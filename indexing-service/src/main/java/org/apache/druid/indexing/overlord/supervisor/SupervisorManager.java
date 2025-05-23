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

package org.apache.druid.indexing.overlord.supervisor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import org.apache.druid.common.guava.FutureUtils;
import org.apache.druid.error.DruidException;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.indexing.common.TaskLockType;
import org.apache.druid.indexing.common.task.Tasks;
import org.apache.druid.indexing.overlord.DataSourceMetadata;
import org.apache.druid.indexing.overlord.supervisor.autoscaler.SupervisorTaskAutoScaler;
import org.apache.druid.indexing.seekablestream.supervisor.SeekableStreamSupervisor;
import org.apache.druid.indexing.seekablestream.supervisor.SeekableStreamSupervisorSpec;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.metadata.MetadataSupervisorManager;
import org.apache.druid.metadata.PendingSegmentRecord;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.segment.incremental.ParseExceptionReport;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Manages the creation and lifetime of {@link Supervisor}.
 */
public class SupervisorManager
{
  private static final EmittingLogger log = new EmittingLogger(SupervisorManager.class);

  private final MetadataSupervisorManager metadataSupervisorManager;
  private final ConcurrentHashMap<String, Pair<Supervisor, SupervisorSpec>> supervisors = new ConcurrentHashMap<>();
  // SupervisorTaskAutoScaler could be null
  private final ConcurrentHashMap<String, SupervisorTaskAutoScaler> autoscalers = new ConcurrentHashMap<>();
  private final Object lock = new Object();

  private volatile boolean started = false;
  private final ObjectMapper jsonMapper;

  @Inject
  public SupervisorManager(@Json ObjectMapper jsonMapper, MetadataSupervisorManager metadataSupervisorManager)
  {
    this.jsonMapper = jsonMapper;
    this.metadataSupervisorManager = metadataSupervisorManager;
  }

  public MetadataSupervisorManager getMetadataSupervisorManager()
  {
    return metadataSupervisorManager;
  }

  public Set<String> getSupervisorIds()
  {
    return supervisors.keySet();
  }

  /**
   * @param datasource Datasource to find active supervisor id with append lock for.
   * @return An optional with the active appending supervisor id if it exists.
   */
  public Optional<String> getActiveSupervisorIdForDatasourceWithAppendLock(String datasource)
  {
    for (Map.Entry<String, Pair<Supervisor, SupervisorSpec>> entry : supervisors.entrySet()) {
      final String supervisorId = entry.getKey();
      final Supervisor supervisor = entry.getValue().lhs;
      final SupervisorSpec supervisorSpec = entry.getValue().rhs;

      boolean hasAppendLock = Tasks.DEFAULT_USE_CONCURRENT_LOCKS;
      if (supervisorSpec instanceof SeekableStreamSupervisorSpec) {
        SeekableStreamSupervisorSpec seekableStreamSupervisorSpec = (SeekableStreamSupervisorSpec) supervisorSpec;
        Map<String, Object> context = seekableStreamSupervisorSpec.getContext();
        if (context != null) {
          Boolean useConcurrentLocks = QueryContexts.getAsBoolean(
              Tasks.USE_CONCURRENT_LOCKS,
              context.get(Tasks.USE_CONCURRENT_LOCKS)
          );
          if (useConcurrentLocks == null) {
            TaskLockType taskLockType = QueryContexts.getAsEnum(
                Tasks.TASK_LOCK_TYPE,
                context.get(Tasks.TASK_LOCK_TYPE),
                TaskLockType.class
            );
            if (taskLockType == null) {
              hasAppendLock = Tasks.DEFAULT_USE_CONCURRENT_LOCKS;
            } else if (taskLockType == TaskLockType.APPEND) {
              hasAppendLock = true;
            } else {
              hasAppendLock = false;
            }
          } else {
            hasAppendLock = useConcurrentLocks;
          }
        }
      }

      if (supervisor instanceof SeekableStreamSupervisor
          && !supervisorSpec.isSuspended()
          && supervisorSpec.getDataSources().contains(datasource)
          && (hasAppendLock)) {
        return Optional.of(supervisorId);
      }
    }

    return Optional.absent();
  }

  public Optional<SupervisorSpec> getSupervisorSpec(String id)
  {
    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);
    return supervisor == null ? Optional.absent() : Optional.fromNullable(supervisor.rhs);
  }

  public Optional<SupervisorStateManager.State> getSupervisorState(String id)
  {
    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);
    return supervisor == null ? Optional.absent() : Optional.fromNullable(supervisor.lhs.getState());
  }

  public boolean handoffTaskGroupsEarly(String id, List<Integer> taskGroupIds)
  {
    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);
    if (supervisor == null || supervisor.lhs == null) {
      return false;
    }
    final StreamSupervisor streamSupervisor = requireStreamSupervisor(id, "handoff");
    streamSupervisor.handoffTaskGroupsEarly(taskGroupIds);
    return true;
  }

  /**
   * Creates or updates a supervisor and then starts it.
   * If no change has been made to the supervisor spec, it is only restarted.
   *
   * @return true if the supervisor was updated, false otherwise
   */
  public boolean createOrUpdateAndStartSupervisor(SupervisorSpec spec)
  {
    Preconditions.checkState(started, "SupervisorManager not started");
    Preconditions.checkNotNull(spec, "spec");
    Preconditions.checkNotNull(spec.getId(), "spec.getId()");
    Preconditions.checkNotNull(spec.getDataSources(), "spec.getDatasources()");

    synchronized (lock) {
      Preconditions.checkState(started, "SupervisorManager not started");
      final boolean shouldUpdateSpec = shouldUpdateSupervisor(spec);
      possiblyStopAndRemoveSupervisorInternal(spec.getId(), false);
      createAndStartSupervisorInternal(spec, shouldUpdateSpec);
      return shouldUpdateSpec;
    }
  }

  /**
   * Checks whether the submitted SupervisorSpec differs from the current spec in SupervisorManager's supervisor list.
   * This is used in SupervisorResource specPost to determine whether the Supervisor needs to be restarted
   * @param spec The spec submitted
   * @return boolean - true only if the spec has been modified, false otherwise
   */
  public boolean shouldUpdateSupervisor(SupervisorSpec spec)
  {
    Preconditions.checkState(started, "SupervisorManager not started");
    Preconditions.checkNotNull(spec, "spec");
    Preconditions.checkNotNull(spec.getId(), "spec.getId()");
    Preconditions.checkNotNull(spec.getDataSources(), "spec.getDatasources()");
    synchronized (lock) {
      Preconditions.checkState(started, "SupervisorManager not started");
      try {
        byte[] specAsBytes = jsonMapper.writeValueAsBytes(spec);
        Pair<Supervisor, SupervisorSpec> currentSupervisor = supervisors.get(spec.getId());
        if (currentSupervisor == null || currentSupervisor.rhs == null) {
          return true;
        } else if (Arrays.equals(specAsBytes, jsonMapper.writeValueAsBytes(currentSupervisor.rhs))) {
          return false;
        } else {
          // The spec bytes are different, so we need to check if the update is allowed
          currentSupervisor.rhs.validateSpecUpdateTo(spec);
          return true;
        }
      }
      catch (JsonProcessingException ex) {
        log.warn("Failed to write spec as bytes for spec_id[%s]", spec.getId());
      }
    }
    return true;
  }

  public boolean stopAndRemoveSupervisor(String id)
  {
    Preconditions.checkState(started, "SupervisorManager not started");
    Preconditions.checkNotNull(id, "id");

    synchronized (lock) {
      Preconditions.checkState(started, "SupervisorManager not started");
      return possiblyStopAndRemoveSupervisorInternal(id, true);
    }
  }

  public boolean suspendOrResumeSupervisor(String id, boolean suspend)
  {
    Preconditions.checkState(started, "SupervisorManager not started");
    Preconditions.checkNotNull(id, "id");

    synchronized (lock) {
      Preconditions.checkState(started, "SupervisorManager not started");
      return possiblySuspendOrResumeSupervisorInternal(id, suspend);
    }
  }

  @LifecycleStart
  public void start()
  {
    Preconditions.checkState(!started, "SupervisorManager already started");
    log.info("Loading stored supervisors from database");

    synchronized (lock) {
      Map<String, SupervisorSpec> supervisors = metadataSupervisorManager.getLatest();
      for (Map.Entry<String, SupervisorSpec> supervisor : supervisors.entrySet()) {
        final SupervisorSpec spec = supervisor.getValue();
        if (!(spec instanceof NoopSupervisorSpec)) {
          try {
            createAndStartSupervisorInternal(spec, false);
          }
          catch (Exception ex) {
            log.error(ex, "Failed to start supervisor: id [%s]", spec.getId());
          }
        }
      }

      started = true;
    }
  }

  @LifecycleStop
  public void stop()
  {
    Preconditions.checkState(started, "SupervisorManager not started");
    List<ListenableFuture<Void>> stopFutures = new ArrayList<>();
    synchronized (lock) {
      log.info("Stopping [%d] supervisors", supervisors.keySet().size());
      for (String id : supervisors.keySet()) {
        try {
          stopFutures.add(supervisors.get(id).lhs.stopAsync());
          SupervisorTaskAutoScaler autoscaler = autoscalers.get(id);
          if (autoscaler != null) {
            autoscaler.stop();
          }
        }
        catch (Exception e) {
          log.warn(e, "Caught exception while stopping supervisor [%s]", id);
        }
      }
      log.info("Waiting for [%d] supervisors to shutdown", stopFutures.size());
      try {
        FutureUtils.coalesce(stopFutures).get();
      }
      catch (Exception e) {
        log.warn(
            e,
            "Stopped [%d] out of [%d] supervisors. Remaining supervisors will be killed.",
            stopFutures.stream().filter(Future::isDone).count(),
            stopFutures.size()
        );
      }
      supervisors.clear();
      autoscalers.clear();
      started = false;
    }

    log.info("SupervisorManager stopped.");
  }

  public List<VersionedSupervisorSpec> getSupervisorHistoryForId(String id)
  {
    return metadataSupervisorManager.getAllForId(id);
  }

  public Map<String, List<VersionedSupervisorSpec>> getSupervisorHistory()
  {
    return metadataSupervisorManager.getAll();
  }

  public Optional<SupervisorReport> getSupervisorStatus(String id)
  {
    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);
    return supervisor == null ? Optional.absent() : Optional.fromNullable(supervisor.lhs.getStatus());
  }

  public Optional<Map<String, Map<String, Object>>> getSupervisorStats(String id)
  {
    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);
    return supervisor == null ? Optional.absent() : Optional.fromNullable(supervisor.lhs.getStats());
  }

  public Optional<List<ParseExceptionReport>> getSupervisorParseErrors(String id)
  {
    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);
    return supervisor == null ? Optional.absent() : Optional.fromNullable(supervisor.lhs.getParseErrors());
  }

  public Optional<Boolean> isSupervisorHealthy(String id)
  {
    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);
    return supervisor == null ? Optional.absent() : Optional.fromNullable(supervisor.lhs.isHealthy());
  }

  public boolean resetSupervisor(String id, @Nullable DataSourceMetadata resetDataSourceMetadata)
  {
    Preconditions.checkState(started, "SupervisorManager not started");
    Preconditions.checkNotNull(id, "id");

    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);

    if (supervisor == null) {
      return false;
    }

    final StreamSupervisor streamSupervisor = requireStreamSupervisor(id, "reset");
    if (resetDataSourceMetadata == null) {
      streamSupervisor.reset(null);
    } else {
      streamSupervisor.resetOffsets(resetDataSourceMetadata);
    }
    SupervisorTaskAutoScaler autoscaler = autoscalers.get(id);
    if (autoscaler != null) {
      autoscaler.reset();
    }
    return true;
  }

  public boolean checkPointDataSourceMetadata(
      String supervisorId,
      int taskGroupId,
      DataSourceMetadata previousDataSourceMetadata
  )
  {
    try {
      Preconditions.checkState(started, "SupervisorManager not started");
      Preconditions.checkNotNull(supervisorId, "supervisorId cannot be null");

      Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(supervisorId);

      Preconditions.checkNotNull(supervisor, "supervisor could not be found");

      final StreamSupervisor streamSupervisor = requireStreamSupervisor(supervisorId, "checkPoint");
      streamSupervisor.checkpoint(taskGroupId, previousDataSourceMetadata);
      return true;
    }
    catch (Exception e) {
      log.error(e, "Checkpoint request failed");
    }
    return false;
  }

  /**
   * Registers a new version of the given pending segment on a supervisor. This
   * allows the supervisor to include the pending segment in queries fired against
   * that segment version.
   */
  public boolean registerUpgradedPendingSegmentOnSupervisor(
      String supervisorId,
      PendingSegmentRecord upgradedPendingSegment
  )
  {
    try {
      Preconditions.checkNotNull(supervisorId, "supervisorId cannot be null");
      Preconditions.checkNotNull(upgradedPendingSegment, "upgraded pending segment cannot be null");
      Preconditions.checkNotNull(upgradedPendingSegment.getTaskAllocatorId(), "taskAllocatorId cannot be null");
      Preconditions.checkNotNull(
          upgradedPendingSegment.getUpgradedFromSegmentId(),
          "upgradedFromSegmentId cannot be null"
      );

      Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(supervisorId);
      Preconditions.checkNotNull(supervisor, "supervisor could not be found");
      if (!(supervisor.lhs instanceof SeekableStreamSupervisor)) {
        return false;
      }

      SeekableStreamSupervisor<?, ?, ?> seekableStreamSupervisor = (SeekableStreamSupervisor<?, ?, ?>) supervisor.lhs;
      seekableStreamSupervisor.registerNewVersionOfPendingSegment(upgradedPendingSegment);
      return true;
    }
    catch (Exception e) {
      log.error(
          e,
          "Failed to upgrade pending segment[%s] to new pending segment[%s] on Supervisor[%s].",
          upgradedPendingSegment.getUpgradedFromSegmentId(),
          upgradedPendingSegment.getId().getVersion(),
          supervisorId
      );
    }
    return false;
  }


  /**
   * Stops a supervisor with a given id and then removes it from the list.
   * <p/>
   * Caller should have acquired [lock] before invoking this method to avoid contention with other threads that may be
   * starting, stopping, suspending and resuming supervisors.
   *
   * @return true if a supervisor was stopped, false if there was no supervisor with this id
   */
  private boolean possiblyStopAndRemoveSupervisorInternal(String id, boolean writeTombstone)
  {
    Pair<Supervisor, SupervisorSpec> pair = supervisors.get(id);
    if (pair == null) {
      return false;
    }

    if (writeTombstone) {
      metadataSupervisorManager.insert(
          id,
          new NoopSupervisorSpec(null, pair.rhs.getDataSources())
      ); // where NoopSupervisorSpec is a tombstone
    }
    pair.lhs.stop(true);
    supervisors.remove(id);

    SupervisorTaskAutoScaler autoscler = autoscalers.get(id);
    if (autoscler != null) {
      autoscler.stop();
      autoscalers.remove(id);
    }

    return true;
  }

  /**
   * Suspend or resume a supervisor with a given id.
   * <p/>
   * Caller should have acquired [lock] before invoking this method to avoid contention with other threads that may be
   * starting, stopping, suspending and resuming supervisors.
   *
   * @return true if a supervisor was suspended or resumed, false if there was no supervisor with this id
   * or suspend a suspended supervisor or resume a running supervisor
   */
  private boolean possiblySuspendOrResumeSupervisorInternal(String id, boolean suspend)
  {
    Pair<Supervisor, SupervisorSpec> pair = supervisors.get(id);
    if (pair == null || pair.rhs.isSuspended() == suspend) {
      return false;
    }

    SupervisorSpec nextState = suspend ? pair.rhs.createSuspendedSpec() : pair.rhs.createRunningSpec();
    possiblyStopAndRemoveSupervisorInternal(nextState.getId(), false);
    return createAndStartSupervisorInternal(nextState, true);
  }

  /**
   * Creates a supervisor from the provided spec and starts it if there is not already a supervisor with that id.
   * <p/>
   * Caller should have acquired [lock] before invoking this method to avoid contention with other threads that may be
   * starting, stopping, suspending and resuming supervisors.
   *
   * @return true if a new supervisor was created, false if there was already an existing supervisor with this id
   */
  private boolean createAndStartSupervisorInternal(SupervisorSpec spec, boolean persistSpec)
  {
    String id = spec.getId();
    if (supervisors.containsKey(id)) {
      return false;
    }

    Supervisor supervisor;
    SupervisorTaskAutoScaler autoscaler;
    try {
      supervisor = spec.createSupervisor();
      autoscaler = spec.createAutoscaler(supervisor);

      supervisor.start();
      if (autoscaler != null) {
        autoscaler.start();
      }
    }
    catch (Exception e) {
      log.error("Failed to create and start supervisor: [%s]", spec.getId());
      throw new RuntimeException(e);
    }

    if (persistSpec) {
      metadataSupervisorManager.insert(id, spec);
    }

    supervisors.put(id, Pair.of(supervisor, spec));
    if (autoscaler != null) {
      autoscalers.put(id, autoscaler);
    }

    return true;
  }

  private StreamSupervisor requireStreamSupervisor(final String supervisorId, final String operation)
  {
    Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(supervisorId);
    if (supervisor.lhs instanceof StreamSupervisor) {
      return (StreamSupervisor) supervisor.lhs;
    } else {
      throw DruidException.forPersona(DruidException.Persona.USER)
                          .ofCategory(DruidException.Category.UNSUPPORTED)
                          .build(
                              "Operation[%s] is not supported by supervisor[%s] of type[%s].",
                              operation,
                              supervisorId,
                              supervisor.rhs.getType()
                          );
    }
  }

  @Nullable
  private SupervisorSpec getSpec(String id)
  {
    synchronized (lock) {
      Pair<Supervisor, SupervisorSpec> supervisor = supervisors.get(id);
      return supervisor == null ? null : supervisor.rhs;
    }
  }
}
