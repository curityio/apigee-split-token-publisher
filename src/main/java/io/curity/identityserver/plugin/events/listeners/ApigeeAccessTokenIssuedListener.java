/*
 *  Copyright 2020 Curity AB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.curity.identityserver.plugin.events.listeners;

import io.curity.identityserver.plugin.events.listeners.config.ApigeeEventListenerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.data.events.IssuedAccessTokenOAuthEvent;
import se.curity.identityserver.sdk.errors.ErrorCode;
import se.curity.identityserver.sdk.event.EventListener;
import se.curity.identityserver.sdk.http.HttpRequest;
import se.curity.identityserver.sdk.http.HttpResponse;
import se.curity.identityserver.sdk.service.ExceptionFactory;
import se.curity.identityserver.sdk.service.HttpClient;
import se.curity.identityserver.sdk.service.WebServiceClient;
import se.curity.identityserver.sdk.service.WebServiceClientFactory;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ApigeeAccessTokenIssuedListener implements EventListener<IssuedAccessTokenOAuthEvent>
{
    private static final Logger _logger = LoggerFactory.getLogger(ApigeeAccessTokenIssuedListener.class);

    private final ApigeeEventListenerConfiguration _configuration;
    private final ExceptionFactory _exceptionFactory;
    private final WebServiceClient _client;

    public ApigeeAccessTokenIssuedListener(ApigeeEventListenerConfiguration configuration)
    {
        _configuration = configuration;
        _exceptionFactory = configuration.getExceptionFactory();
        _client = getWebServiceClient(configuration.getHost() + "/token");
    }

    @Override
    public Class<IssuedAccessTokenOAuthEvent> getEventType()
    {
        return IssuedAccessTokenOAuthEvent.class;
    }

    @Override
    public void handle(IssuedAccessTokenOAuthEvent event)
    {
        String accessTokenValue = event.getAccessTokenValue();
        String[] accessTokenParts = accessTokenValue.split("\\.");

        if (accessTokenParts.length != 3) {
            _logger.debug("The access token has unexpected format. Expected the token to have 3 parts but found {}.", accessTokenParts.length);
            return;
        }

        String signature = accessTokenParts[2];
        String tokenValue = accessTokenParts[0] + "." + accessTokenParts[1];

        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            _logger.warn("SHA-256 must be available in order to use the Apigee event listener");
            throw _exceptionFactory.internalServerException(ErrorCode.GENERIC_ERROR,
                    "SHA-256 must be available in order to use the Apigee event listener");
        }

        digest.update(signature.getBytes());
        String hashedSignature = Base64.getEncoder().encodeToString(digest.digest());

        Map<String, String> bodyParams = new HashMap<>(6);
        bodyParams.put("client_id", event.getClientId());
        bodyParams.put("scope", event.getScope());
        bodyParams.put("token", tokenValue);
        bodyParams.put("signatureHash", hashedSignature);
        bodyParams.put("grant_type", "client_credentials");
        bodyParams.put("expiration", String.valueOf(event.getExpires().getEpochSecond() - Instant.now().getEpochSecond()));

        HttpResponse response = _client
                .request()
                .contentType("application/x-www-form-urlencoded")
                .body(HttpRequest.createFormUrlEncodedBodyProcessor(bodyParams))
                .post()
                .response();

        if (response.statusCode() != 200) {
            _logger.warn("Event posted to Apigee but response was not successful: {}",
                    response.body(HttpResponse.asString()));
        } else {
            _logger.debug("Successfully sent event to Apigee token store: {}", event);
        }
    }

    private WebServiceClient getWebServiceClient(String uri) {
        WebServiceClientFactory factory = _configuration.getWebServiceClientFactory();

        HttpClient httpClient = _configuration.getHttpClient();
        URI u = URI.create(uri);

        String configuredScheme = httpClient.getScheme();
        String requiredScheme = u.getScheme();

        if (!Objects.equals(configuredScheme, requiredScheme)) {
            _logger.debug("HTTP client was configured with the scheme {} but {} was expected. Ensure that the " +
                    "configuration is correct.", configuredScheme, requiredScheme);

            throw _exceptionFactory.internalServerException(ErrorCode.CONFIGURATION_ERROR,
                    String.format("HTTP scheme of client is not acceptable; %s is required but %s was found",
                            requiredScheme, configuredScheme));
        }

        return factory.create(httpClient).withHost(u.getHost()).withPath(u.getPath());
    }
}