package com.pushnotification.pushserver.application.controller;

import com.pushnotification.pushserver.application.dto.CallNotificationRequest;
import com.pushnotification.pushserver.domain.service.CallNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
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
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("status", "sent");
        log.info("Controller response payload (incoming): {}", resp);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> reject(@Valid @RequestBody CallNotificationRequest request) {
        log.info("Call rejected: senderId={}, roomId={}, callType={}, eventId={},url={}, reject={}" ,
                request.getSenderId(), request.getRoomId(), request.getCallType(), request.getEventId(), request.getUrl(), request.getReject());
        // Use the same sending logic as incoming
        callNotificationService.sendIncomingCallNotification(request);
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("status", "rejected");
        if (request.getReject() != null) {
            resp.put("reject", request.getReject());
        }
        log.info("Controller response payload (reject): {}", resp);
        return ResponseEntity.ok(resp);
    }

}


