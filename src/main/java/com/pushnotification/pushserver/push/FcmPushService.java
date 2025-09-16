package com.pushnotification.pushserver.push;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class FcmPushService {

    private final FirebaseMessaging firebaseMessaging;
    private static final Logger log = LoggerFactory.getLogger(FcmPushService.class);

    public CompletableFuture<ProviderResult> send(String deviceToken, String title, String body, Map<String, String> data) {
        Message.Builder builder = Message.builder()
                .setToken(deviceToken)
                .putAllData(data != null ? data : Map.of());

        AndroidConfig androidConfig = AndroidConfig.builder().build();
        builder.setAndroidConfig(androidConfig);

        ApnsConfig apnsConfig = ApnsConfig.builder()
                .setAps(Aps.builder().setAlert(ApsAlert.builder().setTitle(title).setBody(body).build()).build())
                .build();
        builder.setApnsConfig(apnsConfig);

        String rawToken = deviceToken;
        String trimmedToken = deviceToken != null ? deviceToken.trim() : null;
        boolean whitespaceTrimmed = rawToken != null && !rawToken.equals(trimmedToken);
        String tokenPreview = rawToken != null && rawToken.length() > 8 ? rawToken.substring(0, 8) + "…" : rawToken;
        log.info("FCM sending: token={}, title='{}', dataKeys={}", tokenPreview, title, (data != null ? data.keySet() : java.util.Set.of()));
        if (data != null && !data.isEmpty()) {
            log.info("FCM data payload: {}", data);
        } else {
            log.info("FCM data payload: {}", "{}");
        }
        log.info("FCM token diagnostics: rawLen={}, trimmedLen={}, whitespaceTrimmed={}, startsWithSpace={}, endsWithSpace={}",
                rawToken == null ? 0 : rawToken.length(),
                trimmedToken == null ? 0 : trimmedToken.length(),
                whitespaceTrimmed,
                rawToken != null && !rawToken.isEmpty() && Character.isWhitespace(rawToken.charAt(0)),
                rawToken != null && !rawToken.isEmpty() && Character.isWhitespace(rawToken.charAt(rawToken.length()-1))
        );

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("FCM attempting to send notification...");
                String id = firebaseMessaging.send(builder.build());
                log.info("🎉 FCM SENT SUCCESSFULLY: messageId={}, token={}", id, tokenPreview);
                return new ProviderResult(true, id, null);
            } catch (Exception ex) {
                log.error("💥 FCM SEND FAILED: token={}, error={}, details={}, stackTrace={}", 
                    tokenPreview, ex.getMessage(), ex.getClass().getSimpleName(), ex.getStackTrace()[0]);
                return new ProviderResult(false, null, ex.getMessage());
            }
        });
    }

    public record ProviderResult(boolean success, String messageId, String error) {}
}


