package com.pushnotification.pushserver.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class SendNotificationRequest {
    @NotBlank
    private String userId;
    @NotBlank
    private String title;
    @NotBlank
    private String body;
    private Map<String, String> data;
}


