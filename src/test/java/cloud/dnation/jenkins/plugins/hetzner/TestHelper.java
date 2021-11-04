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

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import lombok.experimental.UtilityClass;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

@UtilityClass
public class TestHelper {
    public static String inputStreamAsString(InputStream is) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ByteStreams.copy(is, os);
        return os.toString(StandardCharsets.UTF_8.name());
    }

    public static String resourceAsString(String name) throws IOException {
        try (InputStream is = Resources.getResource(name).openStream()) {
            return inputStreamAsString(is);
        }
    }
}
