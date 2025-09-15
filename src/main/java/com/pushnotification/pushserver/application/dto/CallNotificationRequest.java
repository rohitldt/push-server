package com.pushnotification.pushserver.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CallNotificationRequest {
    @NotBlank
    private String senderId;
    @NotBlank
    private String roomId;
    @NotBlank
    private String callType; // e.g., audio or video
}


