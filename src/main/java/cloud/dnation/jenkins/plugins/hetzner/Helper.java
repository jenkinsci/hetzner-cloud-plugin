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
package cloud.dnation.jenkins.plugins.hetzner;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.trilead.ssh2.crypto.PEMDecoder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.slf4j.Logger;
import retrofit2.Response;

import java.io.IOException;
import java.io.PrintStream;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cloud.dnation.jenkins.plugins.hetzner.HetznerConstants.SHUTDOWN_TIME_BUFFER;

@UtilityClass
public class Helper {
    private static final Pattern LABEL_VALUE_RE = Pattern.compile("^(?![0-9]+$)(?!-)[a-zA-Z0-9-_.]{0,63}(?<!-)$");
    private static final String SSH_RSA = "ssh-rsa";
    private static final String SSH_ED25519 = "ssh-ed25519";

    /**
     * Extract public key from SSH private key.
     *
     * @param privateKey PEM-encoded SSH private key
     * @param password   optional (nullable) password
     * @return SSH public key
     * @throws IOException in case of I/O error
     */
    public static String getSSHPublicKeyFromPrivate(String privateKey, @Nullable String password) throws IOException {
        final KeyPair pair = PEMDecoder.decodeKeyPair(privateKey.toCharArray(), password);
        final PublicKey pk = pair.getPublic();
        final String prefix;
        final AsymmetricKeyParameter keyParam;
        if (pk instanceof EdDSAPublicKey edpk) {
          prefix = SSH_ED25519;
            keyParam = new Ed25519PublicKeyParameters(edpk.getAbyte(), 0);
        } else if (pk instanceof RSAPublicKey rsapk) {
          prefix = SSH_RSA;
            keyParam = new RSAKeyParameters(false, rsapk.getModulus(), rsapk.getPublicExponent());
        } else {
            throw new IllegalArgumentException("PublicKey type not recognized: " + pk.getClass());
        }
        return prefix + " " + Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(keyParam));
    }

    /**
     * Check if given string could possibly be label expression.
     *
     * @param expression string to check
     * @return <code>true</code> if given expression could be label expression, <code>false</code> otherwise
     */
    public static boolean isLabelExpression(String expression) {
        return expression.contains("=");
    }

    /**
     * Check if given string could be parsed as positive long.
     *
     * @param str string to check
     * @return <code>true</code> if given string could be parsed as positive long, <code>false</code> otherwise
     */
    public static boolean isPossiblyLong(String str) {
        try {
            final long value = Long.parseLong(str);
            return value > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static List<Long> idList(String str) {
        return Arrays.stream(str.split(",")).map(Long::parseLong).toList();
    }

    public static <T, E> List<E> getPayload(@NonNull Response<T> response, @NonNull Function<T, List<E>> mapper) {
        final T body = response.body();
        if (body == null) {
            return Collections.emptyList();
        }
        return Optional.ofNullable(mapper.apply(body)).orElse(Collections.emptyList());
    }

    public static <T, E> E assertValidResponse(Response<T> response, Function<T, E> mapper) {
        Preconditions.checkState(response.isSuccessful(), "Invalid API response : %s",
                response.code());
        return mapper.apply(response.body());
    }

    public static <T> void assertValidResponse(Response<T> response) {
        assertValidResponse(response, (Function<T, Void>) t -> null);
    }

    public static BasicSSHUserPrivateKey assertSshKey(String credentialsId) {
        final BasicSSHUserPrivateKey privateKey = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(BasicSSHUserPrivateKey.class, Jenkins.get(), ACL.SYSTEM2,
                        Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));

        Preconditions.checkState(privateKey != null,
                "No SSH credentials found with ID '%s'", credentialsId);

        return privateKey;
    }

    public static String getStringOrDefault(String value, String defValue) {
        if(Strings.isNullOrEmpty(value)) {
            return defValue;
        }
        return value;
    }

    @RequiredArgsConstructor
    public static class LogAdapter {
        private static final SimpleFormatter FORMATTER = new SimpleFormatter();
        @Getter
        private final PrintStream stream;
        private final Logger logger;

        public void info(String message) {
            logger.info(message);
            final LogRecord rec = new LogRecord(Level.INFO, message);
            rec.setLoggerName(logger.getName());
            stream.println(FORMATTER.format(rec));
        }

        public void error(String message, Throwable cause) {
            logger.error(message, cause);
            final LogRecord rec = new LogRecord(Level.SEVERE, message + " Cause: " + cause);
            rec.setLoggerName(logger.getName());
            rec.setThrown(cause);
            stream.println(FORMATTER.format(rec));
        }
    }

    /**
     * Check if idle server can be shut down.
     * <p>
     * According to <a href="https://docs.hetzner.com/cloud/billing/faq#how-do-you-bill-your-servers">Hetzner billing policy</a>,
     * you are billed for every hour of existence of server, so it makes sense to keep server running as long as next hour did
     * not start yet.
     *
     * @param createdStr  RFC3339-formatted instant when server was created. See ServerDetail#getCreated().
     * @param currentTime current time. Kept as argument to allow unit-testing.
     * @return <code>true</code> if server should be shut down, <code>false</code> otherwise.
     * Note: we keep small time buffer for corner cases like clock skew or Jenkins's queue manager overload, which could
     * lead to unnecessary 1-hour over-billing.
     */
    public static boolean canShutdownServer(@NonNull String createdStr, LocalDateTime currentTime) {
        final LocalDateTime created = LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(createdStr))
                .atOffset(ZoneOffset.UTC).toLocalDateTime();
        long diff = Duration.between(created, currentTime.atOffset(ZoneOffset.UTC).toLocalDateTime()).toMinutes() % 60;
        return (60 - SHUTDOWN_TIME_BUFFER) <= diff;
    }

    /**
     * Get all nodes that are {@link HetznerServerAgent}.
     *
     * @return list of all {@link HetznerServerAgent} nodes
     */
    public static List<HetznerServerAgent> getHetznerAgents() {
        return Jenkins.get().getNodes()
                .stream()
                .filter(HetznerServerAgent.class::isInstance)
                .map(HetznerServerAgent.class::cast)
                .collect(Collectors.toList());
    }

    public static boolean isValidLabelValue(String value) {
        if (Strings.isNullOrEmpty(value)) {
            return false;
        }
        return LABEL_VALUE_RE.matcher(value).matches();
    }
}
