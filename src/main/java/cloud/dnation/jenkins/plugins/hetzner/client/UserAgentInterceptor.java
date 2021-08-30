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
package cloud.dnation.jenkins.plugins.hetzner.client;

import com.google.common.net.HttpHeaders;
import lombok.NonNull;
import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;

/**
 * {@link Interceptor} which adds "User-Agent" request header.
 */
class UserAgentInterceptor implements Interceptor {
    static final UserAgentInterceptor INSTANCE = new UserAgentInterceptor();

    @Override
    @NonNull
    public Response intercept(Chain chain) throws IOException {
        //noinspection UnstableApiUsage
        return chain.proceed(chain.request().newBuilder()
                .addHeader(HttpHeaders.USER_AGENT, "Jenkins Hetzner Plugin")
                .build());
    }
}
