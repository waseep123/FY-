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

import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP;
import org.onlab.packet.IPv4;
import org.onlab.packet.IPv6;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onlab.packet.TCP;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
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
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Application component that logs packet-in events to ONOS logs.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    private ApplicationId appId;
    private HttpClient httpClient;
    private ScheduledExecutorService scheduledExecutor;
    private final PacketProcessor packetProcessor = new InternalPacketProcessor();
    private static final String BASE_URL = "http://10.3.12.140:8000/url/";
    private static final int MESSAGE_INTERVAL_SECONDS = 10; // Send message every 10 seconds

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("org.packetlogger.app");
        
        // Register packet processor for all packets
        packetService.addProcessor(packetProcessor, PacketProcessor.director(2));
        
        // Initialize HTTP client
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        
        // Start scheduled task to send status messages
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutor.scheduleAtFixedRate(
            this::sendStatusMessage,
            0, // start immediately
            MESSAGE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        
        log.info("Started packet logger application");
    }

    @Deactivate
    protected void deactivate() {
        // Unregister packet processor
        packetService.removeProcessor(packetProcessor);
        
        // Shutdown the scheduled executor
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
            scheduledExecutor = null;
        }
        
        log.info("Stopped packet logger application");
    }

    /**
     * Sends a status message to the HTTP endpoint.
     */
    private void sendStatusMessage() {
        String jsonData = "{\"status\":\"active\",\"app\":\"packet-logger\"}";
        try {
            // URL encode the JSON data to be part of the URL path
            String encodedJson = URLEncoder.encode(jsonData, StandardCharsets.UTF_8.toString());
            String fullUrl = BASE_URL + encodedJson;
            
            log.debug("Sending status to endpoint: {}", fullUrl);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .GET() // Use GET instead of POST since data is in URL
                .timeout(Duration.ofSeconds(5))
                .build();
            
            CompletableFuture<HttpResponse<String>> response = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofString());
                
            response.thenAccept(res -> {
                if (res.statusCode() == 200) {
                    log.debug("Successfully sent status message. Response: {}", res.body());
                } else {
                    log.warn("Failed to send status message. Status code: {}, Response: {}",
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
    
    /**
     * Packet processor implementation to log packet-ins.
     */
    private class InternalPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            
            Ethernet eth = context.inPacket().parsed();
            if (eth == null) {
                return;
            }
            
            StringBuilder logMessage = new StringBuilder();
            logMessage.append("PACKET-IN: Device=").append(context.inPacket().receivedFrom().deviceId())
                    .append(", Port=").append(context.inPacket().receivedFrom().port())
                    .append(", Eth.src=").append(eth.getSourceMAC())
                    .append(", Eth.dst=").append(eth.getDestinationMAC())
                    .append(", Eth.type=0x").append(Integer.toHexString(eth.getEtherType() & 0xFFFF));
            
            // Build JSON data for endpoint
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{\"type\":\"packet-in\"")
                    .append(",\"device\":\"").append(context.inPacket().receivedFrom().deviceId()).append("\"")
                    .append(",\"port\":\"").append(context.inPacket().receivedFrom().port()).append("\"")
                    .append(",\"eth_src\":\"").append(eth.getSourceMAC()).append("\"")
                    .append(",\"eth_dst\":\"").append(eth.getDestinationMAC()).append("\"")
                    .append(",\"eth_type\":\"0x").append(Integer.toHexString(eth.getEtherType() & 0xFFFF)).append("\"");
            
            // Get detailed packet info based on the packet type
            int etherType = eth.getEtherType();
            if (etherType == Ethernet.TYPE_IPV4) {
                IPv4 ipv4 = (IPv4) eth.getPayload();
                logMessage.append(", IPv4.src=").append(ipv4.getSourceAddress())
                        .append(", IPv4.dst=").append(ipv4.getDestinationAddress())
                        .append(", IPv4.proto=").append(ipv4.getProtocol());
                
                jsonBuilder.append(",\"ipv4_src\":\"").append(ipv4.getSourceAddress()).append("\"")
                        .append(",\"ipv4_dst\":\"").append(ipv4.getDestinationAddress()).append("\"")
                        .append(",\"ip_proto\":").append(ipv4.getProtocol());
                
                if (ipv4.getProtocol() == IPv4.PROTOCOL_ICMP) {
                    ICMP icmp = (ICMP) ipv4.getPayload();
                    logMessage.append(", ICMP.type=").append(icmp.getIcmpType())
                            .append(", ICMP.code=").append(icmp.getIcmpCode());
                    
                    jsonBuilder.append(",\"protocol\":\"icmp\"")
                            .append(",\"icmp_type\":").append(icmp.getIcmpType())
                            .append(",\"icmp_code\":").append(icmp.getIcmpCode());
                } else if (ipv4.getProtocol() == IPv4.PROTOCOL_TCP) {
                    TCP tcp = (TCP) ipv4.getPayload();
                    logMessage.append(", TCP.src=").append(tcp.getSourcePort())
                            .append(", TCP.dst=").append(tcp.getDestinationPort());
                    
                    jsonBuilder.append(",\"protocol\":\"tcp\"")
                            .append(",\"tcp_src\":").append(tcp.getSourcePort())
                            .append(",\"tcp_dst\":").append(tcp.getDestinationPort());
                } else if (ipv4.getProtocol() == IPv4.PROTOCOL_UDP) {
                    UDP udp = (UDP) ipv4.getPayload();
                    logMessage.append(", UDP.src=").append(udp.getSourcePort())
                            .append(", UDP.dst=").append(udp.getDestinationPort());
                    
                    jsonBuilder.append(",\"protocol\":\"udp\"")
                            .append(",\"udp_src\":").append(udp.getSourcePort())
                            .append(",\"udp_dst\":").append(udp.getDestinationPort());
                }
            } else if (etherType == Ethernet.TYPE_IPV6) {
                IPv6 ipv6 = (IPv6) eth.getPayload();
                logMessage.append(", IPv6.src=").append(Arrays.toString(ipv6.getSourceAddress()))
                        .append(", IPv6.dst=").append(Arrays.toString(ipv6.getDestinationAddress()))
                        .append(", IPv6.nextHdr=").append(ipv6.getNextHeader());
                
                jsonBuilder.append(",\"protocol\":\"ipv6\"")
                        .append(",\"ipv6_src\":\"").append(Arrays.toString(ipv6.getSourceAddress())).append("\"")
                        .append(",\"ipv6_dst\":\"").append(Arrays.toString(ipv6.getDestinationAddress())).append("\"")
                        .append(",\"ipv6_next_hdr\":").append(ipv6.getNextHeader());
            } else if (etherType == Ethernet.TYPE_ARP) {
                ARP arp = (ARP) eth.getPayload();
                logMessage.append(", ARP.op=").append(arp.getOpCode())
                        .append(", ARP.srcIP=").append(Arrays.toString(arp.getSenderProtocolAddress()))
                        .append(", ARP.tgtIP=").append(Arrays.toString(arp.getTargetProtocolAddress()));
                
                jsonBuilder.append(",\"protocol\":\"arp\"")
                        .append(",\"arp_op\":").append(arp.getOpCode())
                        .append(",\"arp_src_ip\":\"").append(Arrays.toString(arp.getSenderProtocolAddress())).append("\"")
                        .append(",\"arp_tgt_ip\":\"").append(Arrays.toString(arp.getTargetProtocolAddress())).append("\"");
            } else {
                // Unknown payload type
                logMessage.append(", Unknown payload type");
                jsonBuilder.append(",\"protocol\":\"unknown\"");
            }
            
            jsonBuilder.append("}");
            
            // Log the packet information
            log.info(logMessage.toString());
            
            // Send to endpoint
            sendPacketToEndpoint(jsonBuilder.toString());
        }
    }
    
    /**
     * Sends packet data to the HTTP endpoint.
     */
    private void sendPacketToEndpoint(String jsonData) {
        try {
            // URL encode the JSON data to be part of the URL path
            String encodedJson = URLEncoder.encode(jsonData, StandardCharsets.UTF_8.toString());
            String fullUrl = BASE_URL + encodedJson;
            
            log.debug("Sending packet data to endpoint: {}", fullUrl);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .GET() // Use GET instead of POST since data is in URL
                .timeout(Duration.ofSeconds(5))
                .build();
            
            CompletableFuture<HttpResponse<String>> response = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofString());
                
            response.thenAccept(res -> {
                if (res.statusCode() == 200) {
                    log.debug("Successfully sent packet data. Response: {}", res.body());
                } else {
                    log.warn("Failed to send packet data. Status code: {}, Response: {}",
                            res.statusCode(), res.body());
                }
            }).exceptionally(ex -> {
                log.error("Error sending packet data HTTP request: {}", ex.getMessage());
                return null;
            });
        } catch (Exception e) {
            log.error("Error preparing packet data HTTP request: {}", e.getMessage());
        }
    }
} 