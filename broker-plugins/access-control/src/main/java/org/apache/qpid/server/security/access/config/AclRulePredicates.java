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
package org.apache.qpid.server.security.access.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.security.access.config.ObjectProperties.Property;
import org.apache.qpid.server.security.access.firewall.FirewallRule;
import org.apache.qpid.server.security.access.firewall.FirewallRuleFactory;

/**
 * Represents the predicates on an ACL rule by combining predicates relating to the object being operated on
 * (e.g. name=foo) with firewall rules.
 */
public class AclRulePredicates
{
    private static final Logger _logger = LoggerFactory.getLogger(AclRulePredicates.class);

    private static final String SEPARATOR = ",";

    private ObjectProperties _properties = new ObjectProperties();

    private FirewallRule _firewallRule;

    private FirewallRuleFactory _firewallRuleFactory = new FirewallRuleFactory();

    public AclRulePredicates()
    {
    }

    public AclRulePredicates(Map<Property, String> values)
    {
        if(values != null)
        {
            for(Map.Entry<Property, String> entry : values.entrySet())
            {
                addPropertyValue(entry.getKey(), entry.getValue());
            }
        }
    }

    public void parse(String key, String value)
    {
        ObjectProperties.Property property = ObjectProperties.Property.parse(key);

        addPropertyValue(property, value);

        _logger.debug("Parsed {} with value {}",  property, value);
    }

    private void addPropertyValue(final Property property, final String value)
    {
        if(property == Property.FROM_HOSTNAME)
        {
            checkFirewallRuleNotAlreadyDefined(property.name(), value);
            _firewallRule = _firewallRuleFactory.createForHostname(value.split(SEPARATOR));
        }
        else if(property == Property.FROM_NETWORK)
        {
            checkFirewallRuleNotAlreadyDefined(property.name(), value);
            _firewallRule = _firewallRuleFactory.createForNetwork(value.split(SEPARATOR));
        }
        else
        {
            _properties.put(property, value);
        }
    }

    private void checkFirewallRuleNotAlreadyDefined(String key, String value)
    {
        if(_firewallRule != null)
        {
            throw new IllegalStateException(
                    "Cannot parse " + key + "=" + value
                    + " because firewall rule " + _firewallRule + " has already been defined");
        }
    }

    @Override
    public String toString()
    {
        return "AclRulePredicates[" +
               "properties=" + _properties +
               ", firewallRule=" + _firewallRule +
               ']';
    }

    public FirewallRule getFirewallRule()
    {
        return _firewallRule;
    }

    public ObjectProperties getObjectProperties()
    {
        return _properties;
    }

    void setFirewallRuleFactory(FirewallRuleFactory firewallRuleFactory)
    {
        _firewallRuleFactory = firewallRuleFactory;
    }
}
