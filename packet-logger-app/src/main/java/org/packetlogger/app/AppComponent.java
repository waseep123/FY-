/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.packetlogger.app;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Application component that continuously sends "hello world" messages to an HTTP endpoint.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    private ApplicationId appId;
    private HttpClient httpClient;
    private ScheduledExecutorService scheduledExecutor;
    private static final String BASE_URL = "http://10.3.12.140:8000/url/";
    private static final int MESSAGE_INTERVAL_SECONDS = 1; // Send message every second

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("org.packetlogger.app");
        
        // Initialize HTTP client
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        
        // Start scheduled task to send hello world messages
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutor.scheduleAtFixedRate(
            this::sendHelloWorld,
            0, // start immediately
            MESSAGE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        
        log.info("Started hello world sender application - sending messages every {} second(s)",
                MESSAGE_INTERVAL_SECONDS);
    }

    @Deactivate
    protected void deactivate() {
        // Shutdown the scheduled executor
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
            scheduledExecutor = null;
        }
        
        log.info("Stopped hello world sender application");
    }

    /**
     * Sends a hello world message to the HTTP endpoint.
     */
    private void sendHelloWorld() {
        String jsonData = "{\"test\":\"hello world\"}";
        try {
            // URL encode the JSON data to be part of the URL path
            String encodedJson = URLEncoder.encode(jsonData, StandardCharsets.UTF_8.toString());
            String fullUrl = BASE_URL + encodedJson;
            
            log.info("Sending hello world to endpoint: {}", fullUrl);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .GET() // Use GET instead of POST since data is in URL
                .timeout(Duration.ofSeconds(5))
                .build();
            
            CompletableFuture<HttpResponse<String>> response = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofString());
                
            response.thenAccept(res -> {
                if (res.statusCode() == 200) {
                    log.info("Successfully sent hello world message. Response: {}", res.body());
                } else {
                    log.warn("Failed to send hello world message. Status code: {}, Response: {}",
                            res.statusCode(), res.body());
                }
            }).exceptionally(ex -> {
                log.error("Error sending HTTP request: {}", ex.getMessage());
                return null;
            });
        } catch (Exception e) {
            log.error("Error preparing HTTP request: {}", e.getMessage());
        }
    }
} 