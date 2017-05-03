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
package org.apache.qpid.client.ssl;

import static org.apache.qpid.test.utils.TestSSLConstants.KEYSTORE;
import static org.apache.qpid.test.utils.TestSSLConstants.KEYSTORE_PASSWORD;
import static org.apache.qpid.test.utils.TestSSLConstants.TRUSTSTORE;
import static org.apache.qpid.test.utils.TestSSLConstants.TRUSTSTORE_PASSWORD;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.security.Key;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.xml.bind.DatatypeConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.client.AMQConnectionURL;
import org.apache.qpid.client.AMQTestConnection_0_10;
import org.apache.qpid.jms.ConnectionURL;
import org.apache.qpid.server.model.DefaultVirtualHostAlias;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.model.VirtualHostAlias;
import org.apache.qpid.server.model.VirtualHostNameAlias;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;
import org.apache.qpid.test.utils.TestFileUtils;
import org.apache.qpid.test.utils.TestSSLConstants;

public class SSLTest extends QpidBrokerTestCase
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SSLTest.class);

    @Override
    protected void setUp() throws Exception
    {
        setSystemProperty("javax.net.debug", "ssl");

        setSslStoreSystemProperties();

        super.setUp();
    }

    @Override
    public void startDefaultBroker() throws Exception
    {
        // noop; we do not need to start broker in setUp
    }

    public void testCreateSSLConnectionUsingConnectionURLParams() throws Exception
    {
        if (shouldPerformTest())
        {
            clearSslStoreSystemProperties();

            //Start the broker (NEEDing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, true, false, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s" +
            "?ssl='true'" +
            "&key_store='%s'&key_store_password='%s'" +
            "&trust_store='%s'&trust_store_password='%s'" +
            "'";

            url = String.format(url, getDefaultBroker().getAmqpTlsPort(),
                    KEYSTORE,KEYSTORE_PASSWORD,TRUSTSTORE,TRUSTSTORE_PASSWORD);

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);
        }
    }

    public void testCreateSSLConnectionWithCertificateTrust() throws Exception
    {
        if (shouldPerformTest())
        {
            clearSslStoreSystemProperties();

            //Start the broker (NEEDing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, false, false, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s" +
                         "?ssl='true'" +
                         "&trusted_certs_path='%s'" +
                         "'";
            File trustCertFile = extractCertFileFromTestTrustStore();

            url = String.format(url, getDefaultBroker().getAmqpTlsPort(),
                                trustCertFile.getCanonicalPath());

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);
        }
    }

    public void testSSLConnectionToPlainPortRejected() throws Exception
    {
        if (shouldPerformTest())
        {
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s" +
                         "?ssl='true''";

            url = String.format(url, getDefaultBroker().getAmqpPort());

            try
            {
                getConnection(new AMQConnectionURL(url));
                fail("Exception not thrown");
            }
            catch (JMSException e)
            {
                // PASS
                assertTrue("Unexpected exception message : " + e.getMessage(),
                           e.getMessage().contains("Unrecognized SSL message, plaintext connection?"));
            }
        }
    }

    public void testHostVerificationIsOnByDefault() throws Exception
    {
        if (shouldPerformTest())
        {
            clearSslStoreSystemProperties();

            //Start the broker (NEEDing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, true, false, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://127.0.0.1:%s" +
                    "?ssl='true'" +
                    "&key_store='%s'&key_store_password='%s'" +
                    "&trust_store='%s'&trust_store_password='%s'" +
                    "'";

            url = String.format(url, getDefaultBroker().getAmqpTlsPort(),
                    KEYSTORE,KEYSTORE_PASSWORD,TRUSTSTORE,TRUSTSTORE_PASSWORD);

            try
            {
                getConnection(new AMQConnectionURL(url));
                fail("Exception not thrown");
            }
            catch(JMSException e)
            {
                assertTrue("Unexpected exception message", e.getMessage().contains("SSL hostname verification failed"));
            }

            url = "amqp://guest:guest@test/?brokerlist='tcp://127.0.0.1:%s" +
                    "?ssl='true'&ssl_verify_hostname='false'" +
                    "&key_store='%s'&key_store_password='%s'" +
                    "&trust_store='%s'&trust_store_password='%s'" +
                    "'";
            url = String.format(url, getDefaultBroker().getAmqpTlsPort(),
                    KEYSTORE,KEYSTORE_PASSWORD,TRUSTSTORE,TRUSTSTORE_PASSWORD);

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);
        }
    }

    /**
     * Create an SSL connection using the SSL system properties for the trust and key store, but using
     * the {@link ConnectionURL} ssl='true' option to indicate use of SSL at a Connection level,
     * without specifying anything at the {@link ConnectionURL#OPTIONS_BROKERLIST} level.
     */
    public void testSslConnectionOption() throws Exception
    {
        if (shouldPerformTest())
        {
            //Start the broker (NEEDing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, true, false, false);
            super.startDefaultBroker();

            //Create URL enabling SSL at the connection rather than brokerlist level
            String url = "amqp://guest:guest@test/?ssl='true'&brokerlist='tcp://localhost:%s'";
            url = String.format(url, getDefaultBroker().getAmqpTlsPort());

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);
        }
    }

    /**
     * Create an SSL connection using the SSL system properties for the trust and key store, but using
     * the {@link ConnectionURL} ssl='true' option to indicate use of SSL at a Connection level,
     * overriding the false setting at the {@link ConnectionURL#OPTIONS_BROKERLIST} level.
     */
    public void testSslConnectionOptionOverridesBrokerlistOption() throws Exception
    {
        if (shouldPerformTest())
        {
            //Start the broker (NEEDing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, true, false, false);
            super.startDefaultBroker();

            //Create URL enabling SSL at the connection, overriding the false at the brokerlist level
            String url = "amqp://guest:guest@test/?ssl='true'&brokerlist='tcp://localhost:%s?ssl='false''";
            url = String.format(url, getDefaultBroker().getAmqpTlsPort());

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);
        }
    }

    public void testCreateSSLConnectionUsingSystemProperties() throws Exception
    {
        if (shouldPerformTest())
        {
            //Start the broker (NEEDing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, true, false, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s?ssl='true''";

            url = String.format(url, getDefaultBroker().getAmqpTlsPort());

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);
        }
    }

    public void testMultipleCertsInSingleStore() throws Exception
    {
        if (shouldPerformTest())
        {
            //Start the broker (NEEDing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, true, false, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:" +
                         getDefaultBroker().getAmqpTlsPort() +
                         "?ssl='true'&ssl_cert_alias='" + TestSSLConstants.CERT_ALIAS_APP1 + "''";

            AMQTestConnection_0_10 con = new AMQTestConnection_0_10(url);
            org.apache.qpid.transport.Connection transportCon = con.getConnection();
            String userID = transportCon.getSecurityLayer().getUserID();
            assertEquals("The correct certificate was not chosen","app1@acme.org",userID);
            con.close();

            url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:" +
                  getDefaultBroker().getAmqpTlsPort() +
                  "?ssl='true'&ssl_cert_alias='" + TestSSLConstants.CERT_ALIAS_APP2 + "''";

            con = new AMQTestConnection_0_10(url);
            transportCon = con.getConnection();
            userID = transportCon.getSecurityLayer().getUserID();
            assertEquals("The correct certificate was not chosen","app2@acme.org",userID);
            con.close();
        }
    }

    public void testVerifyHostNameWithIncorrectHostname() throws Exception
    {
        if (shouldPerformTest())
        {
            //Start the broker (WANTing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, false, true, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://127.0.0.1:" +
                         getDefaultBroker().getAmqpTlsPort() + "?ssl='true''";

            try
            {
                getConnection(new AMQConnectionURL(url));
                fail("Hostname verification failed. No exception was thrown");
            }
            catch (Exception e)
            {
                verifyExceptionCausesContains(e, "SSL hostname verification failed");
            }
        }
    }

    private void verifyExceptionCausesContains(Exception e, String expectedString)
    {
        LOGGER.debug("verifying that the following exception contains " + expectedString, e);
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        e.printStackTrace(new PrintStream(bout));
        String strace = bout.toString();
        assertTrue("Correct exception not thrown, expecting : " + expectedString + " got : " +e,
                   strace.contains(expectedString));
    }

    public void testVerifyLocalHost() throws Exception
    {
        if (shouldPerformTest())
        {
            //Start the broker (WANTing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, false, true, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:" +
                         getDefaultBroker().getAmqpTlsPort() + "?ssl='true''";

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should have been created", con);
        }
    }

    public void testVerifyLocalHostLocalDomain() throws Exception
    {
        if (shouldPerformTest())
        {
            //Start the broker (WANTing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, false, true, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost.localdomain:" +
                         getDefaultBroker().getAmqpTlsPort() + "?ssl='true''";

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should have been created", con);
        }
    }

    public void testCreateSSLConnectionUsingConnectionURLParamsTrustStoreOnly() throws Exception
    {
        if (shouldPerformTest())
        {
            clearSslStoreSystemProperties();

            //Start the broker (WANTing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, false, true, false);
            super.startDefaultBroker();


            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s" +
            "?ssl='true'" +
            "&trust_store='%s'&trust_store_password='%s'" +
            "'";

            url = String.format(url, getDefaultBroker().getAmqpTlsPort(), TRUSTSTORE, TRUSTSTORE_PASSWORD);

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);
        }
    }

    /**
     * Verifies that when the broker is configured to NEED client certificates,
     * a client which doesn't supply one fails to connect.
     */
    public void testClientCertMissingWhilstNeeding() throws Exception
    {
        missingClientCertWhileNeedingOrWantingTestImpl(true, false, false);
    }

    /**
     * Verifies that when the broker is configured to WANT client certificates,
     * a client which doesn't supply one succeeds in connecting.
     */
    public void testClientCertMissingWhilstWanting() throws Exception
    {
        missingClientCertWhileNeedingOrWantingTestImpl(false, true, true);
    }

    /**
     * Verifies that when the broker is configured to WANT and NEED client certificates
     * that a client which doesn't supply one fails to connect.
     */
    public void testClientCertMissingWhilstWantingAndNeeding() throws Exception
    {
        missingClientCertWhileNeedingOrWantingTestImpl(true, true, false);
    }

    private void missingClientCertWhileNeedingOrWantingTestImpl(boolean needClientCerts,
                            boolean wantClientCerts, boolean shouldSucceed) throws Exception
    {
        if (shouldPerformTest())
        {
            clearSslStoreSystemProperties();

            //Start the broker
            configureJavaBrokerIfNecessary(true, true, needClientCerts, wantClientCerts, false);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s" +
            "?ssl='true'&trust_store='%s'&trust_store_password='%s''";

            url = String.format(url, getDefaultBroker().getAmqpTlsPort(), TRUSTSTORE, TRUSTSTORE_PASSWORD);
            try
            {
                Connection con = getConnection(new AMQConnectionURL(url));
                if(!shouldSucceed)
                {
                    fail("Connection succeeded, expected exception was not thrown");
                }
                else
                {
                    //Use the connection to verify it works
                    con.createSession(true, Session.SESSION_TRANSACTED);
                }
            }
            catch(JMSException e)
            {
                if(shouldSucceed)
                {
                    _logger.error("Caught unexpected exception",e);
                    fail("Connection failed, unexpected exception thrown");
                }
                else
                {
                    //expected
                    verifyExceptionCausesContains(e, "Caused by: javax.net.ssl.SSLException:");
                }
            }
        }
    }

    /**
     * Test running TLS and unencrypted on the same port works and both TLS and non-TLS connections can be established
     *
     */
    public void testCreateSSLandTCPonSamePort() throws Exception
    {
        if (shouldPerformTest())
        {
            clearSslStoreSystemProperties();

            //Start the broker (NEEDing client certificate authentication)
            configureJavaBrokerIfNecessary(true, false, false, false, true);
            super.startDefaultBroker();

            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s" +
            "?ssl='true'&ssl_verify_hostname='true'" +
            "&key_store='%s'&key_store_password='%s'" +
            "&trust_store='%s'&trust_store_password='%s'" +
            "'";

            url = String.format(url, getDefaultBroker().getAmqpTlsPort(),
                    KEYSTORE,KEYSTORE_PASSWORD,TRUSTSTORE,TRUSTSTORE_PASSWORD);

            Connection con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);

            url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s'";

            url = String.format(url, getDefaultBroker().getAmqpTlsPort());

            con = getConnection(new AMQConnectionURL(url));
            assertNotNull("connection should be successful", con);
            ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);

        }
    }

    public void testCreateSSLWithCertFileAndPrivateKey() throws Exception
    {
        if (shouldPerformTest())
        {
            clearSslStoreSystemProperties();
            File[] certAndKeyFiles = extractResourcesFromTestKeyStore();
            //Start the broker (WANTing client certificate authentication)
            configureJavaBrokerIfNecessary(true, true, true, false, false);
            super.startDefaultBroker();


            String url = "amqp://guest:guest@test/?brokerlist='tcp://localhost:%s" +
                         "?ssl='true'" +
                         "&trust_store='%s'&ssl_verify_hostname='false'&trust_store_password='%s'" +
                         "&client_cert_path='%s'&client_cert_priv_key_path='%s''";

            url = String.format(url, getDefaultBroker().getAmqpTlsPort(), TRUSTSTORE, TRUSTSTORE_PASSWORD,
                                certAndKeyFiles[1].getCanonicalPath(), certAndKeyFiles[0].getCanonicalPath());

            final AMQConnectionURL connectionURL = new AMQConnectionURL(url);
            Connection con = getConnection(connectionURL);
            assertNotNull("connection should be successful", con);
            Session ssn = con.createSession(false,Session.AUTO_ACKNOWLEDGE);
            assertNotNull("create session should be successful", ssn);
        }
    }
    private boolean shouldPerformTest()
    {
        // We run the SSL tests on all profiles for the Apache Qpid Broker for Java
        if(isJavaBroker())
        {
            setTestClientSystemProperty(PROFILE_USE_SSL, "true");
        }

        return Boolean.getBoolean(PROFILE_USE_SSL);
    }

    private void configureJavaBrokerIfNecessary(boolean sslEnabled,
                                                boolean sslOnly,
                                                boolean needClientAuth,
                                                boolean wantClientAuth,
                                                boolean samePort) throws Exception
    {
        if(isJavaBroker())
        {
            Map<String, Object> sslPortAttributes = new HashMap<String, Object>();
            sslPortAttributes.put(Port.TRANSPORTS, samePort ? Arrays.asList(Transport.SSL, Transport.TCP)
                                                            : Collections.singleton(Transport.SSL));
            sslPortAttributes.put(Port.PORT, DEFAULT_SSL_PORT);
            sslPortAttributes.put(Port.AUTHENTICATION_PROVIDER, TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER);
            sslPortAttributes.put(Port.NEED_CLIENT_AUTH, needClientAuth);
            sslPortAttributes.put(Port.WANT_CLIENT_AUTH, wantClientAuth);
            sslPortAttributes.put(Port.NAME, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT);
            sslPortAttributes.put(Port.KEY_STORE, TestBrokerConfiguration.ENTRY_NAME_SSL_KEYSTORE);
            sslPortAttributes.put(Port.TRUST_STORES, Collections.singleton(TestBrokerConfiguration.ENTRY_NAME_SSL_TRUSTSTORE));
            sslPortAttributes.put(Port.PROTOCOLS, System.getProperty(TEST_AMQP_PORT_PROTOCOLS_PROPERTY));
            getDefaultBrokerConfiguration().addObjectConfiguration(Port.class, sslPortAttributes);

            Map<String, Object> aliasAttributes = new HashMap<>();
            aliasAttributes.put(VirtualHostAlias.NAME, "defaultAlias");
            aliasAttributes.put(VirtualHostAlias.TYPE, DefaultVirtualHostAlias.TYPE_NAME);
            getDefaultBrokerConfiguration().addObjectConfiguration(Port.class, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT, VirtualHostAlias.class, aliasAttributes);

            aliasAttributes = new HashMap<>();
            aliasAttributes.put(VirtualHostAlias.NAME, "nameAlias");
            aliasAttributes.put(VirtualHostAlias.TYPE, VirtualHostNameAlias.TYPE_NAME);
            getDefaultBrokerConfiguration().addObjectConfiguration(Port.class, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT, VirtualHostAlias.class, aliasAttributes);

        }
    }

    private void setSslStoreSystemProperties()
    {
        setSystemProperty("javax.net.ssl.keyStore", KEYSTORE);
        setSystemProperty("javax.net.ssl.keyStorePassword", KEYSTORE_PASSWORD);
        setSystemProperty("javax.net.ssl.trustStore", TRUSTSTORE);
        setSystemProperty("javax.net.ssl.trustStorePassword", TRUSTSTORE_PASSWORD);
    }

    private void clearSslStoreSystemProperties()
    {
        setSystemProperty("javax.net.ssl.keyStore", null);
        setSystemProperty("javax.net.ssl.keyStorePassword", null);
        setSystemProperty("javax.net.ssl.trustStore", null);
        setSystemProperty("javax.net.ssl.trustStorePassword", null);
    }

    private File[] extractResourcesFromTestKeyStore() throws Exception
    {
        java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        try(InputStream is = new FileInputStream(KEYSTORE))
        {
            ks.load(is, KEYSTORE_PASSWORD.toCharArray() );
        }


        File privateKeyFile = TestFileUtils.createTempFile(this, ".private-key.der");
        try(FileOutputStream kos = new FileOutputStream(privateKeyFile))
        {
            Key pvt = ks.getKey(TestSSLConstants.CERT_ALIAS_APP1, KEYSTORE_PASSWORD.toCharArray());
            kos.write("-----BEGIN PRIVATE KEY-----\n".getBytes());
            String base64encoded = DatatypeConverter.printBase64Binary(pvt.getEncoded());
            while(base64encoded.length() > 76)
            {
                kos.write(base64encoded.substring(0,76).getBytes());
                kos.write("\n".getBytes());
                base64encoded = base64encoded.substring(76);
            }

            kos.write(base64encoded.getBytes());
            kos.write("\n-----END PRIVATE KEY-----".getBytes());
            kos.flush();
        }

        File certificateFile = TestFileUtils.createTempFile(this, ".certificate.der");

        try(FileOutputStream cos = new FileOutputStream(certificateFile))
        {
            Certificate[] chain = ks.getCertificateChain(TestSSLConstants.CERT_ALIAS_APP1);
            for(Certificate pub : chain)
            {
                cos.write("-----BEGIN CERTIFICATE-----\n".getBytes());
                String base64encoded = DatatypeConverter.printBase64Binary(pub.getEncoded());
                while (base64encoded.length() > 76)
                {
                    cos.write(base64encoded.substring(0, 76).getBytes());
                    cos.write("\n".getBytes());
                    base64encoded = base64encoded.substring(76);
                }
                cos.write(base64encoded.getBytes());

                cos.write("\n-----END CERTIFICATE-----\n".getBytes());
            }
            cos.flush();
        }

        return new File[]{privateKeyFile,certificateFile};
    }

    private File extractCertFileFromTestTrustStore() throws Exception
    {
        java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        try(InputStream is = new FileInputStream(TRUSTSTORE))
        {
            ks.load(is, TRUSTSTORE_PASSWORD.toCharArray() );
        }



        File certificateFile = TestFileUtils.createTempFile(this, ".crt");

        try(FileOutputStream cos = new FileOutputStream(certificateFile))
        {

            for(String alias : Collections.list(ks.aliases()))
            {
                Certificate pub = ks.getCertificate(alias);
                cos.write("-----BEGIN CERTIFICATE-----\n".getBytes());
                String base64encoded = DatatypeConverter.printBase64Binary(pub.getEncoded());
                while (base64encoded.length() > 76)
                {
                    cos.write(base64encoded.substring(0, 76).getBytes());
                    cos.write("\n".getBytes());
                    base64encoded = base64encoded.substring(76);
                }
                cos.write(base64encoded.getBytes());

                cos.write("\n-----END CERTIFICATE-----\n".getBytes());
            }
            cos.flush();
        }

        return certificateFile;
    }


}
