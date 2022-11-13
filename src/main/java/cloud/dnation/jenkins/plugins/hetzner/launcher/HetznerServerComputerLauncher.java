/*
 *     Copyright 2021 https://dnation.cloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cloud.dnation.jenkins.plugins.hetzner.launcher;

import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.jenkins.plugins.hetzner.Helper;
import cloud.dnation.jenkins.plugins.hetzner.HetznerConstants;
import cloud.dnation.jenkins.plugins.hetzner.HetznerServerAgent;
import cloud.dnation.jenkins.plugins.hetzner.HetznerServerComputer;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static cloud.dnation.jenkins.plugins.hetzner.Helper.assertSshKey;
import static cloud.dnation.jenkins.plugins.hetzner.Helper.getStringOrDefault;
import static cloud.dnation.jenkins.plugins.hetzner.HetznerConstants.DEFAULT_REMOTE_FS;
import static hudson.plugins.sshslaves.SSHLauncher.AGENT_JAR;

@RequiredArgsConstructor
@Slf4j
public class HetznerServerComputerLauncher extends ComputerLauncher {
    private static final String AGENT_SCRIPT = ".agent.start.sh";
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AbstractHetznerSshConnector connector;

    private static String getRemoteFs(HetznerServerAgent agent) {
        final String res = getStringOrDefault(agent.getRemoteFS(), DEFAULT_REMOTE_FS);
        //trim trailing slash
        if (res.endsWith("/")) {
            return res.substring(0, res.length() - 1);
        }
        return res;
    }

    private void copyAgent(Connection connection,
                           HetznerServerComputer computer,
                           Helper.LogAdapter logger,
                           String remoteFs) throws IOException {
        final byte[] agentBlob = Jenkins.get().getJnlpJars(AGENT_JAR).readFully();
        final String remoteAgentPath = remoteFs + "/" + AGENT_JAR;
        final byte[] launchScriptContent = ("#!/bin/sh" + '\n' + getAgentCommand(computer, remoteFs) + '\n')
                .getBytes(StandardCharsets.UTF_8);
        final String launchScriptPath = remoteFs + "/" + AGENT_SCRIPT;
        final SCPClient scp = connection.createSCPClient();
        logger.info("Copying agent JAR - " + agentBlob.length + " bytes into " + remoteAgentPath);
        scp.put(agentBlob, AGENT_JAR, remoteFs, "0444");
        logger.info("Copying agent script - " + launchScriptContent.length + " bytes into " + launchScriptPath);
        scp.put(launchScriptContent, AGENT_SCRIPT, remoteFs, "0555");
    }

    @SuppressFBWarnings(value = {"NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", "NP_NULL_PARAM_DEREF"},
            justification = "NULLnes is checked already")
    @Override
    public void launch(final SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        if (!(computer instanceof HetznerServerComputer)) {
            throw new AbortException("Incompatible computer : " + computer);
        }
        if(connector.getConnectionMethod() == null) {
            connector.setConnectionMethod(HetznerConstants.DEFAULT_CONNECTION_METHOD);
        }
        final HetznerServerComputer hcomputer = (HetznerServerComputer) computer;
        final Helper.LogAdapter logger = new Helper.LogAdapter(listener.getLogger(), log);
        final HetznerServerAgent node = hcomputer.getNode();
        Preconditions.checkState(node != null && node.getServerInstance() != null,
                "Missing node or server instance data in computer %s", computer.getName());
        final String remoteFs = getRemoteFs(node);
        final Connection connection = setupConnection(node, logger, listener);
        copyAgent(connection, hcomputer, logger, remoteFs);
        launchAgent(connection, hcomputer, logger, listener, remoteFs);
    }

    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
            justification = "NULLnes of node is checked in launch method")
    private String getAgentCommand(HetznerServerComputer computer, String remoteFs) {
        final String jvmOpts = Util.fixNull(computer.getNode().getTemplate().getJvmOpts());
        return "java " + jvmOpts + " -jar " + remoteFs + "/remoting.jar -workDir " + remoteFs;
    }

    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
            justification = "NULLnes of node is checked in launch method")
    private void launchAgent(Connection connection,
                             HetznerServerComputer computer,
                             Helper.LogAdapter logger,
                             TaskListener listener,
                             String remoteFs
    )
            throws IOException, InterruptedException {
        final HetznerServerAgent node = computer.getNode();
        final Session session = connection.openSession();
        final String scriptCmd = "/bin/sh " + remoteFs + "/" + AGENT_SCRIPT;
        final String launchCmd;
        final String username = connector.getUsernameOverride();
        if (username != null) {
            final String credentialsId = node.getTemplate().getConnector().getSshCredentialsId();
            final BasicSSHUserPrivateKey privateKey = assertSshKey(credentialsId);
            launchCmd = "sudo -n -u " + privateKey.getUsername() + " " + scriptCmd;
        } else {
            launchCmd = scriptCmd;
        }

        logger.info("Launching agent using '" + launchCmd + "'");
        session.execCommand(launchCmd);
        computer.setChannel(session.getStdout(), session.getStdin(), listener, new Channel.Listener() {
            @Override
            public void onClosed(Channel channel, IOException cause) {
                session.close();
                connection.close();
            }
        });
    }

    private Connection setupConnection(HetznerServerAgent node,
                                       Helper.LogAdapter logger,
                                       TaskListener taskListener) throws InterruptedException, AbortException {
        int retries = 10;
        while (!terminated.get() && retries-- > 0) {
            final ServerDetail serverDetail = node.getServerInstance().getServerDetail();
            final String ipv4 = connector.getConnectionMethod().getAddress(serverDetail);
            final Connection conn = new Connection(ipv4, 22);
            try {
                conn.connect(AllowAnyServerHostKeyVerifier.INSTANCE,
                        30_000, 10_000);
                logger.info("Connected to " + node.getNodeName() + " via " + ipv4);
                final String credentialsId = node.getTemplate().getConnector().getSshCredentialsId();
                final BasicSSHUserPrivateKey privateKey = assertSshKey(credentialsId);
                final String username = Util.fixNull(node.getTemplate().getConnector().getUsernameOverride(),
                        privateKey.getUsername());

                logger.info("Authenticating using username '" + username + "'");

                final SSHAuthenticator<Connection, BasicSSHUserPrivateKey> authenticator = SSHAuthenticator
                        .newInstance(conn, privateKey, username);

                if (authenticator.authenticate(taskListener) && conn.isAuthenticationComplete()) {
                    logger.info("Authentication succeeded");
                    return conn;
                } else {
                    throw new AbortException("Authentication failed");
                }
            } catch (IOException e) {
                logger.error("Connection to " + ipv4 + " failed. Will wait 10 seconds before retry", e);
                Uninterruptibles.sleepUninterruptibly(10, TimeUnit.SECONDS);
            }
        }
        throw new AbortException("Failed to launch agent");
    }

    public void signalTermination() {
        terminated.set(true);
    }

    //TODO: is there a way to verify hostkey of newly created server?
    //its is usually generated by cloud-init
    private static class AllowAnyServerHostKeyVerifier implements ServerHostKeyVerifier {
        static final AllowAnyServerHostKeyVerifier INSTANCE = new AllowAnyServerHostKeyVerifier();

        @Override
        public boolean verifyServerHostKey(String hostname, int port,
                                           String serverHostKeyAlgorithm,
                                           byte[] serverHostKey) throws Exception {
            return true;
        }
    }
}
