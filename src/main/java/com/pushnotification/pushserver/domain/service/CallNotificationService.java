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

        boolean isGroupCall = request.getGroupName() != null && !request.getGroupName().isBlank();

        String groupName = null;
        String notificationTitle;
        String notificationBody;
        String callType = request.getCallType() != null ? request.getCallType().toLowerCase() : "call";
        if (isGroupCall) {
            log.info("GROUP_CALL_PROCESSING - Processing group call notification [roomId={}, senderId={}]", request.getRoomId(), request.getSenderId());
            if (request.getGroupName() != null && !request.getGroupName().isBlank()) {
                groupName = request.getGroupName();
                notificationTitle = "Incoming group " + callType + " call";
                log.info("GROUP_CALL_WITH_NAME - Group call with name from request [roomId={}, groupName={}]", request.getRoomId(), groupName);
            } else {
                notificationTitle = "Incoming group " + callType + " call";
                log.info("GROUP_CALL_WITHOUT_NAME - Group call without name, using generic title [roomId={}]", request.getRoomId());
            }
            notificationBody = request.getSenderId() + " is calling";

            log.info("GROUP_CALL_CONFIGURED - Group call notification configured [roomId={}, senderId={}, groupName={}, title={}]", request.getRoomId(), request.getSenderId(), groupName, notificationTitle);
        } else {
            String senderName = getSenderDisplayName(request.getSenderId());
            notificationTitle = "Incoming " + callType + " call";
            notificationBody = senderName + " is calling";

            log.info("DIRECT_CALL_CONFIGURED - Direct call notification configured [roomId={}, senderId={}, senderName={}, title={}]", request.getRoomId(), request.getSenderId(), senderName, notificationTitle);
        }

        Map<String, String> data = new HashMap<>();
        if (request.getRoomId() != null) {
            data.put("roomId", request.getRoomId());
            data.put("room_id", request.getRoomId());
        }
        // Set 'type' based on reject flag: 'reject' for rejected calls, otherwise 'call'
        boolean isReject = Boolean.TRUE.equals(request.getReject());
        data.put("type", isReject ? "reject" : "call");
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
        // Keep explicit reject flag for clients that rely on it
        if (request.getReject() != null) {
            data.put("reject", String.valueOf(request.getReject()));
        }
        log.info("VoIP data map to send (pre-APNs): {}", data);

        if (isGroupCall) {
            data.put("isGroupCall", "true");
            log.info("PAYLOAD_GROUP_DEBUG - Checking groupName for payload [roomId={}, requestGroupName={}, isNull={}, isBlank={}]", request.getRoomId(), request.getGroupName(), request.getGroupName() == null, request.getGroupName() != null && request.getGroupName().isBlank());
            if (request.getGroupName() != null && !request.getGroupName().isBlank()) {
                data.put("groupName", request.getGroupName());
                log.info("PAYLOAD_DATA_GROUP - Added group call data to payload [roomId={}, groupNameFromRequest={}]", request.getRoomId(), request.getGroupName());
            } else {
                data.put("groupName", "GroupCall");
                log.info("PAYLOAD_DATA_GROUP_DEFAULT - Group call detected but no groupName in request, using default [roomId={}, groupName=GroupCall]", request.getRoomId());
            }
        } else {
            data.put("isGroupCall", "false");
            // For direct calls, add sender name to data payload
            String senderName = getSenderDisplayName(request.getSenderId());
            data.put("senderName", senderName);
            log.debug("PAYLOAD_DATA_DIRECT - Added direct call data to payload [roomId={}, senderName={}]", request.getRoomId(), senderName);
        }

        // Add URL to payload only if it's not null
        String url = request.getUrl();
        if (url != null && !url.isBlank()) {
            data.put("url", url);
            log.info("PAYLOAD_URL_FROM_REQUEST - Added URL from request [roomId={}, url={}]", request.getRoomId(), url);
        } else {
            log.info("PAYLOAD_URL_SKIPPED - URL is null/blank, not adding to payload [roomId={}]", request.getRoomId());
        }

        log.info("NOTIFICATION_PAYLOAD - Final data payload prepared [roomId={}, payloadSize={}, keys={}, payload={}]", request.getRoomId(), data.size(), data.keySet(), data);

        // Debug: Log the exact data being sent to FCM and APNs
        log.info("FINAL_PAYLOAD_DEBUG - Data map being sent to FCM/APNs [roomId={}, containsUrl={}, urlValue={}]", request.getRoomId(), data.containsKey("url"), data.get("url"));

        log.info("MEMBER_RESOLUTION_START - Resolving room members for notification [roomId={}, isGroupCall={}]", request.getRoomId(), isGroupCall);
        String senderMxid = request.getSenderId();
        List<String> roomMembers = membershipRepository.findByRoomIdAndMembership(request.getRoomId(), "join").stream().map(m -> m.getUserId()).filter(u -> !u.equals(senderMxid)).distinct().collect(Collectors.toList());
        log.info("MEMBER_RESOLUTION_RESULT - Room members identified [roomId={}, memberCount={}, members={}]", request.getRoomId(), roomMembers.size(), roomMembers);

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

        log.info("PUSHER_LOAD_RESULT - Pushers loaded from database [totalPushers={}, roomMembers={}, fallbackUsed={}]", pushers.size(), roomMembers.size(), pushers.isEmpty() ? "yes" : "no");

        // Log all pushers before filtering
        log.debug("PUSHER_DETAILS - All pushers found in database:");
        pushers.forEach(p -> log.debug("PUSHER_ITEM - Pusher details [user={}, appId={}, hasToken={}]", p.getUserName(), p.getAppId(), p.getPushkey() != null && !p.getPushkey().isBlank()));

        // Filter for both VoIP (iOS), normal iOS (non-VoIP), and FCM (Android) app IDs
        List<Pusher> voipPushers = pushers.stream().filter(p -> !request.getSenderId().equals(p.getUserName())).filter(p -> {
            String appId = p.getAppId();
            return appId != null && ("com.pareza.app.ios.prod.voip".equals(appId) || "com.pareza.app.ios.dev.voip".equals(appId));
        }).collect(Collectors.toList());

        // Only accept normal iOS app IDs explicitly provided (non-VoIP)
        List<Pusher> normalIosPushers = pushers.stream()
                .filter(p -> !request.getSenderId().equals(p.getUserName()))
                .filter(p -> {
                    String appId = p.getAppId();
                    return "com.pareza.pro.ios.prod".equals(appId) || "com.pareza.pro.ios.dev".equals(appId);
                })
                .collect(Collectors.toList());

        List<Pusher> androidPushers = pushers.stream().filter(p -> !request.getSenderId().equals(p.getUserName())).filter(p -> "com.pareza.pro".equals(p.getAppId())|| "1:40238129953:web:90562b7f62c91e22a89460".equals(p.getAppId())).collect(Collectors.toList());

        log.info("PUSHER_FILTER_IOS_VOIP - iOS VoIP pushers identified [count={}]", voipPushers.size());
        voipPushers.forEach(p -> log.debug("PUSHER_IOS_VOIP - iOS pusher details [user={}, appId={}, tokenLength={}]", p.getUserName(), p.getAppId(), p.getPushkey() != null ? p.getPushkey().length() : 0));

        log.info("PUSHER_FILTER_IOS_NORMAL - iOS non-VoIP pushers identified [count={}]", normalIosPushers.size());
        normalIosPushers.forEach(p -> log.debug("PUSHER_IOS_NORMAL - iOS pusher details [user={}, appId={}, tokenLength={}]", p.getUserName(), p.getAppId(), p.getPushkey() != null ? p.getPushkey().length() : 0));

        log.info("PUSHER_FILTER_ANDROID - Android FCM pushers identified [count={}]", androidPushers.size());
        androidPushers.forEach(p -> log.debug("PUSHER_ANDROID - Android pusher details [user={}, appId={}, tokenLength={}]", p.getUserName(), p.getAppId(), p.getPushkey() != null ? p.getPushkey().length() : 0));

        if (voipPushers.isEmpty() && androidPushers.isEmpty()) {
            log.warn("PUSHER_WARNING - No valid pushers found for notification [roomId={}, memberCount={}] - Check user registrations", request.getRoomId(), roomMembers.size());
        }

        // Route iOS based on reject flag: VoIP for normal calls, NORMAL APNs for reject
        log.info("ROUTE_DECISION - isReject={}, voipPushers={}, normalIosPushers={}, androidPushers={}", isReject, voipPushers.size(), normalIosPushers.size(), androidPushers.size());
        List<CompletableFuture<?>> voipFutures = new ArrayList<>();
        if (!isReject) {
            voipFutures = voipPushers.stream().map(p -> {
                String token = p.getPushkey();

                System.out.println("the app id============>>>>>>>>>>>>>" + p.getAppId());

                String trimmed = token != null ? token.trim() : null;
                boolean whitespaceTrimmed = token != null && !token.equals(trimmed);
                log.info("ROUTE_PUSH - platform=iOS-VOIP, user={}, appId={}, tokenLen={}, type={}", p.getUserName(), p.getAppId(), token != null ? token.length() : 0, data.get("type"));
                log.info("Token diagnostics: rawLen={}, trimmedLen={}, whitespaceTrimmed={}, startsWithSpace={}, endsWithSpace={}", token == null ? 0 : token.length(), trimmed == null ? 0 : trimmed.length(), whitespaceTrimmed, token != null && !token.isEmpty() && Character.isWhitespace(token.charAt(0)), token != null && !token.isEmpty() && Character.isWhitespace(token.charAt(token.length() - 1)));
                log.info("APNS_DATA_PAYLOAD - Data being sent to APNs [user={}, dataKeys={}, urlPresent={}]", p.getUserName(), data.keySet(), data.containsKey("url"));

                String iosTokenHex = normalizeIosToken(token);
                return apnsPushService.sendVoip(iosTokenHex, data).thenAccept(result -> {
                    if (result.success()) {
                        log.info("✅ APNS VoIP SUCCESS platform=iOS-VOIP, user={}, ApnsId={}", p.getUserName(), result.messageId());
                    } else {
                        log.error("❌ APNS VoIP FAILED platform=iOS-VOIP, user={}, Error={}", p.getUserName(), result.error());
                    }
                });
            }).collect(Collectors.toList());
        } else {
            if (normalIosPushers.isEmpty()) {
                log.warn("ROUTE_SKIP - Reject requested but no normal iOS tokens found (com.pareza.pro.ios.[dev|prod]); skipping iOS reject push [roomId={}]", request.getRoomId());
                voipFutures = new ArrayList<>();
            } else {
                voipFutures = normalIosPushers.stream().map(p -> {
                    String rawToken = p.getPushkey();
                    String appId = p.getAppId();
                    Map<String, String> rejectData = new HashMap<>(data);
                    rejectData.remove("senderName"); // keep minimal for background
                    String collapseId = "reject-" + request.getRoomId();

                    // Normalize and validate APNs token (64 hex)
                    String apnsTokenHex = normalizeIosToken(rawToken);
                    boolean validApnsToken = apnsTokenHex != null && apnsTokenHex.matches("[a-fA-F0-9]{64}");
                    if (!validApnsToken) {
                        log.warn("ROUTE_SKIP - iOS-BACKGROUND token invalid for user={}, appId={}, rawLen={}, normalizedLen={} (expect 64-hex); skipping background APNs",
                                p.getUserName(), appId, rawToken != null ? rawToken.length() : 0, apnsTokenHex != null ? apnsTokenHex.length() : 0);
                        return CompletableFuture.completedFuture(null);
                    }

                    // Send background (silent) APNs with default configured topic, priority 5, push-type background
                    log.info("ROUTE_PUSH - platform=iOS-BACKGROUND, user={}, appId={}, tokenLen={}, collapseId={}, type=reject",
                            p.getUserName(), appId, apnsTokenHex.length(), collapseId);
                    return apnsPushService.sendBackground(apnsTokenHex, rejectData, collapseId).thenAccept(result -> {
                        if (result.success()) {
                            log.info("✅ APNS BACKGROUND SUCCESS platform=iOS-BACKGROUND, user={}, ApnsId={}", p.getUserName(), result.messageId());
                        } else {
                            log.error("❌ APNS BACKGROUND FAILED platform=iOS-BACKGROUND, user={}, Error={}", p.getUserName(), result.error());
                        }
                    });
                }).collect(Collectors.toList());
            }
        }

        // Process Android pushers (FCM)
        List<CompletableFuture<?>> androidFutures = androidPushers.stream().map(p -> {
            String token = p.getPushkey();

            log.info("ROUTE_PUSH - platform=Android, user={}, appId={}, tokenLen={}, type={}", p.getUserName(), p.getAppId(), token != null ? token.length() : 0, data.get("type"));
            log.info("FCM_DATA_PAYLOAD - Data being sent to FCM [user={}, dataKeys={}, urlPresent={}]", p.getUserName(), data.keySet(), data.containsKey("url"));

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
        log.info("NOTIFICATION_SEND_COMPLETE - All notifications sent successfully [roomId={}, totalMembers={}, voipPushers={}, androidPushers={}, totalFutures={}]", request.getRoomId(), roomMembers.size(), voipPushers.size(), androidPushers.size(), allFutures.size());
        log.info("CALL_NOTIFICATION_SUMMARY - Final notification summary [roomId={}, callingUser={}, isGroupCall={}, title={}, body={}]", request.getRoomId(), request.getSenderId(), isGroupCall, notificationTitle, notificationBody);
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

//    private boolean isIosPusher(Pusher pusher) {
//        // Hard-map app IDs to platforms; fallback to token shape only if appId unknown
//        String appId = pusher.getAppId();
//        if (appId != null) {
//            if ("com.pareza.pro.ios.prod".equals(appId) || "com.pareza.pro.ios.dev".equals(appId) || appId.startsWith("com.pareza.pro.ios.")) {
//                return true; // iOS
//            }
//
//            if ("com.pareza.pro".equals(appId)) {
//                return false; // Android
//            }
//        }
//        String token = pusher.getPushkey();
//        return token != null && token.matches("[a-fA-F0-9]{64}");
//    }

    /**
     * Analyzes room types for a specific user to determine if they are in group rooms or direct messages
     *
     * @param userId The user ID to analyze (e.g., "@+919306273742:server.pareza.im")
     * @return List of room analysis results
     */
//    public List<RoomAnalysis> analyzeUserRooms(String userId) {
//        String sql = """
//                SELECT
//                    r.room_id,
//                    r.creator,
//                    rsc.joined_members,
//                    CASE
//                        WHEN ad.content IS NOT NULL THEN 'direct_message'
//                        WHEN rsc.joined_members <= 2 THEN 'likely_dm'
//                        ELSE 'group_room'
//                    END AS room_type
//                FROM rooms r
//                LEFT JOIN room_stats_current rsc ON r.room_id = rsc.room_id
//                LEFT JOIN account_data ad ON ad.user_id = ?
//                    AND ad.account_data_type = 'm.direct'
//                    AND r.room_id = ANY (
//                        SELECT jsonb_array_elements_text(ad.content::jsonb)
//                    )
//                WHERE r.room_id IN (
//                    SELECT DISTINCT room_id
//                    FROM room_memberships
//                    WHERE user_id = ?
//                      AND membership = 'join'
//                )
//                """;
//
//        return jdbcTemplate.query(sql, new Object[]{userId, userId}, this::mapRowToRoomAnalysis);
//    }

    /**
     * Gets a summary of room types for a user
     *
     * @param userId The user ID to analyze
     * @return Map with counts of different room types
     */
//    public Map<String, Integer> getUserRoomTypeSummary(String userId) {
//        List<RoomAnalysis> rooms = analyzeUserRooms(userId);
//
//        Map<String, Integer> summary = new HashMap<>();
//        summary.put("direct_message", 0);
//        summary.put("likely_dm", 0);
//        summary.put("group_room", 0);
//
//        for (RoomAnalysis room : rooms) {
//            summary.merge(room.roomType(), 1, Integer::sum);
//        }
//
//        return summary;
//    }

    /**
     * Checks if a user is primarily in group rooms or direct messages
     *
     * @param userId The user ID to check
     * @return "GROUP_USER" if mostly group rooms, "DM_USER" if mostly direct messages, "MIXED" if balanced
     */
//    public String getUserRoomTypeClassification(String userId) {
//        Map<String, Integer> summary = getUserRoomTypeSummary(userId);
//
//        int groupRooms = summary.get("group_room");
//        int directMessages = summary.get("direct_message") + summary.get("likely_dm");
//
//        if (groupRooms > directMessages * 2) {
//            return "GROUP_USER";
//        } else if (directMessages > groupRooms * 2) {
//            return "DM_USER";
//        } else {
//            return "MIXED";
//        }
//    }

    private RoomAnalysis mapRowToRoomAnalysis(ResultSet rs, int rowNum) throws SQLException {
        return new RoomAnalysis(rs.getString("room_id"), rs.getString("creator"), rs.getInt("joined_members"), rs.getString("room_type"));
    }

    /**
     * Gets the room type for a specific room (group_room, likely_dm, or direct_message)
     * This checks if the calling user is in a group room or direct message
     *
     * @param roomId The room ID to check
     * @return The room type string
     */
//    private String getUserRoomType(String roomId) {
//        if (roomId == null || roomId.isBlank()) {
//            log.warn("ROOM_ANALYSIS_INVALID - Room ID is null or blank, defaulting to likely_dm [roomId={}]", roomId);
//            return "likely_dm";
//        }
//
//        log.debug("ROOM_TYPE_QUERY_START - Executing room type analysis query [roomId={}]", roomId);
//        String sql = """
//                SELECT
//                    CASE
//                        WHEN ad.content IS NOT NULL THEN 'direct_message'
//                        WHEN rsc.joined_members <= 2 THEN 'likely_dm'
//                        ELSE 'group_room'
//                    END AS room_type
//                FROM rooms r
//                LEFT JOIN room_stats_current rsc ON r.room_id = rsc.room_id
//                LEFT JOIN account_data ad ON ad.account_data_type = 'm.direct'
//                    AND r.room_id = ANY (
//                        SELECT jsonb_array_elements_text(ad.content::jsonb)
//                    )
//                WHERE r.room_id = ?
//                """;
//
//        try {
//            String roomType = jdbcTemplate.queryForObject(sql, String.class, roomId);
//            log.debug("ROOM_TYPE_QUERY_RESULT - Room type analysis completed [roomId={}, roomType={}]", roomId, roomType);
//            return roomType != null ? roomType : "likely_dm";
//        } catch (Exception e) {
//            log.error("ROOM_TYPE_QUERY_ERROR - Failed to analyze room type [roomId={}, error={}]", roomId, e.getMessage(), e);
//            return "likely_dm"; // Default to direct message if we can't determine
//        }
//    }


    /**
     * Gets the display name for a sender user
     *
     * @param senderId The sender user ID
     * @return The display name or the user ID if not found
     */
    private String getSenderDisplayName(String senderId) {
        if (senderId == null || senderId.isBlank()) {
            log.warn("DISPLAY_NAME_INVALID - Sender ID is null or blank, using default [senderId={}]", senderId);
            return "Unknown User";
        }

        log.debug("DISPLAY_NAME_QUERY_START - Getting display name for sender [senderId={}]", senderId);

        // Try different possible table structures for display names
        String[] queries = {
                // Try profiles table with displayname column
                "SELECT displayname FROM profiles WHERE user_id = ?",
                // Try profiles table with content JSONB
                "SELECT content->>'displayname' FROM profiles WHERE user_id = ?",
                // Try user_directory table
                "SELECT display_name FROM user_directory WHERE user_id = ?",
                // Try user_directory with content JSONB
                "SELECT content->>'displayname' FROM user_directory WHERE user_id = ?"};

        for (String sql : queries) {
            try {
                String displayName = jdbcTemplate.queryForObject(sql, String.class, senderId);
                if (displayName != null && !displayName.trim().isEmpty()) {
                    log.debug("DISPLAY_NAME_QUERY_SUCCESS - Display name found [senderId={}, displayName={}]", senderId, displayName);
                    return displayName;
                }
            } catch (Exception e) {
                log.debug("DISPLAY_NAME_QUERY_ATTEMPT_FAILED - Query failed, trying next [senderId={}, error={}]", senderId, e.getMessage());
                // Continue to next query
            }
        }

        log.warn("DISPLAY_NAME_QUERY_FALLBACK - No display name found, using senderId [senderId={}]", senderId);
        return senderId; // Fallback to original senderId
    }

    /**
     * Record class to hold room analysis results
     */
    public record RoomAnalysis(String roomId, String creator, int joinedMembers, String roomType) {
    }
}


