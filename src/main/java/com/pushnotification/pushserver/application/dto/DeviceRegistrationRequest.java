package com.pushnotification.pushserver.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeviceRegistrationRequest {
    @NotBlank
    private String userId;
    @NotBlank
    private String token;
    @NotNull
    private Platform platform;
    private String deviceModel;
    private String osVersion;

    public enum Platform { IOS, ANDROID }
}


