package com.pushnotification.pushserver.application.controller;

import com.pushnotification.pushserver.application.dto.CallNotificationRequest;
import com.pushnotification.pushserver.domain.service.CallNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallController {

    private final CallNotificationService callNotificationService;
    private static final Logger log = LoggerFactory.getLogger(CallController.class);

    @PostMapping("/incoming")
    public ResponseEntity<Map<String, Object>> incoming(@Valid @RequestBody CallNotificationRequest request) {
        log.info("Incoming call: senderId={}, roomId={}, callType={}", request.getSenderId(), request.getRoomId(), request.getCallType());
        callNotificationService.sendIncomingCallNotification(request);
        log.info("Processed incoming call for roomId={} from senderId={}", request.getRoomId(), request.getSenderId());
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> reject(@Valid @RequestBody CallNotificationRequest request) {
        log.info("Call rejected: senderId={}, roomId={}, callType={}", request.getSenderId(), request.getRoomId(), request.getCallType());
        // Use the same sending logic as incoming
        callNotificationService.sendIncomingCallNotification(request);
        return ResponseEntity.ok(Map.of(
                "status", "rejected",
                "reject", request.getReject() == null ? null : request.getReject()
        ));
    }
}


