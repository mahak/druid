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

package org.apache.druid.query.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.java.util.common.Cacheable;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.ordering.StringComparator;
import org.apache.druid.query.ordering.StringComparators;

import java.util.Comparator;

public class SearchSortSpec implements Cacheable
{
  public static final StringComparator DEFAULT_ORDERING = StringComparators.LEXICOGRAPHIC;

  private final StringComparator ordering;

  @JsonCreator
  public SearchSortSpec(
      @JsonProperty("type") StringComparator ordering
  )
  {
    this.ordering = ordering == null ? DEFAULT_ORDERING : ordering;
  }

  @JsonProperty("type")
  public StringComparator getOrdering()
  {
    return ordering;
  }

  public Comparator<SearchHit> getComparator()
  {
    return new Comparator<>()
    {
      @Override
      public int compare(SearchHit searchHit, SearchHit searchHit1)
      {
        int retVal = ordering.compare(
            searchHit.getValue(), searchHit1.getValue());

        if (retVal == 0) {
          retVal = StringComparators.LEXICOGRAPHIC.compare(
              searchHit.getDimension(), searchHit1.getDimension());
        }
        return retVal;
      }
    };
  }

  @Override
  public byte[] getCacheKey()
  {
    return ordering.getCacheKey();
  }

  @Override
  public String toString()
  {
    return StringUtils.format("%sSort", ordering.toString());
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    SearchSortSpec that = (SearchSortSpec) o;

    return ordering.equals(that.ordering);

  }

  @Override
  public int hashCode()
  {
    return ordering.hashCode();
  }
}
