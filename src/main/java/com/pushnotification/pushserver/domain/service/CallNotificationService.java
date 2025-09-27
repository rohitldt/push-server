package com.pushnotification.pushserver.domain.service;

import com.pushnotification.pushserver.application.dto.CallNotificationRequest;
import com.pushnotification.pushserver.domain.model.Pusher;
import com.pushnotification.pushserver.domain.repository.PusherRepository;
import com.pushnotification.pushserver.domain.repository.LocalCurrentMembershipRepository;
import com.pushnotification.pushserver.push.ApnsPushService;
import com.pushnotification.pushserver.push.FcmPushService;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.Base64;
import java.sql.ResultSet;
import java.sql.SQLException;


@Service
@RequiredArgsConstructor
public class CallNotificationService {

    private final PusherRepository pusherRepository;
    private final LocalCurrentMembershipRepository membershipRepository;
    private final ApnsPushService apnsPushService;
    private final FcmPushService fcmPushService;
    private final JdbcTemplate jdbcTemplate;
    private static final Logger log = LoggerFactory.getLogger(CallNotificationService.class);


    public void sendIncomingCallNotification(CallNotificationRequest request) {
        // Check if the calling user (sender) is in a group room or direct message
        String roomType = getUserRoomType(request.getRoomId());
        boolean isGroupCall = "group_room".equals(roomType);
        
        String groupName = null;
        String notificationTitle;
        String notificationBody;
        
        if (isGroupCall) {
            // It's a group call - get group name
            groupName = getGroupName(request.getRoomId());
            notificationTitle = "Incoming " + request.getCallType() + " call in " + (groupName != null ? groupName : "group");
            notificationBody = request.getSenderId() + " is calling";
            log.info("Group call detected for roomId={}, groupName={}, sender={}", request.getRoomId(), groupName, request.getSenderId());
        } else {
            // It's a direct message - use sender name
            String senderName = getSenderDisplayName(request.getSenderId());
            notificationTitle = "Incoming " + request.getCallType() + " call";
            notificationBody = senderName + " is calling";
            log.info("Direct call detected for roomId={}, sender={}, senderName={}", request.getRoomId(), request.getSenderId(), senderName);
        }

        Map<String, String> data = new HashMap<>();
        // Mandatory keys
        if (request.getRoomId() != null) {
            data.put("roomId", request.getRoomId());
            data.put("room_id", request.getRoomId());
        }
        data.put("type", "call");
        // Optional keys (avoid Map.of NPE on nulls)
        if (request.getCallType() != null) {
            data.put("callType", request.getCallType());
        }
        if (request.getSenderId() != null) {
            data.put("senderId", request.getSenderId());
        }
        if (request.getSenderName() != null) {
            data.put("senderName", request.getSenderName());
        }
        log.info("VoIP data map to send (pre-APNs): {}", data);

        // Proceed with normal DB/Android/iOS logic below
        if (request.getReject() != null) {
            data.put("reject", String.valueOf(request.getReject()));
        }

        // Add group information if it's a group call
        if (isGroupCall) {
            data.put("isGroupCall", "true");
            if (groupName != null) {
                data.put("groupName", groupName);
            }
            // Also add the group name from request if provided
            if (request.getGroupName() != null) {
                data.put("groupName", request.getGroupName());
            }
        } else {
            data.put("isGroupCall", "false");
        }

        log.info("Resolving members for roomId={}, roomType={}", request.getRoomId(), roomType);
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
        
        // Log all pushers before filtering
        log.info("All pushers found:");
        pushers.forEach(p -> log.info("  - user={}, appId={}, hasToken={}", 
            p.getUserName(), p.getAppId(), p.getPushkey() != null && !p.getPushkey().isBlank()));
        
        // Filter for both VoIP (iOS) and FCM (Android) app IDs
        List<Pusher> voipPushers = pushers.stream()
            .filter(p -> !request.getSenderId().equals(p.getUserName()))
            .filter(p -> {
                String appId = p.getAppId();
                return appId != null && (
                        "com.pareza.pro.ios.prod.voip".equals(appId) ||
                        "com.pareza.pro.ios.dev.voip".equals(appId)
                );
            })
            .collect(Collectors.toList());
            
        List<Pusher> androidPushers = pushers.stream()
            .filter(p -> !request.getSenderId().equals(p.getUserName()))
            .filter(p -> "com.pareza.pro".equals(p.getAppId()))
            .collect(Collectors.toList());
            
        log.info("VoIP pushers (iOS): count={}", voipPushers.size());
        voipPushers.forEach(p -> log.info("  - user={}, appId={}, token={}", 
            p.getUserName(), p.getAppId(), 
            p.getPushkey() != null ? p.getPushkey() : "NULL"));
            
        log.info("Android pushers: count={}", androidPushers.size());
        androidPushers.forEach(p -> log.info("  - user={}, appId={}, token={}", 
            p.getUserName(), p.getAppId(), 
            p.getPushkey() != null ? p.getPushkey() : "NULL"));
        
        if (voipPushers.isEmpty() && androidPushers.isEmpty()) {
            log.warn("⚠️ NO PUSHERS FOUND! Check if users have registered with appId=com.pareza.pro.ios.voip or com.pareza.pro");
        }
        
        // Process VoIP pushers (iOS)
        List<CompletableFuture<?>> voipFutures = voipPushers.stream().map(p -> {
            String token = p.getPushkey();

            System.out.println("the app id============>>>>>>>>>>>>>" + p.getAppId());

            String trimmed = token != null ? token.trim() : null;
            boolean whitespaceTrimmed = token != null && !token.equals(trimmed);
            log.info("Sending to user={}, appId={}, platform=iOS, token={}", p.getUserName(), p.getAppId(), token);
            log.info("Token diagnostics: rawLen={}, trimmedLen={}, whitespaceTrimmed={}, startsWithSpace={}, endsWithSpace={}", token == null ? 0 : token.length(), trimmed == null ? 0 : trimmed.length(), whitespaceTrimmed, token != null && !token.isEmpty() && Character.isWhitespace(token.charAt(0)), token != null && !token.isEmpty() && Character.isWhitespace(token.charAt(token.length() - 1)));
            
            // Since we filtered for com.pareza.pro.ios.voip, this is always iOS VoIP
            String iosTokenHex = normalizeIosToken(token);
            return apnsPushService.sendVoip(iosTokenHex, data).thenAccept(result -> {
                if (result.success()) {
                    log.info("✅ APNS VoIP SUCCESS for user={}: ApnsId={}", p.getUserName(), result.messageId());
                } else {
                    log.error("❌ APNS VoIP FAILED for user={}: Error={}", p.getUserName(), result.error());
                }
            });
        }).collect(Collectors.toList());
        
        // Process Android pushers (FCM)
        List<CompletableFuture<?>> androidFutures = androidPushers.stream().map(p -> {
            String token = p.getPushkey();
            
            log.info("Sending to user={}, appId={}, platform=Android, token={}", p.getUserName(), p.getAppId(), token);
            
            return fcmPushService.send(token, notificationTitle, notificationBody, data).thenAccept(result -> {
                if (result.success()) {
                    log.info("✅ FCM SUCCESS for user={}: MessageId={}", p.getUserName(), result.messageId());
                } else {
                    log.error("❌ FCM FAILED for user={}: Error={}", p.getUserName(), result.error());
                }
            });
        }).collect(Collectors.toList());
        
        // Combine all futures
        List<CompletableFuture<?>> allFutures = new ArrayList<>();
        allFutures.addAll(voipFutures);
        allFutures.addAll(androidFutures);

        CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();
        log.info("Completed sends for roomId={} totalMembers={} voipPushers={} androidPushers={} totalFutures={}", 
            request.getRoomId(), roomMembers.size(), voipPushers.size(), androidPushers.size(), allFutures.size());
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

    /**
     * Analyzes room types for a specific user to determine if they are in group rooms or direct messages
     * @param userId The user ID to analyze (e.g., "@+919306273742:server.pareza.im")
     * @return List of room analysis results
     */
    public List<RoomAnalysis> analyzeUserRooms(String userId) {
        String sql = """
            SELECT 
                r.room_id,
                r.creator,
                rsc.joined_members,
                CASE 
                    WHEN ad.content IS NOT NULL THEN 'direct_message'
                    WHEN rsc.joined_members <= 2 THEN 'likely_dm'
                    ELSE 'group_room'
                END AS room_type
            FROM rooms r
            LEFT JOIN room_stats_current rsc ON r.room_id = rsc.room_id
            LEFT JOIN account_data ad ON ad.user_id = ? 
                AND ad.account_data_type = 'm.direct'
                AND r.room_id = ANY (
                    SELECT jsonb_array_elements_text(ad.content::jsonb)
                )
            WHERE r.room_id IN (
                SELECT DISTINCT room_id 
                FROM room_memberships 
                WHERE user_id = ? 
                  AND membership = 'join'
            )
            """;

        return jdbcTemplate.query(sql, new Object[]{userId, userId}, this::mapRowToRoomAnalysis);
    }

    /**
     * Gets a summary of room types for a user
     * @param userId The user ID to analyze
     * @return Map with counts of different room types
     */
    public Map<String, Integer> getUserRoomTypeSummary(String userId) {
        List<RoomAnalysis> rooms = analyzeUserRooms(userId);
        
        Map<String, Integer> summary = new HashMap<>();
        summary.put("direct_message", 0);
        summary.put("likely_dm", 0);
        summary.put("group_room", 0);
        
        for (RoomAnalysis room : rooms) {
            summary.merge(room.roomType(), 1, Integer::sum);
        }
        
        return summary;
    }

    /**
     * Checks if a user is primarily in group rooms or direct messages
     * @param userId The user ID to check
     * @return "GROUP_USER" if mostly group rooms, "DM_USER" if mostly direct messages, "MIXED" if balanced
     */
    public String getUserRoomTypeClassification(String userId) {
        Map<String, Integer> summary = getUserRoomTypeSummary(userId);
        
        int groupRooms = summary.get("group_room");
        int directMessages = summary.get("direct_message") + summary.get("likely_dm");
        
        if (groupRooms > directMessages * 2) {
            return "GROUP_USER";
        } else if (directMessages > groupRooms * 2) {
            return "DM_USER";
        } else {
            return "MIXED";
        }
    }

    private RoomAnalysis mapRowToRoomAnalysis(ResultSet rs, int rowNum) throws SQLException {
        return new RoomAnalysis(
            rs.getString("room_id"),
            rs.getString("creator"),
            rs.getInt("joined_members"),
            rs.getString("room_type")
        );
    }

    /**
     * Gets the room type for a specific room (group_room, likely_dm, or direct_message)
     * @param roomId The room ID to check
     * @return The room type string
     */
    private String getUserRoomType(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return "likely_dm";
        }

        String sql = """
            SELECT 
                CASE 
                    WHEN ad.content IS NOT NULL THEN 'direct_message'
                    WHEN rsc.joined_members <= 2 THEN 'likely_dm'
                    ELSE 'group_room'
                END AS room_type
            FROM rooms r
            LEFT JOIN room_stats_current rsc ON r.room_id = rsc.room_id
            LEFT JOIN account_data ad ON ad.account_data_type = 'm.direct'
                AND r.room_id = ANY (
                    SELECT jsonb_array_elements_text(ad.content::jsonb)
                )
            WHERE r.room_id = ?
            """;

        try {
            String roomType = jdbcTemplate.queryForObject(sql, String.class, roomId);
            log.debug("Room {} analysis: type={}", roomId, roomType);
            return roomType != null ? roomType : "likely_dm";
        } catch (Exception e) {
            log.warn("Error analyzing room {}: {}, defaulting to likely_dm", roomId, e.getMessage());
            return "likely_dm"; // Default to direct message if we can't determine
        }
    }

    /**
     * Gets the group name for a room
     * @param roomId The room ID
     * @return The group name or null if not found
     */
    private String getGroupName(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return null;
        }

        String sql = """
            SELECT 
                COALESCE(
                    (SELECT content->>'name' FROM room_data WHERE room_id = ? AND type = 'm.room.name'),
                    (SELECT content->>'topic' FROM room_data WHERE room_id = ? AND type = 'm.room.topic'),
                    'Group Chat'
                ) AS group_name
            """;

        try {
            String groupName = jdbcTemplate.queryForObject(sql, String.class, roomId, roomId);
            log.debug("Group name for room {}: {}", roomId, groupName);
            return groupName;
        } catch (Exception e) {
            log.warn("Error getting group name for room {}: {}", roomId, e.getMessage());
            return "Group Chat"; // Default group name
        }
    }

    /**
     * Gets the display name for a sender user
     * @param senderId The sender user ID
     * @return The display name or the user ID if not found
     */
    private String getSenderDisplayName(String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return "Unknown User";
        }

        String sql = """
            SELECT 
                COALESCE(
                    (SELECT content->>'displayname' FROM profiles WHERE user_id = ?),
                    (SELECT content->>'displayname' FROM user_directory WHERE user_id = ?),
                    ?
                ) AS display_name
            """;

        try {
            String displayName = jdbcTemplate.queryForObject(sql, String.class, senderId, senderId, senderId);
            log.debug("Display name for sender {}: {}", senderId, displayName);
            return displayName != null ? displayName : senderId;
        } catch (Exception e) {
            log.warn("Error getting display name for sender {}: {}", senderId, e.getMessage());
            return senderId; // Fallback to user ID
        }
    }

    /**
     * Record class to hold room analysis results
     */
    public record RoomAnalysis(
        String roomId,
        String creator,
        int joinedMembers,
        String roomType
    ) {}
}


