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
        log.info("🚀 Starting call notification process for roomId={}, senderId={}, callType={}", 
                request.getRoomId(), request.getSenderId(), request.getCallType());
        
        // Check if the calling user (sender) is in a group room or direct message
        log.info("🔍 Analyzing if calling user {} is in a group room for roomId={}", request.getSenderId(), request.getRoomId());
        
        boolean isGroupCall = false;
        try {
            isGroupCall = isUserInGroupRoom(request.getSenderId(), request.getRoomId());
            log.info("📊 User room analysis result: roomId={}, callingUser={}, isGroupCall={}", 
                    request.getRoomId(), request.getSenderId(), isGroupCall);
        } catch (Exception e) {
            log.error("❌ Error in group call detection: {}", e.getMessage(), e);
            isGroupCall = false; // Default to direct call on error
            log.info("📊 User room analysis result (error fallback): roomId={}, callingUser={}, isGroupCall={}", 
                    request.getRoomId(), request.getSenderId(), isGroupCall);
        }
        
        // Clear decision log
        if (isGroupCall) {
            log.info("🎯 DECISION: This is a GROUP CALL - will show group name in notification");
        } else {
            log.info("🎯 DECISION: This is a DIRECT CALL - will show sender name in notification");
        }
        
        String groupName = null;
        String notificationTitle;
        String notificationBody;
        
        if (isGroupCall) {
            // It's a group call - get group name
            log.info("👥 ===== GROUP CALL DETECTED =====");
            log.info("👥 Calling user {} is in a GROUP ROOM", request.getSenderId());
            log.info("👥 Room ID: {}", request.getRoomId());
            log.info("👥 Getting group name for roomId={}", request.getRoomId());
            groupName = getGroupName(request.getRoomId());
            notificationTitle = "Incoming " + request.getCallType() + " call in " + (groupName != null ? groupName : "group");
            notificationBody = request.getSenderId() + " is calling";
            log.info("✅ Group call setup complete:");
            log.info("   📍 Room: {}", request.getRoomId());
            log.info("   👤 Sender: {}", request.getSenderId());
            log.info("   🏷️ Group Name: {}", groupName);
            log.info("   📱 Title: {}", notificationTitle);
            log.info("   💬 Body: {}", notificationBody);
            log.info("👥 ===== END GROUP CALL =====");
        } else {
            // It's a direct message - use sender name
            log.info("💬 ===== DIRECT CALL DETECTED =====");
            log.info("💬 Calling user {} is in a DIRECT MESSAGE", request.getSenderId());
            log.info("💬 Room ID: {}", request.getRoomId());
            log.info("💬 Getting sender display name for senderId={}", request.getSenderId());
            String senderName = getSenderDisplayName(request.getSenderId());
            notificationTitle = "Incoming " + request.getCallType() + " call";
            notificationBody = senderName + " is calling";
            log.info("✅ Direct call setup complete:");
            log.info("   📍 Room: {}", request.getRoomId());
            log.info("   👤 Sender: {}", request.getSenderId());
            log.info("   👤 Sender Name: {}", senderName);
            log.info("   📱 Title: {}", notificationTitle);
            log.info("   💬 Body: {}", notificationBody);
            log.info("💬 ===== END DIRECT CALL =====");
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
            log.info("📦 Added group call data: isGroupCall=true, groupName={}", groupName);
        } else {
            data.put("isGroupCall", "false");
            // For direct calls, add sender name to data payload
            String senderName = getSenderDisplayName(request.getSenderId());
            data.put("senderName", senderName);
            log.info("📦 Added direct call data: isGroupCall=false, senderName={}", senderName);
        }

        log.info("📋 Final notification data payload: {}", data);

        log.info("👥 Resolving members for roomId={}, isGroupCall={}", request.getRoomId(), isGroupCall);
        String senderMxid = request.getSenderId();
        List<String> roomMembers = membershipRepository.findByRoomIdAndMembership(request.getRoomId(), "join").stream().map(m -> m.getUserId()).filter(u -> !u.equals(senderMxid)).distinct().collect(Collectors.toList());
        log.info("📝 Room members to notify (excluding sender): count={}, members={}", roomMembers.size(), roomMembers);

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

        log.info("📱 Loaded pushers: count={} (members={}, triedLocalsFallback={})", pushers.size(), roomMembers.size(), pushers.isEmpty() ? "yes" : "no");
        
        // Log all pushers before filtering
        log.info("📱 All pushers found:");
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
            
        log.info("🍎 VoIP pushers (iOS): count={}", voipPushers.size());
        voipPushers.forEach(p -> log.info("  - user={}, appId={}, token={}", 
            p.getUserName(), p.getAppId(), 
            p.getPushkey() != null ? p.getPushkey() : "NULL"));
            
        log.info("🤖 Android pushers: count={}", androidPushers.size());
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
        log.info("✅ Completed notification sends for roomId={} totalMembers={} voipPushers={} androidPushers={} totalFutures={}", 
            request.getRoomId(), roomMembers.size(), voipPushers.size(), androidPushers.size(), allFutures.size());
        log.info("🎯 Final notification summary: roomId={}, callingUser={}, isGroupCall={}, title={}, body={}", 
            request.getRoomId(), request.getSenderId(), isGroupCall, notificationTitle, notificationBody);
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
     * This checks if the calling user is in a group room or direct message
     * @param roomId The room ID to check
     * @return The room type string
     */
    private String getUserRoomType(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            log.warn("⚠️ Room ID is null or blank, defaulting to likely_dm");
            return "likely_dm";
        }

        log.info("🔍 Executing room type query for roomId={}", roomId);
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
            log.info("📊 Room type query result: roomId={}, roomType={}", roomId, roomType);
            return roomType != null ? roomType : "likely_dm";
        } catch (Exception e) {
            log.error("❌ Error analyzing room {}: {}, defaulting to likely_dm", roomId, e.getMessage(), e);
            return "likely_dm"; // Default to direct message if we can't determine
        }
    }

    /**
     * Checks if a specific user is in a group room based on your data structure
     * This method uses the exact query you provided to determine room types
     * @param userId The user ID to check
     * @param roomId The room ID to check
     * @return true if the user is in a group room, false if direct message
     */
    private boolean isUserInGroupRoom(String userId, String roomId) {
        if (userId == null || userId.isBlank() || roomId == null || roomId.isBlank()) {
            log.warn("⚠️ User ID or Room ID is null/blank, defaulting to direct message");
            return false;
        }

        log.info("🔍 Checking if user {} is in group room for roomId={}", userId, roomId);
        String sql = """
            SELECT 
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
            WHERE r.room_id = ?
            """;

        try {
            String roomType = jdbcTemplate.queryForObject(sql, String.class, userId, roomId);
            boolean isGroup = "group_room".equals(roomType);
            log.info("📊 User room type check: userId={}, roomId={}, roomType={}, isGroup={}", 
                    userId, roomId, roomType, isGroup);
            return isGroup;
        } catch (Exception e) {
            log.error("❌ Error checking user room type for user {} in room {}: {}, defaulting to direct message", 
                    userId, roomId, e.getMessage(), e);
            return false; // Default to direct message if we can't determine
        }
    }

    /**
     * Gets the group name for a room
     * @param roomId The room ID
     * @return The group name or null if not found
     */
    private String getGroupName(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            log.warn("⚠️ Room ID is null or blank for group name query");
            return null;
        }

        log.info("🏷️ Getting group name for roomId={}", roomId);
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
            log.info("🏷️ Group name query result: roomId={}, groupName={}", roomId, groupName);
            return groupName;
        } catch (Exception e) {
            log.error("❌ Error getting group name for room {}: {}, using default", roomId, e.getMessage(), e);
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
            log.warn("⚠️ Sender ID is null or blank, using default");
            return "Unknown User";
        }

        log.info("👤 Getting display name for senderId={}", senderId);
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
            log.info("👤 Display name query result: senderId={}, displayName={}", senderId, displayName);
            return displayName != null ? displayName : senderId;
        } catch (Exception e) {
            log.error("❌ Error getting display name for sender {}: {}, using senderId", senderId, e.getMessage(), e);
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


