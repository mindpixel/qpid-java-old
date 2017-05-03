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
package org.apache.qpid.disttest;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

public class AbstractRunner
{
    public static final String JNDI_CONFIG_PROP = "jndi-config";
    public static final String JNDI_CONFIG_DEFAULT = "perftests-jndi.properties";

    private Map<String,String> _cliOptions = new HashMap<String, String>();
    {
        getCliOptions().put(JNDI_CONFIG_PROP, JNDI_CONFIG_DEFAULT);
    }

    protected Context getContext()
    {
        String jndiConfig = getJndiConfig();
        Hashtable env = new Hashtable();
        env.put(Context.PROVIDER_URL, jndiConfig);
        // Java allows this to be overridden with a system property of the same name
        if (!System.getProperties().containsKey(InitialContext.INITIAL_CONTEXT_FACTORY))
        {
            env.put(InitialContext.INITIAL_CONTEXT_FACTORY, "org.apache.qpid.jndi.PropertiesFileInitialContextFactory");
        }

        try
        {
            return  new InitialContext(env);
        }
        catch (NamingException e)
        {
            throw new DistributedTestException("Exception whilst creating InitialContext from URL '"
                                               + jndiConfig + "'", e);
        }
    }

    public void parseArgumentsIntoConfig(String[] args)
    {
        ArgumentParser argumentParser = new ArgumentParser();
        argumentParser.parseArgumentsIntoConfig(getCliOptions(), args);
    }

    protected String getJndiConfig()
    {
        return getCliOptions().get(AbstractRunner.JNDI_CONFIG_PROP);
    }

    protected Map<String,String> getCliOptions()
    {
        return _cliOptions;
    }
}
