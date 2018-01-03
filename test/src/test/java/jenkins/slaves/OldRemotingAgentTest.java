/*
 * The MIT License
 *
 * Copyright (c) 2018, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.slaves;

import hudson.EnvVars;
import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Slave;
import hudson.model.labels.LabelAtom;
import hudson.node_monitors.AbstractAsyncNodeMonitorDescriptor;
import hudson.node_monitors.AbstractNodeMonitorDescriptor;
import hudson.node_monitors.NodeMonitor;
import hudson.slaves.ComputerLauncher;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import org.codehaus.plexus.util.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;

import javax.annotation.CheckForNull;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.Collection;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

/**
 * Tests for old Remoting agent versions
 */
public class OldRemotingAgentTest {

    @Rule
    public JenkinsRule j = new JenkinsRuleWithOldAgent();

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private File agentJar;

    @Before
    public void extractAgent() throws Exception {
        agentJar = new File(tmpDir.getRoot(), "old-agent.jar");
        FileUtils.copyURLToFile(getClass().getResource("/old-remoting/remoting-minimal-supported.jar"), agentJar);
    }

    @Test
    @Issue("JENKINS-48761")
    public void shouldBeAbleToConnectAgentWithMinimalSupportedVersion() throws Exception {
        Label agentLabel = new LabelAtom("old-agent");
        Slave agent = j.createOnlineSlave(agentLabel);
        boolean isUnix = agent.getComputer().isUnix();
        assertThat("Received wrong agent version. A minimal supported version is expected",
                agent.getComputer().getSlaveVersion(),
                equalTo(RemotingVersionInfo.getMinimalSupportedVersion().toString()));

        // Just ensure we are able to run something on the agent
        FreeStyleProject project = j.createFreeStyleProject("foo");
        project.setAssignedLabel(agentLabel);
        project.getBuildersList().add(isUnix ? new Shell("echo Hello") : new BatchFile("echo 'hello'"));
        j.buildAndAssertSuccess(project);

        // Run agent monitors
        NodeMonitorAssert.assertMonitors(NodeMonitor.getAll(), agent.getComputer());
    }

    //TODO: move the logic to JTH
    private class JenkinsRuleWithOldAgent extends JenkinsRule {

        @Override
        public ComputerLauncher createComputerLauncher(EnvVars env) throws URISyntaxException, IOException {

            // EnvVars are ignored, simple Command Launcher does not offer this API in public
            int sz = this.jenkins.getNodes().size();
            return new SimpleCommandLauncher(String.format("\"%s/bin/java\" %s -jar \"%s\"",
                    System.getProperty("java.home"),
                    SLAVE_DEBUG_PORT > 0 ? " -Xdebug -Xrunjdwp:transport=dt_socket,server=y,address=" + (SLAVE_DEBUG_PORT + sz) : "",
                    agentJar.getAbsolutePath()));
        }
    }

    private static class NodeMonitorAssert extends NodeMonitor {

        static void assertMonitors(Collection<NodeMonitor> toCheck, Computer c) throws AssertionError {
            for (NodeMonitor monitor : toCheck) {
                assertMonitor(monitor, c);
            }
        }

        static void assertMonitor(NodeMonitor monitor, Computer c) throws AssertionError {
            AbstractNodeMonitorDescriptor<?> descriptor = monitor.getDescriptor();
            final Method monitorMethod;
            try {
                monitorMethod = AbstractAsyncNodeMonitorDescriptor.class.getDeclaredMethod("monitor", Computer.class);
            } catch (NoSuchMethodException ex) {
                System.out.println("Cannot invoke monitor " + monitor + ", no monitor(Computer.class) method in the Descriptor. It will be ignored. " + ex.getMessage());
                return;
            }
            try {
                monitorMethod.setAccessible(true);
                Object res = monitorMethod.invoke(descriptor, c);
                System.out.println("Successfully executed monitor " + monitor);
            } catch (Exception ex) {
                throw new AssertionError("Failed to run monitor " + monitor + " for computer " + c, ex);
            } finally {
                monitorMethod.setAccessible(false);
            }
        }
    }

}
