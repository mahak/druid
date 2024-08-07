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

package org.apache.druid.indexing.overlord.http;

import com.google.common.base.Optional;
import org.apache.druid.indexing.overlord.DruidOverlord;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;

public class OverlordRedirectInfoTest
{
  private DruidOverlord overlord;
  private OverlordRedirectInfo redirectInfo;

  @Before
  public void setUp()
  {
    overlord = EasyMock.createMock(DruidOverlord.class);
    redirectInfo = new OverlordRedirectInfo(overlord);
  }

  @Test
  public void testDoLocalWhenLeading()
  {
    EasyMock.expect(overlord.isLeader()).andReturn(true).anyTimes();
    EasyMock.replay(overlord);
    Assert.assertTrue(redirectInfo.doLocal(null));
    Assert.assertTrue(redirectInfo.doLocal("/druid/indexer/v1/leader"));
    Assert.assertTrue(redirectInfo.doLocal("/druid/indexer/v1/isLeader"));
    Assert.assertTrue(redirectInfo.doLocal("/druid/indexer/v1/other/path"));
    EasyMock.verify(overlord);
  }

  @Test
  public void testDoLocalWhenNotLeading()
  {
    EasyMock.expect(overlord.isLeader()).andReturn(false).anyTimes();
    EasyMock.replay(overlord);
    Assert.assertFalse(redirectInfo.doLocal(null));
    Assert.assertTrue(redirectInfo.doLocal("/druid/indexer/v1/leader"));
    Assert.assertTrue(redirectInfo.doLocal("/druid/indexer/v1/isLeader"));
    Assert.assertFalse(redirectInfo.doLocal("/druid/indexer/v1/other/path"));
    EasyMock.verify(overlord);
  }

  @Test
  public void testGetRedirectURLWithEmptyLocation()
  {
    EasyMock.expect(overlord.getRedirectLocation()).andReturn(Optional.absent()).anyTimes();
    EasyMock.replay(overlord);
    URL url = redirectInfo.getRedirectURL("query", "/request");
    Assert.assertNull(url);
    EasyMock.verify(overlord);
  }

  @Test
  public void testGetRedirectURL()
  {
    String host = "http://localhost";
    String query = "foo=bar&x=y";
    String request = "/request";
    EasyMock.expect(overlord.getRedirectLocation()).andReturn(Optional.of(host)).anyTimes();
    EasyMock.replay(overlord);
    URL url = redirectInfo.getRedirectURL(query, request);
    Assert.assertEquals("http://localhost/request?foo=bar&x=y", url.toString());
    EasyMock.verify(overlord);
  }

  @Test
  public void testGetRedirectURLWithEncodedCharacter() throws UnsupportedEncodingException
  {
    String host = "http://localhost";
    String request = "/druid/indexer/v1/task/" + URLEncoder.encode(
        "index_hadoop_datasource_2017-07-12T07:43:01.495Z",
        "UTF-8"
    ) + "/status";

    EasyMock.expect(overlord.getRedirectLocation()).andReturn(Optional.of(host)).anyTimes();
    EasyMock.replay(overlord);
    URL url = redirectInfo.getRedirectURL(null, request);
    Assert.assertEquals(
        "http://localhost/druid/indexer/v1/task/index_hadoop_datasource_2017-07-12T07%3A43%3A01.495Z/status",
        url.toString()
    );
    EasyMock.verify(overlord);
  }

}
