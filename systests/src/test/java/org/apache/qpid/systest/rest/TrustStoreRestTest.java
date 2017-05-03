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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.qpid.server.model.AbstractConfiguredObject;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.TrustStore;
import org.apache.qpid.server.security.FileTrustStore;
import org.apache.qpid.test.utils.TestBrokerConfiguration;
import org.apache.qpid.test.utils.TestSSLConstants;
import org.apache.qpid.util.DataUrlUtils;
import org.apache.qpid.util.FileUtils;

public class TrustStoreRestTest extends QpidRestTestCase
{

    public void testGet() throws Exception
    {
        //verify existence of the default trust store used by the systests
        List<Map<String, Object>> trustStores = assertNumberOfTrustStores(1);

        Map<String, Object> truststore = trustStores.get(0);
        assertEquals("default systests trust store is missing",
                TestBrokerConfiguration.ENTRY_NAME_SSL_TRUSTSTORE, truststore.get(TrustStore.NAME));
        assertEquals("unexpected store URL", ConfiguredObject.OVER_SIZED_ATTRIBUTE_ALTERNATIVE_TEXT, truststore.get(FileTrustStore.STORE_URL));
        assertEquals("unexpected (dummy) password of default systests trust store",
                     AbstractConfiguredObject.SECURED_STRING_VALUE, truststore.get(FileTrustStore.PASSWORD));
        assertEquals("unexpected type of default systests trust store",
                java.security.KeyStore.getDefaultType(), truststore.get(FileTrustStore.TRUST_STORE_TYPE));
        assertEquals("unexpected peersOnly value", false, truststore.get(FileTrustStore.PEERS_ONLY));
    }

    public void testCreate() throws Exception
    {
        String name = getTestName();

        assertNumberOfTrustStores(1);
        createTrustStore(name, true, TestSSLConstants.TRUSTSTORE, TestSSLConstants.TRUSTSTORE_PASSWORD);
        assertNumberOfTrustStores(2);

        List<Map<String, Object>> trustStores = getRestTestHelper().getJsonAsList("truststore/" + name);
        assertNotNull("details cannot be null", trustStores);

        Map<String, Object> truststore = trustStores.get(0);
        assertEquals("unexpected trust store name", name, truststore.get(TrustStore.NAME));
        assertEquals("unexpected store URL", TestSSLConstants.TRUSTSTORE, truststore.get(FileTrustStore.STORE_URL));
        assertEquals("unexpected password value", AbstractConfiguredObject.SECURED_STRING_VALUE, truststore.get(FileTrustStore.PASSWORD));
        assertEquals("unexpected type", java.security.KeyStore.getDefaultType(), truststore.get(FileTrustStore.TRUST_STORE_TYPE));
        assertEquals("unexpected peersOnly value", true, truststore.get(FileTrustStore.PEERS_ONLY));
    }

    public void testCreateUsingDataUrl() throws Exception
    {
        String name = getTestName();
        byte[] trustStoreAsBytes = FileUtils.readFileAsBytes(TestSSLConstants.TRUSTSTORE);
        String dataUrlForTruststore = DataUrlUtils.getDataUrlForBytes(trustStoreAsBytes);

        assertNumberOfTrustStores(1);

        createTrustStore(name, false, dataUrlForTruststore, TestSSLConstants.TRUSTSTORE_PASSWORD);

        assertNumberOfTrustStores(2);

        List<Map<String, Object>> trustStores = getRestTestHelper().getJsonAsList("truststore/" + name);
        assertNotNull("details cannot be null", trustStores);

        Map<String, Object> truststore = trustStores.get(0);
        assertEquals("nexpected trust store name", name, truststore.get(TrustStore.NAME));
        assertEquals("unexpected store URL value",  ConfiguredObject.OVER_SIZED_ATTRIBUTE_ALTERNATIVE_TEXT, truststore.get(FileTrustStore.STORE_URL));
        assertEquals("unexpected password value", AbstractConfiguredObject.SECURED_STRING_VALUE, truststore.get(FileTrustStore.PASSWORD));
        assertEquals("unexpected type of trust store", java.security.KeyStore.getDefaultType(), truststore.get(FileTrustStore.TRUST_STORE_TYPE));
        assertEquals("unexpected peersOnly value", false, truststore.get(FileTrustStore.PEERS_ONLY));
    }

    public void testDelete() throws Exception
    {
        String name = getTestName();

        assertNumberOfTrustStores(1);
        createTrustStore(name, false, TestSSLConstants.TRUSTSTORE, TestSSLConstants.TRUSTSTORE_PASSWORD);
        assertNumberOfTrustStores(2);

        getRestTestHelper().submitRequest("truststore/" + name, "DELETE", HttpServletResponse.SC_OK);
        getRestTestHelper().submitRequest("truststore/" + name, "GET", HttpServletResponse.SC_NOT_FOUND);

        //check only the default systests trust store remains
        List<Map<String, Object>> trustStores = assertNumberOfTrustStores(1);
        Map<String, Object> truststore = trustStores.get(0);
        assertEquals("unexpected name", TestBrokerConfiguration.ENTRY_NAME_SSL_TRUSTSTORE, truststore.get(TrustStore.NAME));
        assertEquals("unexpected store URL value",  ConfiguredObject.OVER_SIZED_ATTRIBUTE_ALTERNATIVE_TEXT, truststore.get(FileTrustStore.STORE_URL));
        assertEquals("unexpected password value", AbstractConfiguredObject.SECURED_STRING_VALUE, truststore.get(FileTrustStore.PASSWORD));
        assertEquals("unexpected type of  trust store", java.security.KeyStore.getDefaultType(), truststore.get(FileTrustStore.TRUST_STORE_TYPE));
        assertEquals("unexpected peersOnly value", false, truststore.get(FileTrustStore.PEERS_ONLY));
    }


    public void testUpdate() throws Exception
    {
        String name = getTestName();

        assertNumberOfTrustStores(1);
        createTrustStore(name, false, TestSSLConstants.TRUSTSTORE, TestSSLConstants.TRUSTSTORE_PASSWORD);
        assertNumberOfTrustStores(2);

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(TrustStore.NAME, name);
        attributes.put(FileTrustStore.STORE_URL, TestSSLConstants.TRUSTSTORE);

        getRestTestHelper().submitRequest("truststore/" + name , "PUT", attributes, HttpServletResponse.SC_OK);

        List<Map<String, Object>> trustStore = getRestTestHelper().getJsonAsList("truststore/" + name);
        assertNotNull("details should not be null", trustStore);

        Map<String, Object> truststore = trustStore.get(0);
        assertEquals("unexpected name", name, truststore.get(TrustStore.NAME));
        assertEquals("unexpected path to trust store",  TestSSLConstants.TRUSTSTORE, truststore.get(FileTrustStore.STORE_URL));
        assertEquals("unexpected password", AbstractConfiguredObject.SECURED_STRING_VALUE, truststore.get(FileTrustStore.PASSWORD));
        assertEquals("unexpected type", java.security.KeyStore.getDefaultType(), truststore.get(FileTrustStore.TRUST_STORE_TYPE));
        assertEquals("unexpected peersOnly value", false, truststore.get(FileTrustStore.PEERS_ONLY));
    }

    private List<Map<String, Object>> assertNumberOfTrustStores(int numberOfTrustStores) throws Exception
    {
        List<Map<String, Object>> trustStores = getRestTestHelper().getJsonAsList("truststore");
        assertNotNull("trust stores should not be null", trustStores);
        assertEquals("Unexpected number of trust stores", numberOfTrustStores, trustStores.size());

        return trustStores;
    }

    private void createTrustStore(String name, boolean peersOnly, final String truststorePath, final String truststorePassword) throws Exception
    {
        Map<String, Object> trustStoreAttributes = new HashMap<String, Object>();
        trustStoreAttributes.put(TrustStore.NAME, name);
        //deliberately using the client trust store to differentiate from the one we are already for broker
        trustStoreAttributes.put(FileTrustStore.STORE_URL, truststorePath);
        trustStoreAttributes.put(FileTrustStore.PASSWORD, truststorePassword);
        trustStoreAttributes.put(FileTrustStore.PEERS_ONLY, peersOnly);

        getRestTestHelper().submitRequest("truststore/" + name, "PUT", trustStoreAttributes, HttpServletResponse.SC_CREATED);
    }

}
