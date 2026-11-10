package com.jitsimeet.api.controller;

import com.jitsimeet.api.service.HostTransferRecoveryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Disabled by default. A trusted operator must independently verify the live
 * XMPP target before replacement; this API cannot attest XMPP membership. */
@RestController
@RequestMapping("/internal/host-recovery/v1")
public class HostTransferRecoveryController {
    private final HostTransferRecoveryService recovery;
    private final String secret;
    private final String roleSecret;

    public HostTransferRecoveryController(HostTransferRecoveryService recovery,
            @Value("${host-recovery.admin-secret:}") String secret,
            @Value("${jitsi-token.role-check-secret:}") String roleSecret) {
        this.recovery = recovery;
        this.secret = secret;
        this.roleSecret = roleSecret;
    }

    public record Replacement(String roomName, String expectedOperationId, String expectedRoomInstance,
                              String expectedIdentity, String operationId, String targetSession,
                              String roomInstance, String operatorId, String reason) {}

    public record Resume(String roomName, String previousOperationId, String operationId,
                         String targetSession, String roomInstance) {}

    private void authenticate(String provided) {
        if (secret == null || secret.length() < 32 || secret.length() > 256 || secret.equals(roleSecret)
                || provided == null || provided.length() > 256
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @PostMapping("/resume")
    public ResponseEntity<HostTransferRecoveryService.Result> resume(
            @RequestHeader(value = "X-Host-Recovery-Secret", required = false) String provided,
            @RequestBody Resume request) {
        authenticate(provided);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recovery.resume(request.roomName(),
                request.previousOperationId(), request.operationId(), request.targetSession(), request.roomInstance()));
    }

    @PostMapping("/replace")
    public ResponseEntity<HostTransferRecoveryService.Result> replace(
            @RequestHeader(value = "X-Host-Recovery-Secret", required = false) String provided,
            @RequestBody Replacement request) {
        authenticate(provided);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recovery.replace(request.roomName(),
                request.expectedOperationId(), request.expectedRoomInstance(), request.expectedIdentity(),
                request.operationId(), request.targetSession(), request.roomInstance(), request.operatorId(), request.reason()));
    }
}
