package com.pushnotification.pushserver.push;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class ApnsPushService {

    private final ApnsClient apnsClient;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(ApnsPushService.class);

    @Value("${apns.topic:}")
    private String apnsTopic;

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
        String tokenPreview = deviceToken != null && deviceToken.length() > 8 ? deviceToken.substring(0, 8) + "…" : deviceToken;
        log.info("APNs sending: token={}, topic={}, title='{}', dataKeys={}", tokenPreview, topic, title, (data != null ? data.keySet() : java.util.Set.of()));
        SimpleApnsPushNotification notification = new SimpleApnsPushNotification(deviceToken, topic, payload);

        CompletableFuture<ProviderResult> promise = new CompletableFuture<>();
        apnsClient.sendNotification(notification).whenComplete((response, cause) -> {
            if (cause == null) {
                if (response.isAccepted()) {
                    String apnsId = response.getApnsId() != null ? response.getApnsId().toString() : null;
                    log.info("APNs accepted: apnsId={}", apnsId);
                    promise.complete(new ProviderResult(true, apnsId, null));
                } else {
                    String reason = response.getRejectionReason() != null ? response.getRejectionReason().orElse(null) : null;
                    log.warn("APNs rejected: reason={}", reason);
                    promise.complete(new ProviderResult(false, null, reason));
                }
            } else {
                log.error("APNs send failed: {}", cause.getMessage());
                promise.complete(new ProviderResult(false, null, cause.getMessage()));
            }
        });
        return promise;
    }

    public record ProviderResult(boolean success, String messageId, String error) {}
}


