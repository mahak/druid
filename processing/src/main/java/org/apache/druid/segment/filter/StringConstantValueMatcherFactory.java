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

package org.apache.druid.segment.filter;

import org.apache.druid.query.filter.SelectorPredicateFactory;
import org.apache.druid.query.filter.ValueMatcher;
import org.apache.druid.segment.BaseDoubleColumnValueSelector;
import org.apache.druid.segment.BaseFloatColumnValueSelector;
import org.apache.druid.segment.BaseLongColumnValueSelector;
import org.apache.druid.segment.BaseObjectColumnValueSelector;
import org.apache.druid.segment.ColumnProcessorFactory;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnType;

import javax.annotation.Nullable;

/**
 * Creates {@link ValueMatcher} that match constants.
 */
public class StringConstantValueMatcherFactory implements ColumnProcessorFactory<ValueMatcher>
{
  @Nullable
  private final String matchValue;

  StringConstantValueMatcherFactory(@Nullable String matchValue)
  {
    this.matchValue = matchValue;
  }

  @Override
  public ColumnType defaultType()
  {
    // todo: ??
    return ColumnType.UNKNOWN_COMPLEX;
  }

  @Override
  public ValueMatcher makeDimensionProcessor(DimensionSelector selector, boolean multiValue)
  {
    return ValueMatchers.makeStringValueMatcher(selector, matchValue, multiValue);
  }

  @Override
  public ValueMatcher makeFloatProcessor(BaseFloatColumnValueSelector selector)
  {
    return ValueMatchers.makeFloatValueMatcher(selector, matchValue);
  }

  @Override
  public ValueMatcher makeDoubleProcessor(BaseDoubleColumnValueSelector selector)
  {
    return ValueMatchers.makeDoubleValueMatcher(selector, matchValue);
  }

  @Override
  public ValueMatcher makeLongProcessor(BaseLongColumnValueSelector selector)
  {
    return ValueMatchers.makeLongValueMatcher(selector, matchValue);
  }

  @Override
  public ValueMatcher makeArrayProcessor(
      BaseObjectColumnValueSelector<?> selector,
      @Nullable ColumnCapabilities columnCapabilities
  )
  {
    // this is gonna fail because SelectorPredicateFactory does not implement array predicate...
    // let it happen anyway to bubble up standard error messaging about predicate not supporting arrays
    return new PredicateValueMatcherFactory(
        new SelectorPredicateFactory(matchValue)
    ).makeArrayProcessor(selector, columnCapabilities);
  }

  @Override
  public ValueMatcher makeComplexProcessor(BaseObjectColumnValueSelector<?> selector)
  {
    return new PredicateValueMatcherFactory(new SelectorPredicateFactory(matchValue)).makeComplexProcessor(selector);
  }
}
