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

import cloud.dnation.jenkins.plugins.hetzner.HetznerConstants;
import com.google.common.net.HttpHeaders;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
public class ClientFactory {
    private static final ConnectionPool CP = new ConnectionPool(2,
            30,
            TimeUnit.SECONDS);

    private static final Gson GSON = new GsonBuilder().create();

    private static HetznerApi create(String apiToken) {
        final boolean debug = Boolean.TRUE.toString().equals(System.getProperty(HetznerConstants.PROP_CLIENT_DEBUG,
                Boolean.FALSE.toString()));
        final HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        //noinspection UnstableApiUsage
        loggingInterceptor.redactHeader(HttpHeaders.AUTHORIZATION);
        loggingInterceptor.setLevel(debug ? HttpLoggingInterceptor.Level.BASIC : HttpLoggingInterceptor.Level.NONE);
        final OkHttpClient client = new OkHttpClient.Builder()
                .connectionPool(CP)
                .addInterceptor(new AuthenticationInterceptor(apiToken))
                .addInterceptor(UserAgentInterceptor.INSTANCE)
                .addInterceptor(loggingInterceptor)
                .build();

        final Retrofit.Builder builder = new Retrofit.Builder()
                .baseUrl(System.getProperty(HetznerConstants.PROP_API_ENDPOINT,
                        HetznerConstants.DEFAULT_ENDPOINT))
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(GSON));
        return builder.build().create(HetznerApi.class);
    }
    /**
     * Create new {@link Retrofit} object using token provider.
     *
     * @param tokenProvider supplier of Hetzner API token.
     * @return Proxy of {@link HetznerApi}
     * @throws IllegalStateException when credentialsId is not valid
     */
    public static HetznerApi create(Supplier<String> tokenProvider) {
        return create(tokenProvider.get());
    }
}
