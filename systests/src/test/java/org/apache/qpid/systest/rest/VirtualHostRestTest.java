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

import static org.apache.qpid.server.management.plugin.servlet.rest.AbstractServlet.SC_UNPROCESSABLE_ENTITY;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.Session;
import javax.servlet.http.HttpServletResponse;

import org.junit.Assert;

import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.queue.LastValueQueue;
import org.apache.qpid.server.queue.PriorityQueue;
import org.apache.qpid.server.queue.SortedQueue;
import org.apache.qpid.server.virtualhost.AbstractVirtualHost;
import org.apache.qpid.server.virtualhost.NodeAutoCreationPolicy;
import org.apache.qpid.server.virtualhost.QueueManagingVirtualHost;
import org.apache.qpid.server.virtualhost.derby.DerbyVirtualHostImpl;
import org.apache.qpid.server.virtualhostnode.JsonVirtualHostNodeImpl;

public class VirtualHostRestTest extends QpidRestTestCase
{
    private static final String VIRTUALHOST_EXCHANGES_ATTRIBUTE = "exchanges";
    public static final String VIRTUALHOST_QUEUES_ATTRIBUTE = "queues";

    public static final String EMPTY_VIRTUALHOSTNODE_NAME = "emptyVHN";

    private Connection _connection;

    @Override
    protected void customizeConfiguration() throws Exception
    {
        super.customizeConfiguration();
        createTestVirtualHostNode(getDefaultBroker(), EMPTY_VIRTUALHOSTNODE_NAME, false);
    }

    public void testGet() throws Exception
    {
        List<Map<String, Object>> hosts = getRestTestHelper().getJsonAsList("virtualhost");
        assertNotNull("Hosts data cannot be null", hosts);
        assertEquals("Unexpected number of hosts", EXPECTED_VIRTUALHOSTS.length, hosts.size());
        for (String hostName : EXPECTED_VIRTUALHOSTS)
        {
            Map<String, Object> host = getRestTestHelper().find("name", hostName, hosts);
            Asserts.assertVirtualHost(hostName, host);
        }
    }

    public void testGetHost() throws Exception
    {
        // create AMQP connection to get connection JSON details
        _connection = getConnection();
        Session session = _connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        createTestQueue(session);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");
        Asserts.assertVirtualHost("test", hostDetails);

        @SuppressWarnings("unchecked")
        Map<String, Object> statistics = (Map<String, Object>) hostDetails.get(Asserts.STATISTICS_ATTRIBUTE);

        assertEquals("Unexpected number of exchanges in statistics", EXPECTED_EXCHANGES.length, statistics.get(
                "exchangeCount"));
        assertEquals("Unexpected number of queues in statistics", 1, statistics.get("queueCount"));
        assertEquals("Unexpected number of connections in statistics", 1, statistics.get("connectionCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exchanges = (List<Map<String, Object>>) hostDetails.get(VIRTUALHOST_EXCHANGES_ATTRIBUTE);
        assertEquals("Unexpected number of exchanges", EXPECTED_EXCHANGES.length, exchanges.size());
        Asserts.assertDurableExchange("amq.fanout", "fanout", getRestTestHelper().find(Exchange.NAME, "amq.fanout", exchanges));
        Asserts.assertDurableExchange("amq.topic", "topic", getRestTestHelper().find(Exchange.NAME, "amq.topic", exchanges));
        Asserts.assertDurableExchange("amq.direct", "direct", getRestTestHelper().find(Exchange.NAME, "amq.direct", exchanges));
        Asserts.assertDurableExchange("amq.match", "headers", getRestTestHelper().find(Exchange.NAME, "amq.match", exchanges));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VIRTUALHOST_QUEUES_ATTRIBUTE);
        assertEquals("Unexpected number of queues", 1, queues.size());
        Map<String, Object> queue = getRestTestHelper().find(Queue.NAME,  getTestQueueName(), queues);
        Asserts.assertQueue(getTestQueueName(), "standard", queue);
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, queue.get(Queue.DURABLE));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connections = getRestTestHelper().getJsonAsList("virtualhost/test/test/getConnections");
        assertEquals("Unexpected number of connections", 1, connections.size());
        Asserts.assertConnection(connections.get(0), isBroker10() ? 2 : 1);
    }

    public void testCreateProvidedVirtualHost() throws Exception
    {
        Map<String, Object> requestData = submitVirtualHost(true, "PUT", HttpServletResponse.SC_CREATED);
        String hostName = (String)requestData.get(VirtualHost.NAME);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/" + EMPTY_VIRTUALHOSTNODE_NAME + "/" + hostName);
        Asserts.assertVirtualHost(hostName, hostDetails);

        assertNewVirtualHost(hostDetails);
    }

    public void testCreateVirtualHostByPutUsingParentURI() throws Exception
    {
        Map<String, Object> data = submitVirtualHost(true, "PUT", HttpServletResponse.SC_CREATED);
        String hostName = (String)data.get(VirtualHost.NAME);

        String url = "virtualhost/" + EMPTY_VIRTUALHOSTNODE_NAME + "/" + hostName;
        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList(url);
        Asserts.assertVirtualHost(hostName, hostDetails);
        assertNewVirtualHost(hostDetails);
    }

    public void testCreateVirtualHostByPostUsingParentURI() throws Exception
    {
        Map<String, Object> data = submitVirtualHost(true, "POST", HttpServletResponse.SC_CREATED);
        String hostName = (String)data.get(VirtualHost.NAME);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/" + EMPTY_VIRTUALHOSTNODE_NAME + "/" + hostName);
        Asserts.assertVirtualHost(hostName, hostDetails);
        assertNewVirtualHost(hostDetails);
    }

    public void testCreateVirtualHostByPutUsingVirtualHostURI() throws Exception
    {
        Map<String, Object> data = submitVirtualHost(false, "PUT", HttpServletResponse.SC_CREATED);
        String hostName = (String)data.get(VirtualHost.NAME);
        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/" + EMPTY_VIRTUALHOSTNODE_NAME + "/" + hostName);
        Asserts.assertVirtualHost(hostName, hostDetails);

        assertNewVirtualHost(hostDetails);

    }

    public void testCreateVirtualHostByPostUsingVirtualHostURI() throws Exception
    {
        Map<String, Object> data = submitVirtualHost(false, "POST", HttpServletResponse.SC_NOT_FOUND);

        String hostName = (String)data.get(VirtualHost.NAME);
        getRestTestHelper().submitRequest("virtualhost/" + EMPTY_VIRTUALHOSTNODE_NAME + "/" + hostName,
                "GET", HttpServletResponse.SC_NOT_FOUND);
    }

    public void testDeleteHost() throws Exception
    {
        getRestTestHelper().submitRequest("virtualhost/" + TEST3_VIRTUALHOST + "/" + TEST3_VIRTUALHOST,
                                          "DELETE",
                                          HttpServletResponse.SC_OK);

        List<Map<String, Object>> hosts = getRestTestHelper().getJsonAsList("virtualhost/" + TEST3_VIRTUALHOST);
        assertEquals("Host should be deleted", 0, hosts.size());
    }

    public void testUpdateByPut() throws Exception
    {
        assertVirtualHostUpdate("PUT");
    }

    public void testUpdateByPost() throws Exception
    {
        assertVirtualHostUpdate("POST");
    }

    private void assertVirtualHostUpdate(String method) throws IOException
    {
        String hostToUpdate = TEST3_VIRTUALHOST;
        String restHostUrl = "virtualhost/" + hostToUpdate + "/" + hostToUpdate;

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList(restHostUrl);
        Asserts.assertVirtualHost(hostToUpdate, hostDetails);

        Map<String, Object> newAttributes = Collections.<String, Object>singletonMap(VirtualHost.DESCRIPTION, "This is a virtual host");
        getRestTestHelper().submitRequest(restHostUrl, method, newAttributes, HttpServletResponse.SC_OK);

        Map<String, Object> rereadHostDetails = getRestTestHelper().getJsonAsSingletonList(restHostUrl);
        Asserts.assertVirtualHost(hostToUpdate, rereadHostDetails);
        assertEquals("This is a virtual host", rereadHostDetails.get(VirtualHost.DESCRIPTION));
    }

    public void testAddValidAutoCreationPolicies() throws IOException
    {
        String hostToUpdate = TEST3_VIRTUALHOST;
        String restHostUrl = "virtualhost/" + hostToUpdate + "/" + hostToUpdate;

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList(restHostUrl);
        Asserts.assertVirtualHost(hostToUpdate, hostDetails);

        NodeAutoCreationPolicy[] policies = new NodeAutoCreationPolicy[] {
            new NodeAutoCreationPolicy()
            {
                @Override
                public String getPattern()
                {
                    return "fooQ*";
                }

                @Override
                public boolean isCreatedOnPublish()
                {
                    return true;
                }

                @Override
                public boolean isCreatedOnConsume()
                {
                    return true;
                }

                @Override
                public String getNodeType()
                {
                    return "Queue";
                }

                @Override
                public Map<String, Object> getAttributes()
                {
                    return Collections.emptyMap();
                }
            },
                new NodeAutoCreationPolicy()
                {
                    @Override
                    public String getPattern()
                    {
                        return "barE*";
                    }

                    @Override
                    public boolean isCreatedOnPublish()
                    {
                        return true;
                    }

                    @Override
                    public boolean isCreatedOnConsume()
                    {
                        return false;
                    }

                    @Override
                    public String getNodeType()
                    {
                        return "Exchange";
                    }

                    @Override
                    public Map<String, Object> getAttributes()
                    {
                        return Collections.<String, Object>singletonMap(Exchange.TYPE, "amq.fanout");
                    }
                }
        };
        Map<String, Object> newAttributes = Collections.<String, Object>singletonMap(QueueManagingVirtualHost.NODE_AUTO_CREATION_POLICIES,
                                                                                     Arrays.asList(policies));
        getRestTestHelper().submitRequest(restHostUrl, "POST", newAttributes, HttpServletResponse.SC_OK);
        Map<String, Object> rereadHostDetails = getRestTestHelper().getJsonAsSingletonList(restHostUrl);

        Object retrievedPolicies = rereadHostDetails.get(QueueManagingVirtualHost.NODE_AUTO_CREATION_POLICIES);
        assertNotNull("Retrieved node policies are null", retrievedPolicies);
        assertTrue("Retrieved node policies are not of expected type", retrievedPolicies instanceof List);
        List retrievedPoliciesList = (List) retrievedPolicies;
        assertFalse("Retrieved node policies is empty", retrievedPoliciesList.isEmpty());
        assertEquals("Retrieved node policies list has incorrect size", 2, retrievedPoliciesList.size());
        assertTrue("First policy is not a map", retrievedPoliciesList.get(0) instanceof Map);
        assertTrue("Second policy is not a map", retrievedPoliciesList.get(1) instanceof Map);
        Map firstPolicy = (Map) retrievedPoliciesList.get(0);
        Map secondPolicy = (Map) retrievedPoliciesList.get(1);
        assertEquals("fooQ*", firstPolicy.get("pattern"));
        assertEquals("barE*", secondPolicy.get("pattern"));
        assertEquals(Boolean.TRUE, firstPolicy.get("createdOnConsume"));
        assertEquals(Boolean.FALSE, secondPolicy.get("createdOnConsume"));

    }


    public void testAddInvalidAutoCreationPolicies() throws IOException
    {

        String hostToUpdate = TEST3_VIRTUALHOST;
        String restHostUrl = "virtualhost/" + hostToUpdate + "/" + hostToUpdate;

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList(restHostUrl);
        Asserts.assertVirtualHost(hostToUpdate, hostDetails);

        NodeAutoCreationPolicy[] policies = new NodeAutoCreationPolicy[] {
                new NodeAutoCreationPolicy()
                {
                    @Override
                    public String getPattern()
                    {
                        return null;
                    }

                    @Override
                    public boolean isCreatedOnPublish()
                    {
                        return true;
                    }

                    @Override
                    public boolean isCreatedOnConsume()
                    {
                        return true;
                    }

                    @Override
                    public String getNodeType()
                    {
                        return "Queue";
                    }

                    @Override
                    public Map<String, Object> getAttributes()
                    {
                        return Collections.emptyMap();
                    }
                }
        };
        Map<String, Object> newAttributes = Collections.<String, Object>singletonMap(QueueManagingVirtualHost.NODE_AUTO_CREATION_POLICIES,
                                                                                     Arrays.asList(policies));
        getRestTestHelper().submitRequest(restHostUrl, "POST", newAttributes, 422);

        Map<String, Object> rereadHostDetails = getRestTestHelper().getJsonAsSingletonList(restHostUrl);

        Object retrievedPolicies = rereadHostDetails.get(QueueManagingVirtualHost.NODE_AUTO_CREATION_POLICIES);
        assertNotNull("Retrieved node policies are null", retrievedPolicies);
        assertTrue("Retrieved node policies are not of expected type", retrievedPolicies instanceof List);
        assertTrue("Retrieved node policies is not empty", ((List)retrievedPolicies).isEmpty());
    }

    public void testMutateState() throws Exception
    {
        String restHostUrl = "virtualhost/" + TEST1_VIRTUALHOST + "/" + TEST1_VIRTUALHOST;

        _restTestHelper.waitForAttributeChanged(restHostUrl, VirtualHost.STATE, "ACTIVE");
        assertActualAndDesireStates(restHostUrl, "ACTIVE", "ACTIVE");

        Map<String, Object> newAttributes = Collections.<String, Object>singletonMap(VirtualHost.DESIRED_STATE, "STOPPED");
        getRestTestHelper().submitRequest(restHostUrl, "PUT", newAttributes, HttpServletResponse.SC_OK);

        _restTestHelper.waitForAttributeChanged(restHostUrl, VirtualHost.STATE, "STOPPED");
        assertActualAndDesireStates(restHostUrl, "STOPPED", "STOPPED");

        newAttributes = Collections.<String, Object>singletonMap(VirtualHost.DESIRED_STATE, "ACTIVE");
        getRestTestHelper().submitRequest(restHostUrl, "PUT", newAttributes, HttpServletResponse.SC_OK);

        _restTestHelper.waitForAttributeChanged(restHostUrl, VirtualHost.STATE, "ACTIVE");

        assertActualAndDesireStates(restHostUrl, "ACTIVE", "ACTIVE");
    }

    public void testMutateStateOfVirtualHostWithQueuesAndMessages() throws Exception
    {
        String testQueueName = getTestQueueName();
        String restHostUrl = "virtualhost/" + TEST1_VIRTUALHOST + "/" + TEST1_VIRTUALHOST;
        String restQueueUrl = "queue/" + TEST1_VIRTUALHOST + "/" + TEST1_VIRTUALHOST + "/" + testQueueName;

        _restTestHelper.waitForAttributeChanged(restHostUrl, VirtualHost.STATE, "ACTIVE");
        assertActualAndDesireStates(restHostUrl, "ACTIVE", "ACTIVE");

        Connection connection = getConnection();
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        Destination dest = session.createQueue(testQueueName);
        session.createConsumer(dest).close();
        session.createProducer(dest).send(session.createTextMessage("My test message"));
        session.commit();
        connection.close();

        assertQueueDepth(restQueueUrl, "Unexpected number of messages before stopped", 1);

        Map<String, Object> newAttributes = Collections.<String, Object>singletonMap(VirtualHost.DESIRED_STATE, "STOPPED");
        getRestTestHelper().submitRequest(restHostUrl, "PUT", newAttributes, HttpServletResponse.SC_OK);

        _restTestHelper.waitForAttributeChanged(restHostUrl, VirtualHost.STATE, "STOPPED");
        assertActualAndDesireStates(restHostUrl, "STOPPED", "STOPPED");

        newAttributes = Collections.<String, Object>singletonMap(VirtualHost.DESIRED_STATE, "ACTIVE");
        getRestTestHelper().submitRequest(restHostUrl, "PUT", newAttributes, HttpServletResponse.SC_OK);

        _restTestHelper.waitForAttributeChanged(restHostUrl, VirtualHost.STATE, "ACTIVE");

        assertActualAndDesireStates(restHostUrl, "ACTIVE", "ACTIVE");

        assertQueueDepth(restQueueUrl, "Unexpected number of messages after restart", 1);
    }

    public void testRecoverVirtualHostInDesiredStateStoppedWithDescription() throws Exception
    {
        String hostToUpdate = TEST3_VIRTUALHOST;
        String restUrl = "virtualhost/" + hostToUpdate + "/" + hostToUpdate;

        assertActualAndDesireStates(restUrl, "ACTIVE", "ACTIVE");

        Map<String, Object> newAttributes = new HashMap<>();
        newAttributes.put(VirtualHost.DESIRED_STATE, "STOPPED");
        newAttributes.put(VirtualHost.DESCRIPTION, "My description");

        getRestTestHelper().submitRequest(restUrl, "PUT", newAttributes, HttpServletResponse.SC_OK);

        assertActualAndDesireStates(restUrl, "STOPPED", "STOPPED");

        restartDefaultBroker();

        Map<String, Object> rereadVirtualhost = getRestTestHelper().getJsonAsSingletonList(restUrl);
        Asserts.assertActualAndDesiredState("STOPPED", "STOPPED", rereadVirtualhost);

        assertEquals("Unexpected description after restart", "My description", rereadVirtualhost.get(VirtualHost.DESCRIPTION));
    }

    public void testPutCreateQueue() throws Exception
    {
        String queueName = getTestQueueName();

        createQueue(queueName + "-standard", "standard", null);

        Map<String, Object> sortedQueueAttributes = new HashMap<String, Object>();
        sortedQueueAttributes.put(SortedQueue.SORT_KEY, "sortme");
        createQueue(queueName + "-sorted", "sorted", sortedQueueAttributes);

        Map<String, Object> priorityQueueAttributes = new HashMap<String, Object>();
        priorityQueueAttributes.put(PriorityQueue.PRIORITIES, 10);
        createQueue(queueName + "-priority", "priority", priorityQueueAttributes);

        Map<String, Object> lvqQueueAttributes = new HashMap<String, Object>();
        lvqQueueAttributes.put(LastValueQueue.LVQ_KEY, "LVQ");
        createQueue(queueName + "-lvq", "lvq", lvqQueueAttributes);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        Map<String, Object> standardQueue = getRestTestHelper().find(Queue.NAME, queueName + "-standard" , queues);
        Map<String, Object> sortedQueue = getRestTestHelper().find(Queue.NAME, queueName + "-sorted" , queues);
        Map<String, Object> priorityQueue = getRestTestHelper().find(Queue.NAME, queueName + "-priority" , queues);
        Map<String, Object> lvqQueue = getRestTestHelper().find(Queue.NAME, queueName + "-lvq" , queues);

        Asserts.assertQueue(queueName + "-standard", "standard", standardQueue);
        Asserts.assertQueue(queueName + "-sorted", "sorted", sortedQueue);
        Asserts.assertQueue(queueName + "-priority", "priority", priorityQueue);
        Asserts.assertQueue(queueName + "-lvq", "lvq", lvqQueue);

        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, standardQueue.get(Queue.DURABLE));
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, sortedQueue.get(Queue.DURABLE));
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, priorityQueue.get(Queue.DURABLE));
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, lvqQueue.get(Queue.DURABLE));

        assertEquals("Unexpected sorted key attribute", "sortme", sortedQueue.get(SortedQueue.SORT_KEY));
        assertEquals("Unexpected lvq key attribute", "LVQ", lvqQueue.get(LastValueQueue.LVQ_KEY));
        assertEquals("Unexpected priorities key attribute", 10, priorityQueue.get(PriorityQueue.PRIORITIES));
    }

    public void testPutCreateExchange() throws Exception
    {
        String exchangeName = getTestName();

        createExchange(exchangeName + "-direct", "direct");
        createExchange(exchangeName + "-topic", "topic");
        createExchange(exchangeName + "-headers", "headers");
        createExchange(exchangeName + "-fanout", "fanout");

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exchanges = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_EXCHANGES_ATTRIBUTE);
        Map<String, Object> directExchange = getRestTestHelper().find(Queue.NAME, exchangeName + "-direct" , exchanges);
        Map<String, Object> topicExchange = getRestTestHelper().find(Queue.NAME, exchangeName + "-topic" , exchanges);
        Map<String, Object> headersExchange = getRestTestHelper().find(Queue.NAME, exchangeName + "-headers" , exchanges);
        Map<String, Object> fanoutExchange = getRestTestHelper().find(Queue.NAME, exchangeName + "-fanout" , exchanges);

        Asserts.assertDurableExchange(exchangeName + "-direct", "direct", directExchange);
        Asserts.assertDurableExchange(exchangeName + "-topic", "topic", topicExchange);
        Asserts.assertDurableExchange(exchangeName + "-headers", "headers", headersExchange);
        Asserts.assertDurableExchange(exchangeName + "-fanout", "fanout", fanoutExchange);

        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, directExchange.get(Queue.DURABLE));
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, topicExchange.get(Queue.DURABLE));
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, headersExchange.get(Queue.DURABLE));
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, fanoutExchange.get(Queue.DURABLE));

    }

    public void testPutCreateLVQWithoutKey() throws Exception
    {
        String queueName = getTestQueueName()+ "-lvq";
        createQueue(queueName, "lvq", null);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        Map<String, Object> lvqQueue = getRestTestHelper().find(Queue.NAME, queueName  , queues);

        Asserts.assertQueue(queueName , "lvq", lvqQueue);
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, lvqQueue.get(Queue.DURABLE));
        assertEquals("Unexpected lvq key attribute", LastValueQueue.DEFAULT_LVQ_KEY, lvqQueue.get(LastValueQueue.LVQ_KEY));
    }

    public void testPutCreateSortedQueueWithoutKey() throws Exception
    {
        String queueName = getTestQueueName() + "-sorted";
        int responseCode = tryCreateQueue(queueName, "sorted", null);
        assertEquals("Unexpected response code", SC_UNPROCESSABLE_ENTITY, responseCode);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        Map<String, Object> testQueue = getRestTestHelper().find(Queue.NAME, queueName  , queues);

        assertNull("Sorted queue without a key was created ", testQueue);
    }

    public void testPutCreatePriorityQueueWithoutKey() throws Exception
    {
        String queueName = getTestQueueName()+ "-priority";
        createQueue(queueName, "priority", null);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        Map<String, Object> priorityQueue = getRestTestHelper().find(Queue.NAME, queueName  , queues);

        Asserts.assertQueue(queueName , "priority", priorityQueue);
        assertEquals("Unexpected value of queue attribute " + Queue.DURABLE, Boolean.TRUE, priorityQueue.get(Queue.DURABLE));
        assertEquals("Unexpected number of priorities", 10, priorityQueue.get(PriorityQueue.PRIORITIES));
    }

    public void testPutCreateStandardQueueWithoutType() throws Exception
    {
        String queueName = getTestQueueName();
        createQueue(queueName, null, null);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        Map<String, Object> queue = getRestTestHelper().find(Queue.NAME, queueName  , queues);

        Asserts.assertQueue(queueName , "standard", queue);
    }

    public void testPutCreateQueueOfUnsupportedType() throws Exception
    {
        String queueName = getTestQueueName();
        int responseCode = tryCreateQueue(queueName, "unsupported", null);
        assertEquals("Unexpected response code", SC_UNPROCESSABLE_ENTITY, responseCode);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        Map<String, Object> queue = getRestTestHelper().find(Queue.NAME, queueName  , queues);

        assertNull("Queue of unsupported type was created", queue);
    }

    public void testDeleteQueue() throws Exception
    {
        String queueName = getTestQueueName();
        createQueue(queueName, null, null);

        String queueUrl = "queue/test/test/" + queueName;
        List<Map<String, Object>> queues = getRestTestHelper().getJsonAsList(queueUrl);
        assertEquals("Queue should exist", 1, queues.size());

        int statusCode = getRestTestHelper().submitRequest(queueUrl, "DELETE");
        assertEquals("Unexpected response code", 200, statusCode);

        getRestTestHelper().submitRequest(queueUrl, "GET", HttpServletResponse.SC_NOT_FOUND);
    }

    public void testDeleteQueueById() throws Exception
    {
        String queueName = getTestQueueName();
        createQueue(queueName, null, null);
        Map<String, Object> queueDetails = getRestTestHelper().getJsonAsSingletonList("queue/test/test/" + queueName);
        int statusCode = getRestTestHelper().submitRequest("queue/test/test?id=" + queueDetails.get(Queue.ID), "DELETE");
        assertEquals("Unexpected response code", 200, statusCode);
        getRestTestHelper().submitRequest("queue/test/test/" + queueName, "GET", HttpServletResponse.SC_NOT_FOUND);
    }

    public void testDeleteExchange() throws Exception
    {
        String exchangeName = getTestName();
        createExchange(exchangeName, "direct");

        int statusCode = getRestTestHelper().submitRequest("exchange/test/test/" + exchangeName, "DELETE");

        assertEquals("Unexpected response code", 200, statusCode);
        getRestTestHelper().submitRequest("exchange/test/test/" + exchangeName, "GET", HttpServletResponse.SC_NOT_FOUND);
    }

    public void testDeleteExchangeById() throws Exception
    {
        String exchangeName = getTestName();
        createExchange(exchangeName, "direct");
        Map<String, Object> echangeDetails = getRestTestHelper().getJsonAsSingletonList("exchange/test/test/" + exchangeName);

        int statusCode = getRestTestHelper().submitRequest("exchange/test/test?id=" + echangeDetails.get(Exchange.ID), "DELETE");

        assertEquals("Unexpected response code", 200, statusCode);
        getRestTestHelper().submitRequest("exchange/test/test/" + exchangeName, "GET", HttpServletResponse.SC_NOT_FOUND);
    }

    public void testPutCreateQueueWithAttributes() throws Exception
    {
        String queueName = getTestQueueName();

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Queue.ALERT_REPEAT_GAP, 1000);
        attributes.put(Queue.ALERT_THRESHOLD_MESSAGE_AGE, 3600000);
        attributes.put(Queue.ALERT_THRESHOLD_MESSAGE_SIZE, 1000000000);
        attributes.put(Queue.ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES, 800);
        attributes.put(Queue.MAXIMUM_DELIVERY_ATTEMPTS, 15);
        attributes.put(Queue.QUEUE_FLOW_CONTROL_SIZE_BYTES, 2000000000);
        attributes.put(Queue.QUEUE_FLOW_RESUME_SIZE_BYTES, 1500000000);

        createQueue(queueName + "-standard", "standard", attributes);

        Map<String, Object> sortedQueueAttributes = new HashMap<String, Object>();
        sortedQueueAttributes.putAll(attributes);
        sortedQueueAttributes.put(SortedQueue.SORT_KEY, "sortme");
        createQueue(queueName + "-sorted", "sorted", sortedQueueAttributes);

        Map<String, Object> priorityQueueAttributes = new HashMap<String, Object>();
        priorityQueueAttributes.putAll(attributes);
        priorityQueueAttributes.put(PriorityQueue.PRIORITIES, 10);
        createQueue(queueName + "-priority", "priority", priorityQueueAttributes);

        Map<String, Object> lvqQueueAttributes = new HashMap<String, Object>();
        lvqQueueAttributes.putAll(attributes);
        lvqQueueAttributes.put(LastValueQueue.LVQ_KEY, "LVQ");
        createQueue(queueName + "-lvq", "lvq", lvqQueueAttributes);

        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        Map<String, Object> standardQueue = getRestTestHelper().find(Queue.NAME, queueName + "-standard" , queues);
        Map<String, Object> sortedQueue = getRestTestHelper().find(Queue.NAME, queueName + "-sorted" , queues);
        Map<String, Object> priorityQueue = getRestTestHelper().find(Queue.NAME, queueName + "-priority" , queues);
        Map<String, Object> lvqQueue = getRestTestHelper().find(Queue.NAME, queueName + "-lvq" , queues);

        attributes.put(Queue.DURABLE, Boolean.TRUE);
        Asserts.assertQueue(queueName + "-standard", "standard", standardQueue, attributes);
        Asserts.assertQueue(queueName + "-sorted", "sorted", sortedQueue, attributes);
        Asserts.assertQueue(queueName + "-priority", "priority", priorityQueue, attributes);
        Asserts.assertQueue(queueName + "-lvq", "lvq", lvqQueue, attributes);

        assertEquals("Unexpected sorted key attribute", "sortme", sortedQueue.get(SortedQueue.SORT_KEY));
        assertEquals("Unexpected lvq key attribute", "LVQ", lvqQueue.get(LastValueQueue.LVQ_KEY));
        assertEquals("Unexpected priorities key attribute", 10, priorityQueue.get(PriorityQueue.PRIORITIES));
    }

    @SuppressWarnings("unchecked")
    public void testCreateQueueWithDLQEnabled() throws Exception
    {
        String queueName = getTestQueueName();

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(AbstractVirtualHost.CREATE_DLQ_ON_CREATION, true);

        //verify the starting state
        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        List<Map<String, Object>> exchanges = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_EXCHANGES_ATTRIBUTE);

        assertNull("queue "+ queueName + " should not have already been present", getRestTestHelper().find(Queue.NAME, queueName , queues));
        assertNull("queue "+ queueName + "_DLQ should not have already been present", getRestTestHelper().find(Queue.NAME, queueName + "_DLQ" , queues));
        assertNull("exchange should not have already been present", getRestTestHelper().find(Exchange.NAME, queueName + "_DLE" , exchanges));

        //create the queue
        createQueue(queueName, "standard", attributes);

        //verify the new queue, as well as the DLQueue and DLExchange have been created
        hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");
        queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        exchanges = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_EXCHANGES_ATTRIBUTE);

        Map<String, Object> queue = getRestTestHelper().find(Queue.NAME, queueName , queues);
        Map<String, Object> dlqQueue = getRestTestHelper().find(Queue.NAME, queueName + "_DLQ" , queues);
        Map<String, Object> dlExchange = getRestTestHelper().find(Exchange.NAME, queueName + "_DLE" , exchanges);
        assertNotNull("queue should have been present", queue);
        assertNotNull("queue should have been present", dlqQueue);
        assertNotNull("exchange should have been present", dlExchange);

        //verify that the alternate exchange is set as expected on the new queue
        Map<String, Object> queueAttributes = new HashMap<String, Object>();
        queueAttributes.put(Queue.ALTERNATE_EXCHANGE, queueName + "_DLE");

        Asserts.assertQueue(queueName, "standard", queue, queueAttributes);
        Asserts.assertQueue(queueName, "standard", queue, null);
    }

    public void testObjectsWithSlashes() throws Exception
    {
        String queueName = "testQueue/with/slashes";
        String queueNameEncoded = URLEncoder.encode(queueName, "UTF-8");
        String queueNameDoubleEncoded = URLEncoder.encode(queueNameEncoded, "UTF-8");
        String queueUrl = "queue/test/test/" + queueNameDoubleEncoded;

        // Test creation
        createQueue(queueNameDoubleEncoded, "standard", null);
        Map<String, Object> hostDetails = getRestTestHelper().getJsonAsSingletonList("virtualhost/test");
        List<Map<String, Object>> queues = (List<Map<String, Object>>) hostDetails.get(VirtualHostRestTest.VIRTUALHOST_QUEUES_ATTRIBUTE);
        Map<String, Object> queue = getRestTestHelper().find(Queue.NAME, queueName , queues);
        Asserts.assertQueue(queueName, "standard", queue);

        // Test deletion
        int statusCode = getRestTestHelper().submitRequest(queueUrl, "DELETE");
        assertEquals("Unexpected response code", 200, statusCode);
        getRestTestHelper().submitRequest(queueUrl, "GET", HttpServletResponse.SC_NOT_FOUND);
    }

    private void createExchange(String exchangeName, String exchangeType) throws IOException
    {
        Map<String, Object> queueData = new HashMap<String, Object>();
        queueData.put(Exchange.NAME, exchangeName);
        queueData.put(Exchange.DURABLE, Boolean.TRUE);
        queueData.put(Exchange.TYPE, exchangeType);

        int statusCode = getRestTestHelper().submitRequest("exchange/test/test/" + exchangeName, "PUT", queueData);
        assertEquals("Unexpected response code", 201, statusCode);
    }

    private void createQueue(String queueName, String queueType, Map<String, Object> attributes) throws Exception
    {
        int responseCode = tryCreateQueue(queueName, queueType, attributes);
        assertEquals("Unexpected response code", 201, responseCode);
    }

    private int tryCreateQueue(String queueName, String queueType, Map<String, Object> attributes) throws Exception
    {
        Map<String, Object> queueData = new HashMap<String, Object>();
        queueData.put(Queue.NAME, queueName);
        queueData.put(Queue.DURABLE, Boolean.TRUE);
        if (queueType != null)
        {
            queueData.put(Queue.TYPE, queueType);
        }
        if (attributes != null)
        {
            queueData.putAll(attributes);
        }

        return getRestTestHelper().submitRequest("queue/test/test/" + queueName, "PUT", queueData);
    }

    private Map<String, Object> submitVirtualHost(boolean useParentURI, String method, int statusCode) throws IOException
    {
        String hostName = getTestName();
        String type = getTestProfileVirtualHostNodeType();
        if (JsonVirtualHostNodeImpl.VIRTUAL_HOST_NODE_TYPE.equals(type))
        {
            type = DerbyVirtualHostImpl.VIRTUAL_HOST_TYPE;
        }
        Map<String, Object> virtualhostData = new HashMap<>();
        virtualhostData.put(VirtualHost.NAME, hostName);
        virtualhostData.put(VirtualHost.TYPE, type);

        String url = "virtualhost/" + EMPTY_VIRTUALHOSTNODE_NAME;
        if (!useParentURI)
        {
            url += "/" + hostName;
        }

        Map<String,List<String>> headers = new HashMap<>();
        int responseCode = getRestTestHelper().submitRequest(url, method, virtualhostData, headers );
        Assert.assertEquals("Unexpected response code from " + method + " " + url, statusCode, responseCode);
        if (statusCode == 201)
        {
            List<String> location = headers.get("Location");
            assertTrue("Location is not returned by REST create request", location != null && location.size() == 1);
            String expectedLocation = getRestTestHelper().getManagementURL() + RestTestHelper.API_BASE + url;
            if (useParentURI)
            {
                expectedLocation += "/" + hostName;
            }
            assertEquals("Unexpected location", expectedLocation, location.get(0));
        }
        return virtualhostData;
    }

    private void assertNewVirtualHost(Map<String, Object> hostDetails)
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> statistics = (Map<String, Object>) hostDetails.get(Asserts.STATISTICS_ATTRIBUTE);
        assertEquals("Unexpected number of exchanges in statistics", EXPECTED_EXCHANGES.length,
                statistics.get("exchangeCount"));
        assertEquals("Unexpected number of queues in statistics", 0, statistics.get("queueCount"));
        assertEquals("Unexpected number of connections in statistics", 0, statistics.get("connectionCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exchanges = (List<Map<String, Object>>) hostDetails.get(VIRTUALHOST_EXCHANGES_ATTRIBUTE);
        assertEquals("Unexpected number of exchanges", EXPECTED_EXCHANGES.length, exchanges.size());
        RestTestHelper restTestHelper = getRestTestHelper();
        Asserts.assertDurableExchange("amq.fanout", "fanout", restTestHelper.find(Exchange.NAME, "amq.fanout", exchanges));
        Asserts.assertDurableExchange("amq.topic", "topic", restTestHelper.find(Exchange.NAME, "amq.topic", exchanges));
        Asserts.assertDurableExchange("amq.direct", "direct", restTestHelper.find(Exchange.NAME, "amq.direct", exchanges));
        Asserts.assertDurableExchange("amq.match", "headers", restTestHelper.find(Exchange.NAME, "amq.match", exchanges));

        assertNull("Unexpected queues", hostDetails.get(VIRTUALHOST_QUEUES_ATTRIBUTE));
    }

    private void assertActualAndDesireStates(final String restUrl,
                                             final String expectedDesiredState,
                                             final String expectedActualState) throws IOException
    {
        Map<String, Object> virtualhost = getRestTestHelper().getJsonAsSingletonList(restUrl);
        Asserts.assertActualAndDesiredState(expectedDesiredState, expectedActualState, virtualhost);
    }

    private void assertQueueDepth(String restQueueUrl, String message, int expectedDepth) throws IOException
    {
        Map<String, Object> queueDetails = getRestTestHelper().getJsonAsSingletonList(restQueueUrl);
        assertNotNull(queueDetails);
        Map<String, Object> statistics = (Map<String, Object>) queueDetails.get(Asserts.STATISTICS_ATTRIBUTE);
        assertNotNull(statistics);

        assertEquals(message, expectedDepth, statistics.get("queueDepthMessages"));
    }

}
