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

        String tokenPreview = deviceToken != null && deviceToken.length() > 8 ? deviceToken.substring(0, 8) + "…" : deviceToken;
        log.info("FCM sending: token={}, title='{}', dataKeys={}", tokenPreview, title, (data != null ? data.keySet() : java.util.Set.of()));

        return CompletableFuture.supplyAsync(() -> {
            try {
                String id = firebaseMessaging.send(builder.build());
                log.info("FCM sent: messageId={}", id);
                return new ProviderResult(true, id, null);
            } catch (Exception ex) {
                log.error("FCM send failed: {}", ex.getMessage());
                return new ProviderResult(false, null, ex.getMessage());
            }
        });
    }

    public record ProviderResult(boolean success, String messageId, String error) {}
}


