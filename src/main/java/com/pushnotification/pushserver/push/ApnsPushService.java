package com.pushnotification.pushserver.push;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.PushType;
import com.eatthepath.pushy.apns.DeliveryPriority;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ApnsPushService {

    private final ApnsClient apnsClient;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(ApnsPushService.class);

    @Value("${apns.topic:}")
    private String apnsTopic;
    @Value("${apns.voip-topic:}")
    private String apnsVoipTopic;

    public CompletableFuture<ProviderResult> send(String deviceToken, String title, String body, Map<String, String> data) {
        String payload;
        try {
            Map<String, Object> root = new HashMap<>();
            Map<String, Object> aps = new HashMap<>();
            Map<String, Object> alert = new HashMap<>();
            alert.put("title", title);
            alert.put("body", body);
            aps.put("alert", alert);
            aps.put("sound", "default");
            root.put("aps", aps);
            if (data != null && !data.isEmpty()) {
                root.putAll(data);
            }
            payload = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            CompletableFuture<ProviderResult> failed = new CompletableFuture<>();
            failed.complete(new ProviderResult(false, null, "Failed to build APNs payload: " + e.getMessage()));
            return failed;
        }
        String topic = (apnsTopic != null && !apnsTopic.isBlank()) ? apnsTopic : null;
        String rawToken = deviceToken;
        String trimmedToken = deviceToken != null ? deviceToken.trim() : null;
        boolean whitespaceTrimmed = rawToken != null && !rawToken.equals(trimmedToken);
        String tokenPreview = rawToken != null && rawToken.length() > 8 ? rawToken.substring(0, 8) + "…" : rawToken;
        log.info("APNs sending: token={}, topic={}, title='{}', dataKeys={}", tokenPreview, topic, title, (data != null ? data.keySet() : java.util.Set.of()));
        if (data != null && !data.isEmpty()) {
            log.info("APNs data payload: {}", data);
        } else {
            log.info("APNs data payload: {}", "{}");
        }
        log.info("APNs token diagnostics: rawLen={}, trimmedLen={}, whitespaceTrimmed={}, startsWithSpace={}, endsWithSpace={}",
                rawToken == null ? 0 : rawToken.length(),
                trimmedToken == null ? 0 : trimmedToken.length(),
                whitespaceTrimmed,
                rawToken != null && !rawToken.isEmpty() && Character.isWhitespace(rawToken.charAt(0)),
                rawToken != null && !rawToken.isEmpty() && Character.isWhitespace(rawToken.charAt(rawToken.length()-1))
        );
        SimpleApnsPushNotification notification = new SimpleApnsPushNotification(deviceToken, topic, payload);

        CompletableFuture<ProviderResult> promise = new CompletableFuture<>();
        apnsClient.sendNotification(notification).whenComplete((response, cause) -> {
            if (cause == null) {
                if (response.isAccepted()) {
                    String apnsId = response.getApnsId() != null ? response.getApnsId().toString() : null;
                    log.info("APNs accepted: apnsId={}, token={}", apnsId, tokenPreview);
                    promise.complete(new ProviderResult(true, apnsId, null));
                } else {
                    String reason = response.getRejectionReason() != null ? response.getRejectionReason().orElse(null) : null;
                    log.warn("APNs rejected: token={}, reason={}", tokenPreview, reason);
                    promise.complete(new ProviderResult(false, null, reason));
                }
            } else {
                log.error("APNs send failed: token={}, error={}, details={}", tokenPreview, cause.getMessage(), cause.getClass().getSimpleName());
                promise.complete(new ProviderResult(false, null, cause.getMessage()));
            }
        });
        return promise;
    }

    public CompletableFuture<ProviderResult> sendVoip(String deviceTokenHex, Map<String, String> data) {
        String payload;
        try {
            Map<String, Object> root = new HashMap<>();
            Map<String, Object> aps = new HashMap<>();
            aps.put("content-available", 1);
            root.put("aps", aps);
            if (data != null && !data.isEmpty()) {
                root.putAll(data);
            }
            payload = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            CompletableFuture<ProviderResult> failed = new CompletableFuture<>();
            failed.complete(new ProviderResult(false, null, "Failed to build APNs VoIP payload: " + e.getMessage()));
            return failed;
        }

        String topic = (apnsVoipTopic != null && !apnsVoipTopic.isBlank()) ? apnsVoipTopic : null;
        log.info("APNs VoIP sending: token={}, topic={}, dataKeys={}", deviceTokenHex, topic, (data != null ? data.keySet() : java.util.Set.of()));

        // Build a notification with VOIP push type, immediate priority and short expiration
        SimpleApnsPushNotification notification = new SimpleApnsPushNotification(
                deviceTokenHex, 
                topic, 
                payload,
                Instant.now().plusSeconds(30), // expiration
                DeliveryPriority.IMMEDIATE, // priority
                PushType.VOIP // push type
        );

        CompletableFuture<ProviderResult> promise = new CompletableFuture<>();
        apnsClient.sendNotification(notification).whenComplete((response, cause) -> {
            if (cause == null) {
                if (response.isAccepted()) {
                    String apnsId = response.getApnsId() != null ? response.getApnsId().toString() : null;
                    log.info("APNs VoIP accepted: apnsId={}, token={}", apnsId, deviceTokenHex);
                    promise.complete(new ProviderResult(true, apnsId, null));
                } else {
                    String reason = response.getRejectionReason() != null ? response.getRejectionReason().orElse(null) : null;
                    log.warn("APNs VoIP rejected: token={}, reason={}", deviceTokenHex, reason);
                    promise.complete(new ProviderResult(false, null, reason));
                }
            } else {
                log.error("APNs VoIP send failed: token={}, error={}, details={}", deviceTokenHex, cause.getMessage(), cause.getClass().getSimpleName());
                promise.complete(new ProviderResult(false, null, cause.getMessage()));
            }
        });
        return promise;
    }

    public record ProviderResult(boolean success, String messageId, String error) {}
}


