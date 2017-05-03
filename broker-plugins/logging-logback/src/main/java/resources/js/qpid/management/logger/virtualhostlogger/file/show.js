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
define(["qpid/common/util",
        "dojo/text!logger/file/show.html",
        "qpid/common/TypeTabExtension",
        "qpid/management/logger/FileBrowser",
        "dojo/domReady!"], function (util, template, TypeTabExtension, FileBrowser)
{
    function VirtualHostFileLogger(params)
    {
        this.fileBrowser = new FileBrowser({
            containerNode: params.typeSpecificDetailsNode,
            management: params.management,
            data: params.data,
            modelObj: params.modelObj
        });
        TypeTabExtension.call(this,
            params.containerNode,
            template,
            "VirtualHostLogger",
            "File",
            params.metadata,
            params.data);
    }

    util.extend(VirtualHostFileLogger, TypeTabExtension);

    VirtualHostFileLogger.prototype.update = function (restData)
    {
        TypeTabExtension.prototype.update.call(this, restData);
        this.fileBrowser.update(restData);
    }

    return VirtualHostFileLogger;
});
