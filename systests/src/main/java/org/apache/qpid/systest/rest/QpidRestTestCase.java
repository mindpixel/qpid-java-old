/*
 *
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
 *
 */
package org.apache.qpid.systest.rest;

import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;

public class QpidRestTestCase extends QpidBrokerTestCase
{
    public static final String EXTERNAL_AUTHENTICATION_PROVIDER = "testExternal";

    public static final String TEST1_VIRTUALHOST = "test";
    public static final String TEST2_VIRTUALHOST = "test2";
    public static final String TEST3_VIRTUALHOST = "test3";

    public static final String[] EXPECTED_VIRTUALHOSTS = { TEST1_VIRTUALHOST, TEST2_VIRTUALHOST, TEST3_VIRTUALHOST};
    public static final String[] EXPECTED_EXCHANGES = { "amq.fanout", "amq.match", "amq.direct","amq.topic" };

    protected RestTestHelper _restTestHelper;

    @Override
    public void setUp() throws Exception
    {
        // remove the normal 'test' vhost, we will configure the vhosts below
        getDefaultBrokerConfiguration().removeObjectConfiguration(VirtualHostNode.class, TestBrokerConfiguration.ENTRY_NAME_VIRTUAL_HOST);

        // Set up virtualhost config with queues and bindings to the amq.direct
        for (String virtualhost : EXPECTED_VIRTUALHOSTS)
        {
            createTestVirtualHostNode(virtualhost);
        }

        customizeConfiguration();
        super.setUp();
    }

    @Override
    public void startDefaultBroker() throws Exception
    {
        super.startDefaultBroker();
        _restTestHelper = new RestTestHelper(getDefaultBroker().getHttpPort());
    }

    @Override
    protected void tearDown() throws Exception
    {
        try
        {
            super.tearDown();
        }
        finally
        {
            getRestTestHelper().tearDown();
        }
    }

    protected void customizeConfiguration() throws Exception
    {
        getDefaultBrokerConfiguration().addHttpManagementConfiguration();
    }

    public RestTestHelper getRestTestHelper()
    {
        return _restTestHelper;
    }
}
