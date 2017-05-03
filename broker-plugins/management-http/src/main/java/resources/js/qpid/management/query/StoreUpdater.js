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

define(["dojo/_base/declare",
        "dojo/_base/lang",
        "dojo/json",
        "dojo/promise/all",
        "qpid/common/util"],
    function (declare, lang, json, all, util)
    {
        var createIdToIndexMap = function (results, idProperty)
        {
            var map = {};
            for (var i = 0; i < results.length; i++)
            {
                var id = results[i][idProperty];
                map[id] = i;
            }
            return map;
        };

        var updateIdToIndexMap = function (results, idProperty, idToIndexMap, startIndex)
        {
            for (var i = startIndex; i < results.length; i++)
            {
                var id = results[i][idProperty];
                idToIndexMap[id] = i;
            }
        };

        return declare(null,
            {
                /**
                 * fields set from constructor parameter object
                 */
                targetStore: null,

                /**
                 * internal fields
                 */
                _currentResults: null,
                _currentResultsIdToIndexMap: null,
                _updating: false,
                _fetchRangeCapturedArguments: null,

                fetch: function ()
                {
                    var queryResults = this.inherited(arguments);
                    this._captureResults(queryResults, "fetch");
                    return queryResults;
                },
                fetchRange: function (args)
                {
                    var queryResults = this.inherited(arguments);
                    this._captureResults(queryResults, "fetchRange", args);
                    return queryResults;
                },
                update: function ()
                {
                    if (!this._updating)
                    {
                        this._updating = true;
                        return this.fetch();
                    }
                },
                updateRange: function ()
                {
                    var args = this._fetchRangeCapturedArguments;
                    if (!this._updating && args)
                    {
                        this._updating = true;
                        return this.fetchRange(args);
                    }
                },
                _captureResults: function (queryResults, methodName, args)
                {
                    var handler = lang.hitch(this, function (data)
                    {
                        this._processResults(data, methodName, args);
                    });
                    all({results: queryResults, totalLength: queryResults.totalLength})
                        .then(handler);
                },
                _processResults: function (data, methodName, args)
                {
                    var capturedArguments = args ? lang.clone(args) : {};
                    if (this._updating)
                    {
                        try
                        {
                            lang.mixin(capturedArguments, data);
                            this._detectChangesAndNotify(capturedArguments);
                        }
                        finally
                        {
                            this._updating = false;
                            this.emit("updateCompleted", capturedArguments);
                        }
                    }
                    else
                    {
                        var results = data.results.slice(0);
                        this._currentResults = results;
                        this._currentResultsIdToIndexMap = createIdToIndexMap(results, this.idProperty);
                        this["_" + methodName + "CapturedArguments"] = capturedArguments;
                    }
                },
                _detectChangesAndNotify: function (data)
                {
                    var results = data.results;
                    var offset = data.start || 0;
                    if (results)
                    {
                        var newResults = results.slice(0);
                        var idProperty = this.idProperty;
                        var newResultsIdToIndexMap = createIdToIndexMap(newResults, idProperty);
                        if (this._currentResults)
                        {
                            var currentResults = this._currentResults.slice(0);
                            for (var i = currentResults.length - 1; i >= 0; i--)
                            {
                                var currentResult = currentResults[i];
                                var id = currentResult[idProperty];
                                var newResultIndex = newResultsIdToIndexMap[id];
                                if (newResultIndex === undefined)
                                {
                                    this._pushChange("delete", currentResult, offset + i, offset + i);
                                    currentResults.splice(i, 1);
                                    updateIdToIndexMap(currentResults,
                                        idProperty,
                                        this._currentResultsIdToIndexMap,
                                        i);
                                    delete this._currentResultsIdToIndexMap[id];
                                }
                            }

                            for (var j = 0; j < newResults.length; j++)
                            {
                                var newResult = newResults[j];
                                var id = newResult[idProperty];
                                var previousIndex = this._currentResultsIdToIndexMap[id];

                                if (previousIndex === undefined)
                                {
                                    currentResults.splice(j, 0, newResult);
                                    updateIdToIndexMap(currentResults,
                                        idProperty,
                                        this._currentResultsIdToIndexMap,
                                        j);
                                    this._pushChange("add", newResult, offset + j);
                                }
                                else
                                {
                                    var currentResult = currentResults[previousIndex];
                                    if (previousIndex === j)
                                    {
                                        currentResults[j] = newResult;
                                        if (!util.equals(newResult, currentResult))
                                        {
                                            this._pushChange("update", newResult, offset + j, previousIndex + offset);
                                        }
                                    }
                                    else
                                    {
                                        this._pushChange("update", newResult, offset + j, previousIndex + offset);
                                        currentResults.splice(previousIndex, 1);
                                        currentResults.splice(j, 0, currentResult);
                                        updateIdToIndexMap(currentResults,
                                            idProperty,
                                            this._currentResultsIdToIndexMap,
                                            Math.min(previousIndex, j));
                                    }
                                }
                            }
                        }
                        else
                        {
                            for (var j = 0; j < newResults.length; j++)
                            {
                                this._pushChange("add", newResults[j], offset + j);
                            }
                        }

                        this._currentResults = newResults;
                        this._currentResultsIdToIndexMap = newResultsIdToIndexMap;
                    }
                },
                _pushChange: function (change, item, currentIndex, previousIndex)
                {
                    if (this.targetStore)
                    {
                        if (change === "update")
                        {
                            this.targetStore.put(item);
                        }
                        else if (change === "add")
                        {
                            this.targetStore.add(item);
                        }
                        else if (change === "delete")
                        {
                            this.targetStore.remove(item.id);
                        }
                        else
                        {
                            throw new Error("Change " + change + " is unknown by the store");
                        }
                    }
                    else
                    {
                        var event = {
                            "target": item,
                            "index": currentIndex,
                            "previousIndex": previousIndex
                        };
                        this.emit(change, event);
                    }
                }
            });
    });