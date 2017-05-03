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
package org.apache.qpid.server.model.port;

import java.util.Map;
import java.util.Set;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.Container;
import org.apache.qpid.server.model.ManagedAttributeField;
import org.apache.qpid.server.model.TrustStore;
import org.apache.qpid.server.security.ManagedPeerCertificateTrustStore;

abstract public class AbstractClientAuthCapablePortWithAuthProvider<X extends AbstractClientAuthCapablePortWithAuthProvider<X>> extends AbstractPortWithAuthProvider<X>
        implements ClientAuthCapablePort<X>
{
    public static final String DEFAULT_AMQP_NEED_CLIENT_AUTH = "false";
    public static final String DEFAULT_AMQP_WANT_CLIENT_AUTH = "false";

    @ManagedAttributeField
    private boolean _needClientAuth;

    @ManagedAttributeField
    private boolean _wantClientAuth;

    @ManagedAttributeField
    private TrustStore<?> _clientCertRecorder;

    public AbstractClientAuthCapablePortWithAuthProvider(final Map<String, Object> attributes,
                                                         final Container<?> broker)
    {
        super(attributes, broker);
    }

    @Override
    public boolean getNeedClientAuth()
    {
        return _needClientAuth;
    }

    @Override
    public TrustStore<?> getClientCertRecorder()
    {
        return _clientCertRecorder;
    }

    @Override
    public boolean getWantClientAuth()
    {
        return _wantClientAuth;
    }

    @Override
    public void onValidate()
    {
        super.onValidate();
        boolean useClientAuth = getNeedClientAuth() || getWantClientAuth();

        if(useClientAuth && (getTrustStores() == null || getTrustStores().isEmpty()))
        {
            throw new IllegalConfigurationException("Can't create port which requests SSL client certificates but has no trust stores configured.");
        }

        boolean useTLSTransport = isUsingTLSTransport();
        if(useClientAuth && !useTLSTransport)
        {
            throw new IllegalConfigurationException(
                    "Can't create port which requests SSL client certificates but doesn't use SSL transport.");
        }

        if(useClientAuth && getClientCertRecorder() != null)
        {
            if(!(getClientCertRecorder() instanceof ManagedPeerCertificateTrustStore))
            {
                throw new IllegalConfigurationException("Only trust stores of type " + ManagedPeerCertificateTrustStore.TYPE_NAME + " may be used as the client certificate recorder");
            }
        }
    }

    @Override
    protected void validateChange(final ConfiguredObject<?> proxyForValidation, final Set<String> changedAttributes)
    {
        super.validateChange(proxyForValidation, changedAttributes);
        ClientAuthCapablePort<?> updated = (ClientAuthCapablePort<?>)proxyForValidation;

        boolean requiresCertificate = updated.getNeedClientAuth() || updated.getWantClientAuth();

        boolean usesSsl = isUsingTLSTransport(updated.getTransports());
        if (usesSsl)
        {
            if ((updated.getTrustStores() == null || updated.getTrustStores().isEmpty() ) && requiresCertificate)
            {
                throw new IllegalConfigurationException("Can't create port which requests SSL client certificates but has no trust store configured.");
            }
        }
        else
        {
            if (requiresCertificate)
            {
                throw new IllegalConfigurationException("Can't create port which requests SSL client certificates but doesn't use SSL transport.");
            }
        }


        if(requiresCertificate && updated.getClientCertRecorder() != null)
        {
            if(!(updated.getClientCertRecorder() instanceof ManagedPeerCertificateTrustStore))
            {
                throw new IllegalConfigurationException("Only trust stores of type " + ManagedPeerCertificateTrustStore.TYPE_NAME + " may be used as the client certificate recorder");
            }
        }
    }
}
