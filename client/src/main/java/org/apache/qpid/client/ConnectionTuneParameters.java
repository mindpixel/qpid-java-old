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
package org.apache.qpid.client;

public class ConnectionTuneParameters
{
    private long _frameMax;

    private int _channelMax;

    /** Heart-beating interval in seconds, null if not set, use 0 to disable */
    private Integer _heartbeat;

    private float _heartbeatTimeoutFactor;

    public float getHeartbeatTimeoutFactor()
    {
        return _heartbeatTimeoutFactor;
    }

    public void setHeartbeatTimeoutFactor(float heartbeatTimeoutFactor)
    {
        _heartbeatTimeoutFactor = heartbeatTimeoutFactor;
    }

    public long getFrameMax()
    {
        return _frameMax;
    }

    public void setFrameMax(long frameMax)
    {
        _frameMax = frameMax;
    }

    public int getChannelMax()
    {
        return _channelMax;
    }

    public void setChannelMax(int channelMax)
    {
        _channelMax = channelMax;
    }    

    public Integer getHeartbeat()
    {
        return _heartbeat;
    }

    public void setHeartbeat(Integer hearbeat)
    {
        _heartbeat = hearbeat;
    }
}
