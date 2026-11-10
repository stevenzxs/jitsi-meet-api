package com.jitsimeet.api.controller;

import com.jitsimeet.api.service.CreatorHostReclaimService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Server-to-server only. The service secret alone never grants creator authority. */
@RestController
@RequestMapping("/internal/host-role/v1")
public class HostReclaimController {
    private final HostRoleCheckController authentication;
    private final CreatorHostReclaimService service;
    public HostReclaimController(HostRoleCheckController authentication, CreatorHostReclaimService service) {
        this.authentication = authentication; this.service = service;
    }
    public record Reclaim(String roomName, String creatorCredential, String expectedState,
                          String expectedOperationId, String expectedRoomInstance, String expectedIdentity,
                          String operationId, String targetSession, String roomInstance) {}

    @PostMapping("/reclaim")
    public ResponseEntity<CreatorHostReclaimService.Result> reclaim(
            @RequestHeader(value="X-Role-Check-Secret", required=false) String secret,
            @RequestBody Reclaim request) {
        authentication.authenticate(secret);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reclaim(request.roomName(),
                request.creatorCredential(), request.expectedState(), request.expectedOperationId(),
                request.expectedRoomInstance(), request.expectedIdentity(), request.operationId(),
                request.targetSession(), request.roomInstance()));
    }
}
