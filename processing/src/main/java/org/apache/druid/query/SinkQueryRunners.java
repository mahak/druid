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

package org.apache.druid.query;

import org.apache.druid.java.util.common.Pair;
import org.joda.time.Interval;

import java.util.Iterator;

public class SinkQueryRunners<T> implements Iterable<QueryRunner<T>>
{
  Iterable<Pair<Interval, QueryRunner<T>>> runners;

  public SinkQueryRunners(Iterable<Pair<Interval, QueryRunner<T>>> runners)
  {
    this.runners = runners;
  }

  public Iterator<Pair<Interval, QueryRunner<T>>> runnerIntervalMappingIterator()
  {
    return runners.iterator();
  }

  @Override
  public Iterator<QueryRunner<T>> iterator()
  {
    Iterator<Pair<Interval, QueryRunner<T>>> runnerIntervalIterator = runners.iterator();
    return new Iterator<>()
    {
      @Override
      public boolean hasNext()
      {
        return runnerIntervalIterator.hasNext();
      }

      @Override
      public QueryRunner<T> next()
      {
        return runnerIntervalIterator.next().rhs;
      }
    };
  }
}
