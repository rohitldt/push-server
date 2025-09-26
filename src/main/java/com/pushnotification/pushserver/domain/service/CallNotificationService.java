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
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.Base64;


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
        Map<String, String> data = new HashMap<>(Map.of(
//                "event_id", request.getEventId(),
                "room_id", request.getRoomId(),
//                "unread", "1",
//                "prio", "high",
//                "cs", "call-secret",
                "type", "call", "callType", request.getCallType(), "senderId", request.getSenderId()
        ));
        if (request.getReject() != null) {
            data.put("reject", String.valueOf(request.getReject()));
        }

        log.info("Resolving members for roomId={}", request.getRoomId());
        String senderMxid = request.getSenderId();
        List<String> roomMembers = membershipRepository.findByRoomIdAndMembership(request.getRoomId(), "join").stream().map(m -> m.getUserId()).filter(u -> !u.equals(senderMxid)).distinct().collect(Collectors.toList());
        log.info("Room members to notify (excluding sender): {}", roomMembers);

        List<Pusher> pushers = List.of();
        if (!roomMembers.isEmpty()) {
            // Try exact MXIDs first
            pushers = pusherRepository.findByUserNameIn(roomMembers);
            if (pushers.isEmpty()) {
                // Fallback: try matching on localparts if pushers store localpart only
                List<String> memberLocals = roomMembers.stream().map(this::extractLocalpart).filter(s -> s != null && !s.isBlank()).distinct().collect(Collectors.toList());
                if (!memberLocals.isEmpty()) {
                    pushers = pusherRepository.findByUserNameIn(memberLocals);
                }
            }
        }

        log.info("Loaded pushers: count={} (members={}, triedLocalsFallback={})", pushers.size(), roomMembers.size(), pushers.isEmpty() ? "yes" : "no");
        
        // Filter for VoIP app ID only
        List<CompletableFuture<?>> futures = pushers.stream()
            .filter(p -> !request.getSenderId().equals(p.getUserName()))
            .filter(p -> "com.pareza.pro.ios.voip".equals(p.getAppId())) // Only VoIP app
            .map(p -> {
            String token = p.getPushkey();
            boolean ios = isIosPusher(p);

            System.out.println("the app id============>>>>>>>>>>>>>" + p.getAppId());
            System.out.println("the boolean is=============>>>>>>>>>>" + ios);

            String trimmed = token != null ? token.trim() : null;
            boolean whitespaceTrimmed = token != null && !token.equals(trimmed);
            log.info("Sending to user={}, appId={}, platform={}, tokenPrefix={}", p.getUserName(), p.getAppId(), ios ? "iOS" : "Android", token != null && token.length() > 6 ? token.substring(0, 6) : token);
            log.info("Token diagnostics: rawLen={}, trimmedLen={}, whitespaceTrimmed={}, startsWithSpace={}, endsWithSpace={}", token == null ? 0 : token.length(), trimmed == null ? 0 : trimmed.length(), whitespaceTrimmed, token != null && !token.isEmpty() && Character.isWhitespace(token.charAt(0)), token != null && !token.isEmpty() && Character.isWhitespace(token.charAt(token.length() - 1)));
            if (ios) {
                // Send VoIP push for all iOS apps (calling only)
                String iosTokenHex = normalizeIosToken(token);
                return apnsPushService.sendVoip(iosTokenHex, data).thenAccept(result -> {
                    if (result.success()) {
                        log.info("✅ APNS VoIP SUCCESS for user={}: ApnsId={}", p.getUserName(), result.messageId());
                    } else {
                        log.error("❌ APNS VoIP FAILED for user={}: Error={}", p.getUserName(), result.error());
                    }
                });
            } else {
                // Send FCM for Android
                return fcmPushService.send(token, title, body, data).thenAccept(result -> {
                    if (result.success()) {
                        log.info("✅ FCM SUCCESS for user={}: MessageId={}", p.getUserName(), result.messageId());
                    } else {
                        log.error("❌ FCM FAILED for user={}: Error={}", p.getUserName(), result.error());
                    }
                });
            }
        }).collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("Completed sends for roomId={} recipients={} futures={} ", request.getRoomId(), roomMembers.size(), futures.size());
    }


    public String tokenConverter(String base64Token) {
        try {
            // Decode Base64 token to raw bytes
            byte[] tokenBytes = Base64.getDecoder().decode(base64Token);

            // Convert to hex string for APNs
            return bytesToHex(tokenBytes);

        } catch (IllegalArgumentException e) {
            log.error("Invalid Base64 token: {}", base64Token);
            return null; // or throw exception
        }
    }


    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private String normalizeIosToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return rawToken;
        }
        String trimmed = rawToken.trim();
        // If it's already hex (PushKit typical), return as-is
        if (trimmed.matches("[a-fA-F0-9]{64}")) {
            return trimmed;
        }
        // Otherwise attempt to treat it as base64 and convert to hex for APNs
        try {
            return tokenConverter(trimmed);
        } catch (Exception e) {
            log.warn("iOS token did not match hex or valid base64; using raw as-is");
            return trimmed;
        }
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
            if ("com.pareza.pro.ios.prod".equals(appId) || "com.pareza.pro.ios.dev".equals(appId) || appId.startsWith("com.pareza.pro.ios.")) {
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


