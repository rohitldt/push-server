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

    public record ProviderResult(boolean success, String messageId, String error) {}
}


