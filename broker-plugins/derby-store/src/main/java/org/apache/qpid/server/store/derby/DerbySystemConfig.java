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
package org.apache.qpid.server.store.derby;

import org.apache.qpid.server.model.ManagedAttribute;
import org.apache.qpid.server.model.SystemConfig;
import org.apache.qpid.server.store.FileBasedSettings;
import org.apache.qpid.server.store.SizeMonitoringSettings;
import org.apache.qpid.server.store.preferences.PreferenceStoreAttributes;

public interface DerbySystemConfig<X extends DerbySystemConfig<X>> extends SystemConfig<X>, FileBasedSettings,
                                                                           SizeMonitoringSettings
{
    @ManagedAttribute(defaultValue = "${qpid.work_dir}${file.separator}config.json")
    String getStorePath();

    @ManagedAttribute(mandatory = true, defaultValue = "0")
    Long getStoreUnderfullSize();

    @ManagedAttribute(mandatory = true, defaultValue = "0")
    Long getStoreOverfullSize();

    @ManagedAttribute( description = "Configuration for the preference store, e.g. type, path, etc.",
            defaultValue = "{\"type\": \"Provided\"}")
    PreferenceStoreAttributes getPreferenceStoreAttributes();
}
