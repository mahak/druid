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

package org.apache.druid.client.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;

public class ClientCompactionTaskTransformSpecTest
{
  @Test
  public void testEquals()
  {
    EqualsVerifier.forClass(ClientCompactionTaskTransformSpec.class)
                  .withNonnullFields("filter")
                  .usingGetClass()
                  .verify();
  }

  @Test
  public void testSerde() throws IOException
  {
    final ClientCompactionTaskTransformSpec expected = new ClientCompactionTaskTransformSpec(
        new SelectorDimFilter("dim1", "foo", null)
    );
    final ObjectMapper mapper = new DefaultObjectMapper();
    final byte[] json = mapper.writeValueAsBytes(expected);
    final ClientCompactionTaskTransformSpec fromJson = (ClientCompactionTaskTransformSpec) mapper.readValue(
        json,
        ClientCompactionTaskTransformSpec.class
    );
    Assert.assertEquals(expected, fromJson);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testAsMap()
  {
    final ObjectMapper objectMapper = new DefaultObjectMapper();
    String dimension = "dim1";
    String value = "foo";
    final ClientCompactionTaskTransformSpec spec = new ClientCompactionTaskTransformSpec(new SelectorDimFilter(dimension, value, null));
    final Map<String, Object> map = spec.asMap(objectMapper);
    Assert.assertNotNull(map);
    Assert.assertEquals(3, ((Map<String, Object>) map.get("filter")).size());
    Assert.assertEquals(dimension, ((Map<String, Object>) map.get("filter")).get("dimension"));
    Assert.assertEquals(value, ((Map<String, Object>) map.get("filter")).get("value"));
    ClientCompactionTaskTransformSpec actual = objectMapper.convertValue(map, ClientCompactionTaskTransformSpec.class);
    Assert.assertEquals(spec, actual);
  }
}
