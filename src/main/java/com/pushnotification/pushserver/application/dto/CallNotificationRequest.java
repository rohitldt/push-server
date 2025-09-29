package com.pushnotification.pushserver.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CallNotificationRequest {
    private String senderId;
    private String roomId;
    private String callType; // e.g., audio or video
    private String eventId;
    private String senderName;
    private String groupName; // Optional: for group calls
    // Optional: for reject flow; forwarded when present
    private String url;
    private Boolean reject;
}


