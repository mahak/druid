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


package org.apache.druid.indexing.overlord;

import org.apache.druid.indexing.overlord.autoscaling.ProvisioningService;
import org.apache.druid.indexing.overlord.autoscaling.ProvisioningStrategy;
import org.apache.druid.indexing.overlord.autoscaling.ScalingStats;

import javax.annotation.Nonnull;
import java.util.Collection;


public class TestProvisioningStrategy<T extends TaskRunner> implements ProvisioningStrategy<T>
{
  @Override
  public ProvisioningService makeProvisioningService(T runner)
  {
    return new ProvisioningService()
    {
      @Override
      public void close()
      {
        // nothing to close
      }

      @Override
      public ScalingStats getStats()
      {
        return null;
      }
    };
  }

  @Override
  public int getExpectedWorkerCapacity(@Nonnull Collection<ImmutableWorkerInfo> workers)
  {
    return 1;
  }
}
