/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.qpid.server.security.acl;

import javax.jms.Connection;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;

import org.apache.qpid.QpidException;
import org.apache.qpid.AMQException;
import org.apache.qpid.client.AMQSession;
import org.apache.qpid.configuration.ClientProperties;
import org.apache.qpid.protocol.ErrorCodes;

/**
 * ACL version 2/3 file testing to verify that ACL entries control queue creation with specific properties.
 *
 * Tests have their own ACL files that setup specific permissions, and then try to create queues with every possible combination
 * of properties to show that rule matching works correctly. For example, a rule that specified {@code autodelete="true"} for
 * queues with {@code name="temp.true.*"} as well should not affect queues that have names that do not match, or queues that
 * are not autodelete, or both. Also checks that ACL entries only affect the specified users and virtual hosts.
 */
public class ExhaustiveACLTest extends AbstractACLTestCase
{

    /**
     * Creates a queue.
     *
     * Connects to the broker as a particular user and create the named queue on a virtual host, with the provided
     * parameters. Uses a new {@link Connection} and {@link Session} and closes them afterwards.
     */
	private void createQueue(String vhost, String user, String name, boolean autoDelete, boolean durable) throws Exception
	{
		Connection conn = getConnection(vhost, user, "guest");
		Session sess = conn.createSession(true, Session.SESSION_TRANSACTED);
		conn.start();
		((AMQSession<?, ?>) sess).createQueue(name, autoDelete, durable, false);
		sess.commit();
		conn.close();
	}

	/**
	 * Calls {@link #createQueue(String, String, String, boolean, boolean)} with the provided parameters and checks that
	 * no exceptions were thrown.
	 */
	private void createQueueSuccess(String vhost, String user, String name, boolean autoDelete, boolean durable) throws Exception
	{
		try
		{
			createQueue(vhost, user, name, autoDelete, durable);
		}
		catch (QpidException e)
		{
			fail(String.format("Create queue should have worked for \"%s\" for user %s@%s, autoDelete=%s, durable=%s",
                               name, user, vhost, Boolean.toString(autoDelete), Boolean.toString(durable)));
		}
	}

	/**
	 * Calls {@link #createQueue(String, String, String, boolean, boolean)} with the provided parameters and checks that
	 * the exception thrown was an {@link ErrorCodes#ACCESS_REFUSED} or 403 error code.
	 */
	private void createQueueFailure(String vhost, String user, String name, boolean autoDelete, boolean durable) throws Exception
	{
		try
		{
			createQueue(vhost, user, name, autoDelete, durable);
			fail(String.format("Create queue should have failed for \"%s\" for user %s@%s, autoDelete=%s, durable=%s",
                               name, user, vhost, Boolean.toString(autoDelete), Boolean.toString(durable)));
		}
		catch (AMQException e)
		{
			assertEquals("Should be an ACCESS_REFUSED error", 403, e.getErrorCode());
		}
	}

    public void setUpAuthoriseCreateQueueAutodelete() throws Exception
    {
        writeACLFile("acl allow client access virtualhost",
					 "acl allow server access virtualhost",
					 "acl allow client create queue name=\"temp.true.*\" autodelete=true",
					 "acl allow client create queue name=\"temp.false.*\" autodelete=false",
					 "acl deny client create queue",
					 "acl allow client delete queue",
					 "acl deny all create queue"
            );
    }

    /**
     * Test creation of temporary queues, with the autodelete property set to true.
     */
    public void testAuthoriseCreateQueueAutodelete() throws Exception
	{
		createQueueSuccess("test", "client", "temp.true.00", true, false);
		createQueueSuccess("test", "client", "temp.true.01", true, false);
		createQueueSuccess("test", "client", "temp.true.02", true, true);
		createQueueSuccess("test", "client", "temp.false.03", false, false);
		createQueueSuccess("test", "client", "temp.false.04", false, false);
		createQueueSuccess("test", "client", "temp.false.05", false, true);
		createQueueFailure("test", "client", "temp.true.06", false, false);
		createQueueFailure("test", "client", "temp.false.07", true, false);
		createQueueFailure("test", "server", "temp.true.08", true, false);
		createQueueFailure("test", "client", "temp.other.09", false, false);
    }


    public void setUpAuthoriseQueueAutodeleteDeleteByOther() throws Exception
    {
        writeACLFile("acl allow client access virtualhost",
                     "acl allow server access virtualhost",
                     "acl allow client create queue name=\"temp.true.*\" autodelete=true",
                     "acl allow server consume queue name=\"temp.true.*\"",
                     "acl allow server bind exchange",
                     "acl deny client create queue",
                     "acl allow client delete queue",
                     "acl deny all create queue"
                    );
    }
    /**
     * Test creation of temporary queues, with the autodelete property and then autodeleted.
     */
    public void testAuthoriseQueueAutodeleteDeleteByOther() throws Exception
    {
        // stop the consumer trying to redeclare the queue
        setTestSystemProperty(ClientProperties.QPID_DECLARE_QUEUES_PROP_NAME, "false");

        // create a temp queue as use client
        createQueueSuccess("test", "client", "temp.true.00", true, false);

        // consume from temp queue as user server
        Connection conn = getConnection("test", "server", "guest");
        Session sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        conn.start();
        Queue queue = sess.createQueue("temp.true.00");
        MessageConsumer cons = sess.createConsumer(queue);
        cons.close();
        sess.close();

        sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        conn.start();

        // test if the queue is bound to the default exchange
        assertFalse(((AMQSession)sess).isQueueBound("","temp.true.00","temp.true.00",null));
        sess.close();

        conn.close();


    }

    public void setUpAuthoriseCreateQueue() throws Exception
    {
        writeACLFile("acl allow client access virtualhost",
                     "acl allow server access virtualhost",
                     "acl allow client create queue name=\"create.*\""
            );
    }

    /**
     * Tests creation of named queues.
     *
     * If a named queue is specified
     */
    public void testAuthoriseCreateQueue() throws Exception
    {
        createQueueSuccess("test", "client", "create.00", true, true);
        createQueueSuccess("test", "client", "create.01", true, false);
        createQueueSuccess("test", "client", "create.02", false, true);
        createQueueSuccess("test", "client", "create.03", true, false);
        createQueueFailure("test", "server", "create.04", true, true);
        createQueueFailure("test", "server", "create.05", true, false);
        createQueueFailure("test", "server", "create.06", false, true);
        createQueueFailure("test", "server", "create.07", true, false);
    }

    public void setUpAuthoriseCreateQueueBoth() throws Exception
    {
        writeACLFile("acl allow all access virtualhost",
                     "acl allow client create queue name=\"create.*\"",
                     "acl allow all create queue temporary=true"
            );
    }

    /**
     * Tests creation of named queues.
     *
     * If a named queue is specified
     */
    public void testAuthoriseCreateQueueBoth() throws Exception
    {
        createQueueSuccess("test", "client", "create.00", true, false);
        createQueueSuccess("test", "client", "create.01", false, false);
        createQueueFailure("test", "server", "create.02", false, false);
        createQueueFailure("test", "guest", "create.03", false, false);
        createQueueSuccess("test", "client", "tmp.00", true, false);
        createQueueSuccess("test", "server", "tmp.01", true, false);
        createQueueSuccess("test", "guest", "tmp.02", true, false);
    }
}
