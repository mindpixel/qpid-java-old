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
package org.apache.qpid.ra;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Message;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.test.utils.QpidBrokerTestCase;

public class QpidRAConnectionTest extends QpidBrokerTestCase
{
    private static final Logger _logger = LoggerFactory.getLogger(QpidRAConnectionTest.class);

    private static final String URL_TEMPLATE = "amqp://guest:guest@client/test?brokerlist='tcp://localhost:%d?sasl_mechs='PLAIN%%25252520CRAM-MD5''";

    public void testSessionCommitOnClosedConnectionThrowsException() throws Exception
    {
        QpidResourceAdapter ra = new QpidResourceAdapter();
        QpidRAManagedConnectionFactory mcf = new QpidRAManagedConnectionFactory();
        mcf.setConnectionURL(getBrokerUrl());
        mcf.setResourceAdapter(ra);
        ConnectionFactory cf = new QpidRAConnectionFactoryImpl(mcf, null);
        Connection c = cf.createConnection();
        Session s = c.createSession(true, Session.SESSION_TRANSACTED);
        c.close();

        try
        {
            s.commit();
            fail("Exception should be thrown");
        }
        catch(Exception e)
        {
            _logger.error("Commit threw exception", e);
            assertTrue(e instanceof javax.jms.IllegalStateException);
        }

    }

    public void testMessageAck() throws Exception
    {
        QpidResourceAdapter ra = new QpidResourceAdapter();
        QpidRAManagedConnectionFactory mcf = new QpidRAManagedConnectionFactory();
        mcf.setConnectionURL(getBrokerUrl());
        mcf.setResourceAdapter(ra);
        ConnectionFactory cf = new QpidRAConnectionFactoryImpl(mcf, null);
        Connection c = cf.createConnection();
        Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Message m = s.createTextMessage();

        try
        {
            m.acknowledge();
        }
        catch(Exception e)
        {
            fail("Acknowledge should not throw an exception");
        }
        finally
        {
            s.close();
            c.close();
        }
    }

    private String getBrokerUrl()
    {
        return String.format(URL_TEMPLATE, getDefaultBroker().getAmqpPort());
    }
}
