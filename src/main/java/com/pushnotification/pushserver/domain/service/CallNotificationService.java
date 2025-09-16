package com.pushnotification.pushserver.domain.service;

import com.pushnotification.pushserver.application.dto.CallNotificationRequest;
import com.pushnotification.pushserver.domain.model.Pusher;
import com.pushnotification.pushserver.domain.repository.PusherRepository;
import com.pushnotification.pushserver.domain.repository.LocalCurrentMembershipRepository;
import com.pushnotification.pushserver.push.ApnsPushService;
import com.pushnotification.pushserver.push.FcmPushService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CallNotificationService {

    private final PusherRepository pusherRepository;
    private final LocalCurrentMembershipRepository membershipRepository;
    private final ApnsPushService apnsPushService;
    private final FcmPushService fcmPushService;
    private static final Logger log = LoggerFactory.getLogger(CallNotificationService.class);

    public void sendIncomingCallNotification(CallNotificationRequest request) {
        String title = "Incoming " + request.getCallType() + " call";
        String body = request.getSenderId() + " is calling";
        Map<String, String> data = Map.of(
                "type", "call",
                "callType", request.getCallType(),
                "roomId", request.getRoomId(),
                "senderId", request.getSenderId()
        );

        // HARDCODED FCM TOKEN FOR TESTING - Replace with your actual FCM token
        String testFcmToken = "fCTcLQ0mRse2lpxwWVtsQz:APA91bFde8EuIvdM2Fegw-J_pUtdUOzRl6GxkiPdb2PY7eytHUmYfAKdDblfyPC4UK6Eszw2yZODtBQd6S7x-JqRtTS8I9Q7WYk8uQGOGKpgKiqv06mpjYA"; // Replace with your Android FCM token
        
        log.info("TEST MODE: Using hardcoded FCM token for testing");
        log.info("FCM Token: {}", testFcmToken.length() > 10 ? testFcmToken.substring(0, 10) + "..." : testFcmToken);

        // Send to hardcoded FCM token for testing
        if (testFcmToken != null && !testFcmToken.equals("fCTcLQ0mRse2lpxwWVtsQz:APA91bFde8EuIvdM2Fegw-J_pUtdUOzRl6GxkiPdb2PY7eytHUmYfAKdDblfyPC4UK6Eszw2yZODtBQd6S7x-JqRtTS8I9Q7WYk8uQGOGKpgKiqv06mpjYA") && !testFcmToken.trim().isEmpty()) {
            log.info("Sending FCM notification to hardcoded Android device");
            fcmPushService.send(testFcmToken, title, body, data).join();
        } else {
            log.warn("No FCM test token configured! Please replace YOUR_FCM_TOKEN_HERE with actual FCM token");
        }

        /* ORIGINAL DATABASE LOGIC - COMMENTED OUT FOR TESTING
        log.info("Resolving members for roomId={}", request.getRoomId());
        String senderMxid = request.getSenderId();
        List<String> roomMembers = membershipRepository.findByRoomIdAndMembership(request.getRoomId(), "join")
                .stream()
                .map(m -> m.getUserId())
                .filter(u -> !u.equals(senderMxid))
                .distinct()
                .collect(Collectors.toList());
        log.info("Room members to notify (excluding sender): {}", roomMembers);

        List<Pusher> pushers = List.of();
        if (!roomMembers.isEmpty()) {
            // Try exact MXIDs first
            pushers = pusherRepository.findByUserNameIn(roomMembers);
            if (pushers.isEmpty()) {
                // Fallback: try matching on localparts if pushers store localpart only
                List<String> memberLocals = roomMembers.stream()
                        .map(this::extractLocalpart)
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .collect(Collectors.toList());
                if (!memberLocals.isEmpty()) {
                    pushers = pusherRepository.findByUserNameIn(memberLocals);
                }
            }
        }
        log.info("Loaded pushers: count={} (members={}, triedLocalsFallback={})", pushers.size(), roomMembers.size(), pushers.isEmpty() ? "yes" : "no");
        List<CompletableFuture<?>> futures = pushers.stream()
                .filter(p -> !request.getSenderId().equals(p.getUserName()))
                .map(p -> {
                    String token = p.getPushkey();
                    boolean ios = isIosPusher(p);
                    log.info("Sending to user={}, appId={}, platform={}, tokenPrefix={}", p.getUserName(), p.getAppId(), ios ? "iOS" : "Android", token != null && token.length() > 6 ? token.substring(0,6) : token);
                    if (ios) {
                        return apnsPushService.send(token, title, body, data);
                    } else {
                        return fcmPushService.send(token, title, body, data);
                    }
                })
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("Completed sends for roomId={} recipients={} futures={} ", request.getRoomId(), roomMembers.size(), futures.size());
        */
    }

    private String extractLocalpart(String mxid) {
        if (mxid == null) return null;
        // Expect format @local:domain
        int at = mxid.indexOf('@');
        int colon = mxid.indexOf(':');
        if (colon > -1) {
            return mxid.substring(at == -1 ? 0 : at + 1, colon);
        }
        // If no domain part, return as-is (assume already localpart)
        return mxid.startsWith("@") ? mxid.substring(1) : mxid;
    }

    private boolean isIosPusher(Pusher pusher) {
        // Hard-map app IDs to platforms; fallback to token shape only if appId unknown
        String appId = pusher.getAppId();
        if (appId != null) {
            if ("com.parezaapp.app.ios.prod".equals(appId)) {
                return true; // iOS
            }
            if ("com.pareza.pro".equals(appId)) {
                return false; // Android
            }
        }
        String token = pusher.getPushkey();
        return token != null && token.matches("[a-fA-F0-9]{64}");

    }
}


