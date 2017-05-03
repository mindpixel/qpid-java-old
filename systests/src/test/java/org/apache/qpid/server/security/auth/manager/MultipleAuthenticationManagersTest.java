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
 *
 *
 */
package org.apache.qpid.server.security.auth.manager;

import static org.apache.qpid.test.utils.TestSSLConstants.KEYSTORE;
import static org.apache.qpid.test.utils.TestSSLConstants.KEYSTORE_PASSWORD;
import static org.apache.qpid.test.utils.TestSSLConstants.TRUSTSTORE;
import static org.apache.qpid.test.utils.TestSSLConstants.TRUSTSTORE_PASSWORD;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.JMSException;

import org.apache.qpid.QpidException;
import org.apache.qpid.client.AMQConnection;
import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.DefaultVirtualHostAlias;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.model.VirtualHostAlias;
import org.apache.qpid.server.model.VirtualHostNameAlias;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;

public class MultipleAuthenticationManagersTest extends QpidBrokerTestCase
{
    @Override
    protected void setUp() throws Exception
    {
        TestBrokerConfiguration config = getDefaultBrokerConfiguration();

        Map<String, Object> externalAuthProviderAttributes = new HashMap<>();
        externalAuthProviderAttributes.put(AuthenticationProvider.TYPE, AnonymousAuthenticationManager.PROVIDER_TYPE);
        externalAuthProviderAttributes.put(AuthenticationProvider.NAME, TestBrokerConfiguration.ENTRY_NAME_ANONYMOUS_PROVIDER);
        config.addObjectConfiguration(AuthenticationProvider.class, externalAuthProviderAttributes);

        Map<String, Object> sslPortAttributes = new HashMap<>();
        sslPortAttributes.put(Port.TRANSPORTS, Collections.singleton(Transport.SSL));
        sslPortAttributes.put(Port.PORT, DEFAULT_SSL_PORT);
        sslPortAttributes.put(Port.NAME, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT);
        sslPortAttributes.put(Port.KEY_STORE, TestBrokerConfiguration.ENTRY_NAME_SSL_KEYSTORE);
        sslPortAttributes.put(Port.TRUST_STORES, Collections.singleton(TestBrokerConfiguration.ENTRY_NAME_SSL_TRUSTSTORE));
        sslPortAttributes.put(Port.AUTHENTICATION_PROVIDER, TestBrokerConfiguration.ENTRY_NAME_ANONYMOUS_PROVIDER);
        sslPortAttributes.put(Port.PROTOCOLS, System.getProperty(TEST_AMQP_PORT_PROTOCOLS_PROPERTY));

        config.addObjectConfiguration(Port.class, sslPortAttributes);

        Map<String, Object> aliasAttributes = new HashMap<>();
        aliasAttributes.put(VirtualHostAlias.NAME, "defaultAlias");
        aliasAttributes.put(VirtualHostAlias.TYPE, DefaultVirtualHostAlias.TYPE_NAME);
        config.addObjectConfiguration(Port.class, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT, VirtualHostAlias.class, aliasAttributes);

        aliasAttributes = new HashMap<>();
        aliasAttributes.put(VirtualHostAlias.NAME, "nameAlias");
        aliasAttributes.put(VirtualHostAlias.TYPE, VirtualHostNameAlias.TYPE_NAME);
        getDefaultBrokerConfiguration().addObjectConfiguration(Port.class, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT, VirtualHostAlias.class, aliasAttributes);


        // set the ssl system properties
        setSystemProperty("javax.net.ssl.keyStore", KEYSTORE);
        setSystemProperty("javax.net.ssl.keyStorePassword", KEYSTORE_PASSWORD);
        setSystemProperty("javax.net.ssl.trustStore", TRUSTSTORE);
        setSystemProperty("javax.net.ssl.trustStorePassword", TRUSTSTORE_PASSWORD);
        setSystemProperty("javax.net.debug", "ssl");
        super.setUp();
    }

    private Connection getAnonymousSSLConnection() throws Exception
    {
        String url = "amqp://:@test/?brokerlist='tcp://localhost:%s?ssl='true''";

        url = String.format(url, getDefaultBroker().getAmqpTlsPort());

        return new AMQConnection(url);

    }

    private Connection getAnonymousConnection() throws Exception
    {
        String url = "amqp://:@test/?brokerlist='tcp://localhost:%s'";

        url = String.format(url, getDefaultBroker().getAmqpPort());

        return new AMQConnection(url);

    }


    public void testMultipleAuthenticationManagers() throws Exception
    {
        try
        {
            Connection conn = getConnection();
            assertNotNull("Connection unexpectedly null", conn);
        }
        catch(JMSException e)
        {
            fail("Should be able to create a connection with credentials to the standard port. " + e.getMessage());
        }

        try
        {
            Connection conn = getAnonymousSSLConnection();
            assertNotNull("Connection unexpectedly null", conn);
        }
        catch(JMSException e)
        {
            fail("Should be able to create a anonymous connection to the SSL port. " + e.getMessage());
        }

        try
        {
            Connection conn = getAnonymousConnection();
            fail("Should not be able to create anonymous connection to the standard port");
        }
        catch(QpidException e)
        {
            // pass
        }

    }
}
