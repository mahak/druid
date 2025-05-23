/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.coordination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import org.apache.druid.client.CachingQueryRunner;
import org.apache.druid.client.cache.Cache;
import org.apache.druid.client.cache.CacheConfig;
import org.apache.druid.client.cache.CachePopulator;
import org.apache.druid.guice.annotations.Smile;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.guava.FunctionalIterable;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.BySegmentQueryRunner;
import org.apache.druid.query.CPUTimeMetricQueryRunner;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.FinalizeResultsQueryRunner;
import org.apache.druid.query.MetricsEmittingQueryRunner;
import org.apache.druid.query.NoopQueryRunner;
import org.apache.druid.query.PerSegmentOptimizingQueryRunner;
import org.apache.druid.query.PerSegmentQueryOptimizationContext;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.QueryProcessingPool;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.QueryUnsupportedException;
import org.apache.druid.query.ReferenceCountingSegmentQueryRunner;
import org.apache.druid.query.ReportTimelineMissingSegmentQueryRunner;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.planning.ExecutionVertex;
import org.apache.druid.query.policy.PolicyEnforcer;
import org.apache.druid.query.spec.SpecificSegmentQueryRunner;
import org.apache.druid.query.spec.SpecificSegmentSpec;
import org.apache.druid.segment.ReferenceCountingSegment;
import org.apache.druid.segment.SegmentReference;
import org.apache.druid.segment.TimeBoundaryInspector;
import org.apache.druid.server.ResourceIdPopulatingQueryRunner;
import org.apache.druid.server.SegmentManager;
import org.apache.druid.server.SetAndVerifyContextQueryRunner;
import org.apache.druid.server.initialization.ServerConfig;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.apache.druid.utils.JvmUtils;
import org.joda.time.Interval;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Query handler for Historical processes (see CliHistorical).
 * <p>
 * In tests, this class's behavior is partially mimicked by TestClusterQuerySegmentWalker.
 */
public class ServerManager implements QuerySegmentWalker
{
  private static final EmittingLogger log = new EmittingLogger(ServerManager.class);
  private final QueryRunnerFactoryConglomerate conglomerate;
  private final ServiceEmitter emitter;
  private final QueryProcessingPool queryProcessingPool;
  private final CachePopulator cachePopulator;
  private final Cache cache;
  private final ObjectMapper objectMapper;
  private final CacheConfig cacheConfig;
  private final SegmentManager segmentManager;
  private final ServerConfig serverConfig;
  private final PolicyEnforcer policyEnforcer;

  @Inject
  public ServerManager(
      QueryRunnerFactoryConglomerate conglomerate,
      ServiceEmitter emitter,
      QueryProcessingPool queryProcessingPool,
      CachePopulator cachePopulator,
      @Smile ObjectMapper objectMapper,
      Cache cache,
      CacheConfig cacheConfig,
      SegmentManager segmentManager,
      ServerConfig serverConfig,
      PolicyEnforcer policyEnforcer
  )
  {
    this.conglomerate = conglomerate;
    this.emitter = emitter;

    this.queryProcessingPool = queryProcessingPool;
    this.cachePopulator = cachePopulator;
    this.cache = cache;
    this.objectMapper = objectMapper;

    this.cacheConfig = cacheConfig;
    this.segmentManager = segmentManager;
    this.serverConfig = serverConfig;
    this.policyEnforcer = policyEnforcer;
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForIntervals(Query<T> query, Iterable<Interval> intervals)
  {
    final VersionedIntervalTimeline<String, ReferenceCountingSegment> timeline;
    final Optional<VersionedIntervalTimeline<String, ReferenceCountingSegment>> maybeTimeline =
        segmentManager.getTimeline(ExecutionVertex.of(query).getBaseTableDataSource());

    if (maybeTimeline.isPresent()) {
      timeline = maybeTimeline.get();
    } else {
      // Even though we didn't find a timeline for the query datasource, we simply return a NoopQueryRunner
      // instead of reporting missing intervals because the query intervals are a filter rather than something
      // we must find.
      return new NoopQueryRunner<>();
    }

    FunctionalIterable<SegmentDescriptor> segmentDescriptors = FunctionalIterable
        .create(intervals)
        .transformCat(timeline::lookup)
        .transformCat(
            holder -> {
              if (holder == null) {
                return null;
              }

              return FunctionalIterable
                  .create(holder.getObject())
                  .transform(
                      partitionChunk ->
                          new SegmentDescriptor(
                              holder.getInterval(),
                              holder.getVersion(),
                              partitionChunk.getChunkNumber()
                          )
                  );
            }
        );

    return getQueryRunnerForSegments(query, segmentDescriptors);
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForSegments(Query<T> theQuery, Iterable<SegmentDescriptor> specs)
  {
    final Query<T> newQuery = ResourceIdPopulatingQueryRunner.populateResourceId(theQuery);
    final DataSource dataSourceFromQuery = newQuery.getDataSource();
    final QueryRunnerFactory<T, Query<T>> factory = conglomerate.findFactory(newQuery);
    if (factory == null) {
      final QueryUnsupportedException e = new QueryUnsupportedException(
          StringUtils.format("Unknown query type, [%s]", newQuery.getClass())
      );
      log.makeAlert(e, "Error while executing a query[%s]", newQuery.getId())
         .addData("dataSource", dataSourceFromQuery)
         .emit();
      throw e;
    }

    final QueryToolChest<T, Query<T>> toolChest = factory.getToolchest();
    final AtomicLong cpuTimeAccumulator = new AtomicLong(0L);

    final VersionedIntervalTimeline<String, ReferenceCountingSegment> timeline;
    ExecutionVertex ev = ExecutionVertex.of(newQuery);
    final Optional<VersionedIntervalTimeline<String, ReferenceCountingSegment>> maybeTimeline =
        segmentManager.getTimeline(ev.getBaseTableDataSource());

    // Make sure this query type can handle the subquery, if present.
    if ((dataSourceFromQuery instanceof QueryDataSource)
        && !toolChest.canPerformSubquery(((QueryDataSource) dataSourceFromQuery).getQuery())) {
      throw new ISE("Cannot handle subquery: %s", dataSourceFromQuery);
    }

    if (maybeTimeline.isPresent()) {
      timeline = maybeTimeline.get();
    } else {
      return new ReportTimelineMissingSegmentQueryRunner<>(Lists.newArrayList(specs));
    }
    final Function<SegmentReference, SegmentReference> segmentMapFn = JvmUtils.safeAccumulateThreadCpuTime(
        cpuTimeAccumulator,
        () -> ev.createSegmentMapFunction(policyEnforcer)
    );

    // We compute the datasource's cache key here itself so it doesn't need to be re-computed for every segment
    final Optional<byte[]> cacheKeyPrefix = Optional.ofNullable(dataSourceFromQuery.getCacheKey());

    final FunctionalIterable<QueryRunner<T>> queryRunners = FunctionalIterable
        .create(specs)
        .transformCat(
            descriptor -> Collections.singletonList(
                buildQueryRunnerForSegment(
                    newQuery,
                    descriptor,
                    factory,
                    toolChest,
                    timeline,
                    segmentMapFn,
                    cpuTimeAccumulator,
                    cacheKeyPrefix
                )
            )
        );

    return new ResourceIdPopulatingQueryRunner<>(
        CPUTimeMetricQueryRunner.safeBuild(
            new FinalizeResultsQueryRunner<>(
                toolChest.mergeResults(factory.mergeRunners(queryProcessingPool, queryRunners), true),
                toolChest
            ),
            toolChest,
            emitter,
            cpuTimeAccumulator,
            true
        )
    );
  }

  protected <T> QueryRunner<T> buildQueryRunnerForSegment(
      final Query<T> query,
      final SegmentDescriptor descriptor,
      final QueryRunnerFactory<T, Query<T>> factory,
      final QueryToolChest<T, Query<T>> toolChest,
      final VersionedIntervalTimeline<String, ReferenceCountingSegment> timeline,
      final Function<SegmentReference, SegmentReference> segmentMapFn,
      final AtomicLong cpuTimeAccumulator,
      Optional<byte[]> cacheKeyPrefix
  )
  {
    final PartitionChunk<ReferenceCountingSegment> chunk = timeline.findChunk(
        descriptor.getInterval(),
        descriptor.getVersion(),
        descriptor.getPartitionNumber()
    );

    if (chunk == null) {
      return new ReportTimelineMissingSegmentQueryRunner<>(descriptor);
    }

    final ReferenceCountingSegment segment = chunk.getObject();
    return buildAndDecorateQueryRunner(
        factory,
        toolChest,
        segmentMapFn.apply(segment),
        cacheKeyPrefix,
        descriptor,
        cpuTimeAccumulator
    );
  }

  private <T> QueryRunner<T> buildAndDecorateQueryRunner(
      final QueryRunnerFactory<T, Query<T>> factory,
      final QueryToolChest<T, Query<T>> toolChest,
      final SegmentReference segment,
      final Optional<byte[]> cacheKeyPrefix,
      final SegmentDescriptor segmentDescriptor,
      final AtomicLong cpuTimeAccumulator
  )
  {
    // Short-circuit when the index comes from a tombstone (it has no data by definition),
    // check for null also since no all segments (higher level ones) will have QueryableIndex...
    if (segment.isTombstone()) {
      return new NoopQueryRunner<>();
    }

    final SpecificSegmentSpec segmentSpec = new SpecificSegmentSpec(segmentDescriptor);
    final SegmentId segmentId = segment.getId();
    final Interval segmentInterval = segment.getDataInterval();
    // ReferenceCountingSegment can return null for ID or interval if it's already closed.
    // Here, we check one more time if the segment is closed.
    // If the segment is closed after this line, ReferenceCountingSegmentQueryRunner will handle and do the right thing.
    if (segmentId == null || segmentInterval == null) {
      return new ReportTimelineMissingSegmentQueryRunner<>(segmentDescriptor);
    }

    String segmentIdString = segmentId.toString();

    MetricsEmittingQueryRunner<T> metricsEmittingQueryRunnerInner = new MetricsEmittingQueryRunner<>(
        emitter,
        toolChest,
        new ReferenceCountingSegmentQueryRunner<>(factory, segment, segmentDescriptor),
        QueryMetrics::reportSegmentTime,
        queryMetrics -> queryMetrics.segment(segmentIdString)
    );

    final TimeBoundaryInspector timeBoundaryInspector = segment.as(TimeBoundaryInspector.class);
    final Interval cacheKeyInterval =
        timeBoundaryInspector != null ? timeBoundaryInspector.getMinMaxInterval() : segmentInterval;
    CachingQueryRunner<T> cachingQueryRunner = new CachingQueryRunner<>(
        segmentIdString,
        cacheKeyPrefix,
        segmentDescriptor,
        cacheKeyInterval,
        objectMapper,
        cache,
        toolChest,
        metricsEmittingQueryRunnerInner,
        cachePopulator,
        cacheConfig
    );

    BySegmentQueryRunner<T> bySegmentQueryRunner = new BySegmentQueryRunner<>(
        segmentId,
        segmentInterval.getStart(),
        cachingQueryRunner
    );

    MetricsEmittingQueryRunner<T> metricsEmittingQueryRunnerOuter = new MetricsEmittingQueryRunner<>(
        emitter,
        toolChest,
        bySegmentQueryRunner,
        QueryMetrics::reportSegmentAndCacheTime,
        queryMetrics -> queryMetrics.segment(segmentIdString)
    ).withWaitMeasuredFromNow();

    SpecificSegmentQueryRunner<T> specificSegmentQueryRunner = new SpecificSegmentQueryRunner<>(
        metricsEmittingQueryRunnerOuter,
        segmentSpec
    );

    PerSegmentOptimizingQueryRunner<T> perSegmentOptimizingQueryRunner = new PerSegmentOptimizingQueryRunner<>(
        specificSegmentQueryRunner,
        new PerSegmentQueryOptimizationContext(segmentDescriptor)
    );

    return new SetAndVerifyContextQueryRunner<>(
        serverConfig,
        CPUTimeMetricQueryRunner.safeBuild(
            perSegmentOptimizingQueryRunner,
            toolChest,
            emitter,
            cpuTimeAccumulator,
            false
        )
    );
  }
}
