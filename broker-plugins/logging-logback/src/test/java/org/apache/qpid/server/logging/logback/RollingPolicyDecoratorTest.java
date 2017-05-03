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
package org.apache.qpid.server.logging.logback;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import ch.qos.logback.core.Context;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingPolicyBase;
import org.apache.qpid.test.utils.QpidTestCase;
import org.apache.qpid.test.utils.TestFileUtils;
import org.apache.qpid.util.FileUtils;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class RollingPolicyDecoratorTest extends QpidTestCase
{
    private RollingPolicyBase _delegate;
    private RollingPolicyDecorator _policy;
    private RollingPolicyDecorator.RolloverListener _listener;
    private File _baseFolder;
    private File _testFile;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();

        _baseFolder = TestFileUtils.createTestDirectory("rollover", true);
        _testFile = createTestFile("test.2015-06-25.0.gz");
        Context mockContext = mock(Context.class);
        _delegate = mock(RollingPolicyBase.class);
        when(_delegate.getFileNamePattern()).thenReturn( _baseFolder + File.separator + "test.%d{yyyy-MM-dd}.%i.gz");
        when(_delegate.getContext()).thenReturn(mockContext);
        _listener = mock(RollingPolicyDecorator.RolloverListener.class);

        _policy = new RollingPolicyDecorator(_delegate, _listener, createMockExecutorService());
    }

    @Override
    public void tearDown() throws Exception
    {
        super.tearDown();
        if (_baseFolder.exists())
        {
            FileUtils.delete(_baseFolder, true);
        }
    }

    public File createTestFile(String fileName) throws IOException
    {
        File testFile = new File(_baseFolder, fileName);
        assertTrue("Cannot create a new file " + testFile.getPath(), testFile.createNewFile());
        return testFile;
    }

    private ScheduledExecutorService createMockExecutorService()
    {
        ScheduledExecutorService executorService = mock(ScheduledExecutorService.class);
        doAnswer(new Answer() {
            public Object answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                ((Runnable)args[0]).run();
                return null;
            }}).when(executorService).schedule(any(Runnable.class), any(long.class), any(TimeUnit.class));
        doAnswer(new Answer() {
            public Object answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                ((Runnable)args[0]).run();
                return null;
            }}).when(executorService).execute(any(Runnable.class));
        return executorService;
    }

    public void testRollover()
    {
        _policy.rollover();
        verify(_delegate).rollover();
    }

    public void testRolloverListener() throws InterruptedException
    {
        _policy.rollover();
        verify(_listener).onRollover(any(Path.class), any(String[].class));
    }

    public void testRolloverWithFile() throws IOException
    {
        _policy.rollover();
        verify(_delegate).rollover();

        Matcher<String[]> matcher = getMatcher(new String[]{_testFile.getName()});
        verify(_listener).onRollover(eq(_baseFolder.toPath()), argThat(matcher));
    }

    public void testRolloverRescanLimit() throws IOException
    {
        _policy.rollover();
        verify(_delegate).rollover();
        Matcher<String[]> matcher = getMatcher(new String[]{_testFile.getName()});
        verify(_listener).onRollover(eq(_baseFolder.toPath()), argThat(matcher));
        _policy.rollover();
        verify(_delegate, times(2)).rollover();
        verify(_listener).onNoRolloverDetected(eq(_baseFolder.toPath()), argThat(matcher));
    }

    public void testSequentialRollover() throws IOException
    {
        _policy.rollover();
        verify(_delegate).rollover();

        Matcher<String[]> matcher = getMatcher(new String[]{ _testFile.getName() });
        verify(_listener).onRollover(eq(_baseFolder.toPath()), argThat(matcher));

        File secondFile = createTestFile("test.2015-06-25.1.gz");
        _policy.rollover();
        verify(_delegate, times(2)).rollover();
        Matcher<String[]> matcher2 = getMatcher(new String[]{_testFile.getName(), secondFile.getName()});
        verify(_listener).onRollover(eq(_baseFolder.toPath()), argThat(matcher2));
    }

    private Matcher<String[]> getMatcher(final String[] expected)
    {
        return new BaseMatcher<String[]>()
        {
            @Override
            public boolean matches(Object item)
            {
                return Arrays.equals(expected, (String[]) item);
            }
            @Override
            public void describeTo(Description description)
            {
                description.appendValueList("[", ",", "]", expected);
            }
        };
    }

    public void testGetActiveFileName()
    {
        _policy.getActiveFileName();
        verify(_delegate).getActiveFileName();
    }

    public void testGetCompressionMode()
    {
        _policy.getCompressionMode();
        verify(_delegate).getCompressionMode();
    }

    public void testSetParent()
    {
        FileAppender appender = mock(FileAppender.class);
        _policy.setParent(appender);
        verify(_delegate).setParent(appender);
    }

    public void testStart()
    {
        _policy.start();
        verify(_delegate).start();
    }

    public void testStop()
    {
        _policy.stop();
        verify(_delegate).stop();
    }

    public void testIsStarted()
    {
        _policy.isStarted();
        verify(_delegate).isStarted();
    }

}
