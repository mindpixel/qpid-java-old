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
package org.apache.qpid.server.store.berkeleydb.replication;

import static org.apache.qpid.server.virtualhost.berkeleydb.BDBHAVirtualHost.LOCAL_TRANSACTION_SYNCHRONIZATION_POLICY;
import static org.apache.qpid.server.virtualhost.berkeleydb.BDBHAVirtualHost.REMOTE_TRANSACTION_SYNCHRONIZATION_POLICY;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.server.virtualhostnode.AbstractVirtualHostNode;
import org.apache.qpid.server.virtualhostnode.berkeleydb.BDBHAVirtualHostNode;
import org.apache.qpid.systest.rest.Asserts;
import org.apache.qpid.systest.rest.QpidRestTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;
import org.apache.qpid.util.FileUtils;

public class BDBHAVirtualHostRestTest extends QpidRestTestCase
{
    private String _hostName;
    private File _storeBaseDir;
    private int _nodeHaPort;
    private Object _nodeName;
    private String _virtualhostUrl;
    private String _bluePrint;
    private List<String> _permittedNodes;
    private String _address;
    private int _httpPort;

    @Override
    public void setUp() throws Exception
    {
        setTestSystemProperty(ReplicatedEnvironmentFacade.REMOTE_NODE_MONITOR_INTERVAL_PROPERTY_NAME, "1000");
        setTestSystemProperty(ReplicatedEnvironmentFacade.REMOTE_NODE_MONITOR_TIMEOUT_PROPERTY_NAME, "1000");

        _hostName = "ha";
        _nodeName = "node1";
        _storeBaseDir = new File(TMP_FOLDER, "store-" + _hostName + "-" + System.currentTimeMillis());
        _httpPort = findFreePort();
        _nodeHaPort = getNextAvailable(_httpPort + 1);
        _virtualhostUrl = "virtualhost/" + _nodeName + "/" + _hostName;
        _bluePrint = GroupCreator.getBlueprint();
        _permittedNodes = GroupCreator.getPermittedNodes("localhost", _nodeHaPort);
        _address = "localhost:" + _nodeHaPort;
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception
    {
        try
        {
            super.tearDown();
        }
        finally
        {
            if (_storeBaseDir != null)
            {
                FileUtils.delete(_storeBaseDir, true);
            }
        }
    }

    @Override
    protected void customizeConfiguration() throws Exception
    {
        super.customizeConfiguration();
        TestBrokerConfiguration config = getDefaultBrokerConfiguration();
        config.removeObjectConfiguration(VirtualHostNode.class, TEST2_VIRTUALHOST);
        config.removeObjectConfiguration(VirtualHostNode.class, TEST3_VIRTUALHOST);

        Map<String, Object> nodeAttributes = new HashMap<String, Object>();
        nodeAttributes.put(BDBHAVirtualHostNode.NAME, _nodeName);
        nodeAttributes.put(BDBHAVirtualHostNode.TYPE, "BDB_HA");
        nodeAttributes.put(BDBHAVirtualHostNode.STORE_PATH, _storeBaseDir.getPath() + File.separator + _nodeName);
        nodeAttributes.put(BDBHAVirtualHostNode.GROUP_NAME, _hostName);
        nodeAttributes.put(BDBHAVirtualHostNode.ADDRESS, _address);
        nodeAttributes.put(BDBHAVirtualHostNode.HELPER_ADDRESS, "localhost:" + _nodeHaPort);
        nodeAttributes.put(BDBHAVirtualHostNode.HELPER_NODE_NAME, _nodeName);

        nodeAttributes.put(BDBHAVirtualHostNode.PERMITTED_NODES, _permittedNodes);
        Map<String, String> context = new HashMap<String,String>();
        context.put(AbstractVirtualHostNode.VIRTUALHOST_BLUEPRINT_CONTEXT_VAR, _bluePrint);

        nodeAttributes.put(BDBHAVirtualHostNode.CONTEXT, context);
        config.addObjectConfiguration(VirtualHostNode.class, nodeAttributes);
        config.setObjectAttribute(Port.class, TestBrokerConfiguration.ENTRY_NAME_HTTP_PORT, Port.PORT, _httpPort);
    }

    public void testSetLocalTransactionSynchronizationPolicy() throws Exception
    {
        Map<String, Object> hostAttributes = _restTestHelper.waitForAttributeChanged(_virtualhostUrl, VirtualHost.STATE, State.ACTIVE.name());
        assertEquals("Unexpected synchronization policy before change", "SYNC", hostAttributes.get(LOCAL_TRANSACTION_SYNCHRONIZATION_POLICY));

        Map<String, Object> newPolicy = Collections.<String, Object>singletonMap(LOCAL_TRANSACTION_SYNCHRONIZATION_POLICY, "NO_SYNC");
        getRestTestHelper().submitRequest(_virtualhostUrl, "PUT", newPolicy, HttpServletResponse.SC_OK);

        hostAttributes = getRestTestHelper().getJsonAsSingletonList(_virtualhostUrl);
        assertEquals("Unexpected synchronization policy after change", "NO_SYNC", hostAttributes.get(LOCAL_TRANSACTION_SYNCHRONIZATION_POLICY));
    }

    public void testSetRemoteTransactionSynchronizationPolicy() throws Exception
    {
        Map<String, Object> hostAttributes = _restTestHelper.waitForAttributeChanged(_virtualhostUrl, VirtualHost.STATE, State.ACTIVE.name());
        assertEquals("Unexpected synchronization policy before change", "NO_SYNC", hostAttributes.get(REMOTE_TRANSACTION_SYNCHRONIZATION_POLICY));

        Map<String, Object> newPolicy = Collections.<String, Object>singletonMap(REMOTE_TRANSACTION_SYNCHRONIZATION_POLICY, "SYNC");
        getRestTestHelper().submitRequest(_virtualhostUrl, "PUT", newPolicy, HttpServletResponse.SC_OK);

        hostAttributes = getRestTestHelper().getJsonAsSingletonList(_virtualhostUrl);
        assertEquals("Unexpected synchronization policy after change", "SYNC", hostAttributes.get(REMOTE_TRANSACTION_SYNCHRONIZATION_POLICY));
    }

    public void testMutateState() throws Exception
    {
        _restTestHelper.waitForAttributeChanged(_virtualhostUrl, VirtualHost.STATE, "ACTIVE");
        assertActualAndDesireStates(_virtualhostUrl, "ACTIVE", "ACTIVE");

        Map<String, Object> newAttributes = Collections.<String, Object>singletonMap(VirtualHost.DESIRED_STATE, "STOPPED");
        getRestTestHelper().submitRequest(_virtualhostUrl, "PUT", newAttributes, HttpServletResponse.SC_OK);

        _restTestHelper.waitForAttributeChanged(_virtualhostUrl, VirtualHost.STATE, "STOPPED");
        assertActualAndDesireStates(_virtualhostUrl, "STOPPED", "STOPPED");

        newAttributes = Collections.<String, Object>singletonMap(VirtualHost.DESIRED_STATE, "ACTIVE");
        getRestTestHelper().submitRequest(_virtualhostUrl, "PUT", newAttributes, HttpServletResponse.SC_OK);

        _restTestHelper.waitForAttributeChanged(_virtualhostUrl, VirtualHost.STATE, "ACTIVE");
        assertActualAndDesireStates(_virtualhostUrl, "ACTIVE", "ACTIVE");
    }

    private void assertActualAndDesireStates(final String restUrl,
                                             final String expectedDesiredState,
                                             final String expectedActualState) throws IOException
    {
        Map<String, Object> virtualhost = getRestTestHelper().getJsonAsSingletonList(restUrl);
        Asserts.assertActualAndDesiredState(expectedDesiredState, expectedActualState, virtualhost);
    }

}
