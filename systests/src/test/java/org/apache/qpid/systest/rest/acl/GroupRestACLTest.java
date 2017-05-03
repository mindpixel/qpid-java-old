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
package org.apache.qpid.systest.rest.acl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletResponse;

import org.apache.qpid.server.management.plugin.HttpManagement;
import org.apache.qpid.server.model.Plugin;
import org.apache.qpid.server.security.acl.AbstractACLTestCase;
import org.apache.qpid.systest.rest.QpidRestTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;

public class GroupRestACLTest extends QpidRestTestCase
{
    private static final String FILE_GROUP_MANAGER = TestBrokerConfiguration.ENTRY_NAME_GROUP_FILE;

    private static final String ALLOWED_GROUP = "allowedGroup";
    private static final String DENIED_GROUP = "deniedGroup";
    private static final String OTHER_GROUP = "otherGroup";

    private static final String ALLOWED_USER = "webadmin";
    private static final String DENIED_USER = "admin";
    private static final String OTHER_USER = "admin";

    private File _groupFile;

    @Override
    public void startDefaultBroker() throws Exception
    {
        // tests will start the broker after configuring it
    }

    @Override
    protected void customizeConfiguration() throws Exception
    {
        super.customizeConfiguration();
        getDefaultBrokerConfiguration().setObjectAttribute(Plugin.class, TestBrokerConfiguration.ENTRY_NAME_HTTP_MANAGEMENT, HttpManagement.HTTP_BASIC_AUTHENTICATION_ENABLED, true);
        _groupFile = createTemporaryGroupFile();
        getDefaultBrokerConfiguration().addGroupFileConfiguration(_groupFile.getAbsolutePath());
    }

    @Override
    public void tearDown() throws Exception
    {
        super.tearDown();

        if (_groupFile != null)
        {
            if (_groupFile.exists())
            {
                _groupFile.delete();
            }
        }
    }

    private File createTemporaryGroupFile() throws Exception
    {
        File groupFile = File.createTempFile("group", "grp");
        groupFile.deleteOnExit();

        Properties props = new Properties();
        props.put(ALLOWED_GROUP + ".users", ALLOWED_USER);
        props.put(DENIED_GROUP + ".users", DENIED_USER);
        props.put(OTHER_GROUP + ".users", OTHER_USER);

        try(final FileOutputStream out = new FileOutputStream(groupFile))
        {
            props.store(out, "test group file");
        }

        return groupFile;
    }

    public void testCreateGroup() throws Exception
    {
        AbstractACLTestCase.writeACLFileUtil(this, "ACL ALLOW-LOG ALL ACCESS MANAGEMENT",
                "ACL ALLOW-LOG " + ALLOWED_GROUP + " CREATE GROUP",
                "ACL DENY-LOG " + DENIED_GROUP + " CREATE GROUP");

        //Start the broker with the custom config
        super.startDefaultBroker();
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        Map<String, Object> data = getRestTestHelper().getJsonAsSingletonList("groupprovider/" + FILE_GROUP_MANAGER);
        getRestTestHelper().assertNumberOfGroups(data, 3);

        getRestTestHelper().createGroup("newGroup", FILE_GROUP_MANAGER);

        data = getRestTestHelper().getJsonAsSingletonList("groupprovider/" + FILE_GROUP_MANAGER);
        getRestTestHelper().assertNumberOfGroups(data, 4);

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        getRestTestHelper().createGroup("anotherNewGroup", FILE_GROUP_MANAGER, HttpServletResponse.SC_FORBIDDEN);

        data = getRestTestHelper().getJsonAsSingletonList("groupprovider/" + FILE_GROUP_MANAGER);
        getRestTestHelper().assertNumberOfGroups(data, 4);
    }

    public void testDeleteGroup() throws Exception
    {
        AbstractACLTestCase.writeACLFileUtil(this, "ACL ALLOW-LOG ALL ACCESS MANAGEMENT",
                "ACL ALLOW-LOG " + ALLOWED_GROUP + " DELETE GROUP",
                "ACL DENY-LOG " + DENIED_GROUP + " DELETE GROUP");

        //Start the broker with the custom config
        super.startDefaultBroker();
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        Map<String, Object> data = getRestTestHelper().getJsonAsSingletonList("groupprovider/" + FILE_GROUP_MANAGER);
        getRestTestHelper().assertNumberOfGroups(data, 3);

        getRestTestHelper().removeGroup(OTHER_GROUP, FILE_GROUP_MANAGER, HttpServletResponse.SC_FORBIDDEN);

        data = getRestTestHelper().getJsonAsSingletonList("groupprovider/" + FILE_GROUP_MANAGER);
        getRestTestHelper().assertNumberOfGroups(data, 3);

        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        getRestTestHelper().removeGroup(OTHER_GROUP, FILE_GROUP_MANAGER);

        data = getRestTestHelper().getJsonAsSingletonList("groupprovider/" + FILE_GROUP_MANAGER);
        getRestTestHelper().assertNumberOfGroups(data, 2);
    }

    public void testUpdateGroupAddMember() throws Exception
    {
        AbstractACLTestCase.writeACLFileUtil(this, "ACL ALLOW-LOG ALL ACCESS MANAGEMENT",
                "ACL ALLOW-LOG " + ALLOWED_GROUP + " UPDATE GROUP",
                "ACL DENY-LOG " + DENIED_GROUP + " UPDATE GROUP");

        //Start the broker with the custom config
        super.startDefaultBroker();
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        assertNumberOfGroupMembers(OTHER_GROUP, 1);

        getRestTestHelper().createNewGroupMember(FILE_GROUP_MANAGER, OTHER_GROUP, "newGroupMember", HttpServletResponse.SC_FORBIDDEN);
        assertNumberOfGroupMembers(OTHER_GROUP, 1);

        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);
        getRestTestHelper().createNewGroupMember(FILE_GROUP_MANAGER, OTHER_GROUP, "newGroupMember");
        assertNumberOfGroupMembers(OTHER_GROUP, 2);
    }

    public void testUpdateGroupDeleteMember() throws Exception
    {
        AbstractACLTestCase.writeACLFileUtil(this, "ACL ALLOW-LOG ALL ACCESS MANAGEMENT",
                "ACL ALLOW-LOG " + ALLOWED_GROUP + " UPDATE GROUP",
                "ACL DENY-LOG " + DENIED_GROUP + " UPDATE GROUP");

        //Start the broker with the custom config
        super.startDefaultBroker();
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        assertNumberOfGroupMembers(OTHER_GROUP, 1);

        getRestTestHelper().removeMemberFromGroup(FILE_GROUP_MANAGER, OTHER_GROUP, OTHER_USER, HttpServletResponse.SC_FORBIDDEN);
        assertNumberOfGroupMembers(OTHER_GROUP, 1);

        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);
        getRestTestHelper().removeMemberFromGroup(FILE_GROUP_MANAGER, OTHER_GROUP, OTHER_USER);
        assertNumberOfGroupMembers(OTHER_GROUP, 0);
    }

    private void assertNumberOfGroupMembers(String groupName, int expectedNumberOfMembers) throws IOException
    {
        Map<String, Object> group = getRestTestHelper().getJsonAsSingletonList("group/" + FILE_GROUP_MANAGER + "/" + groupName);
        getRestTestHelper().assertNumberOfGroupMembers(group, expectedNumberOfMembers);
    }
}
