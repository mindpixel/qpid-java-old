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
package org.apache.qpid.systest.rest.acl;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.server.security.acl.AbstractACLTestCase;
import org.apache.qpid.server.virtualhostnode.JsonVirtualHostNode;
import org.apache.qpid.systest.rest.QpidRestTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;

public class VirtualHostNodeACLTest extends QpidRestTestCase
{
    private static final String TEST_VIRTUAL_HOST_NODE = "myTestVirtualHostNode";
    private static final String ALLOWED_USER = "user1";
    private static final String DENIED_USER = "user2";

    @Override
    protected void customizeConfiguration() throws Exception
    {
        super.customizeConfiguration();
        final TestBrokerConfiguration defaultBrokerConfiguration = getDefaultBrokerConfiguration();
        defaultBrokerConfiguration.configureTemporaryPasswordFile(ALLOWED_USER, DENIED_USER);

        AbstractACLTestCase.writeACLFileUtil(this, "ACL ALLOW-LOG ALL ACCESS MANAGEMENT",
                "ACL ALLOW-LOG " + ALLOWED_USER + " ALL VIRTUALHOSTNODE",
                "ACL DENY-LOG " + DENIED_USER + " ALL VIRTUALHOSTNODE",
                "ACL DENY-LOG ALL ALL");

        Map<String, Object> virtualHostNodeAttributes = new HashMap<>();
        virtualHostNodeAttributes.put(VirtualHostNode.NAME, TEST_VIRTUAL_HOST_NODE);
        virtualHostNodeAttributes.put(VirtualHostNode.TYPE, getTestProfileVirtualHostNodeType());
        // TODO need better way to determine the VHN's optional attributes
        virtualHostNodeAttributes.put(JsonVirtualHostNode.STORE_PATH, getStoreLocation(TEST_VIRTUAL_HOST_NODE));

        defaultBrokerConfiguration.addObjectConfiguration(VirtualHostNode.class, virtualHostNodeAttributes);
    }

    public void testCreateVirtualHostNodeAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String hostName = getTestName();

        int responseCode = createVirtualHostNode(hostName);
        assertEquals("Virtual host node creation should be allowed", HttpServletResponse.SC_CREATED, responseCode);

        assertVirtualHostNodeExists(hostName);
    }

    public void testCreateVirtualHostNodeDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        String hostName = getTestName();

        int responseCode = createVirtualHostNode(hostName);
        assertEquals("Virtual host node creation should be denied", HttpServletResponse.SC_FORBIDDEN, responseCode);

        assertVirtualHostNodeDoesNotExist(hostName);
    }

    public void testDeleteVirtualHostNodeDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);
        getRestTestHelper().submitRequest("virtualhostnode/" + TEST_VIRTUAL_HOST_NODE, "DELETE", HttpServletResponse.SC_FORBIDDEN);

        assertVirtualHostNodeExists(TEST_VIRTUAL_HOST_NODE);
    }

    /* === Utility Methods === */

    private int createVirtualHostNode(String virtualHostNodeName) throws Exception
    {
        Map<String, Object> data = new HashMap<>();
        data.put(VirtualHostNode.NAME, virtualHostNodeName);
        data.put(VirtualHostNode.TYPE, getTestProfileVirtualHostNodeType());
        data.put(JsonVirtualHostNode.STORE_PATH, getStoreLocation(virtualHostNodeName));

        return getRestTestHelper().submitRequest("virtualhostnode/" + virtualHostNodeName, "PUT", data);
    }

    private void assertVirtualHostNodeDoesNotExist(String name) throws Exception
    {
        assertVirtualHostNodeExistence(name, false);
    }

    private void assertVirtualHostNodeExists(String name) throws Exception
    {
        assertVirtualHostNodeExistence(name, true);
    }

    private void assertVirtualHostNodeExistence(String name, boolean exists) throws Exception
    {
        int expectedResponseCode = exists ? HttpServletResponse.SC_OK : HttpServletResponse.SC_NOT_FOUND;
        getRestTestHelper().submitRequest("virtualhostnode/" + name, "GET", expectedResponseCode);
    }

    private String getStoreLocation(String hostName)
    {
        return new File(TMP_FOLDER, "store-" + hostName + "-" + System.currentTimeMillis()).getAbsolutePath();
    }

}
