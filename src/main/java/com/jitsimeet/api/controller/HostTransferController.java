package com.jitsimeet.api.controller;

import com.jitsimeet.api.service.HostCredentialTransferService;
import com.jitsimeet.api.service.DurableHostTransferService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Internal commit step. Never expose as a browser-managed host transfer. */
@RestController
@RequestMapping("/internal/host-role/v1")
public class HostTransferController {
    private final HostRoleCheckController authentication;
    private final DurableHostTransferService transfers;

    public HostTransferController(HostRoleCheckController authentication, DurableHostTransferService transfers) {
        this.authentication = authentication;
        this.transfers = transfers;
    }

    public record Transfer(String roomName, String identity, String operationId, String targetSession, String roomInstance) {}
    public record Confirmation(String roomName, String operationId, String targetSession, String roomInstance) {}
    public record StatusRequest(String roomName) {}
    public record Rebind(String roomName, String operationId, String targetSession, String roomInstance,
                         String expectedRoomInstance, String nonce, String proof) {}

    @PostMapping("/rotate")
    public ResponseEntity<HostCredentialTransferService.Result> rotate(
            @RequestHeader(value = "X-Role-Check-Secret", required = false) String secret,
            @RequestBody Transfer request) {
        authentication.authenticate(secret);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(transfers.rotate(request.roomName(), request.identity(), request.operationId(),
                        request.targetSession(), request.roomInstance()));
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = "X-Role-Check-Secret", required = false) String secret,
            @RequestBody Confirmation request) {
        authentication.authenticate(secret);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        transfers.complete(request.roomName(), request.operationId(), request.targetSession(), request.roomInstance());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/status")
    public ResponseEntity<DurableHostTransferService.Status> status(
            @RequestHeader(value = "X-Role-Check-Secret", required = false) String secret,
            @RequestBody StatusRequest request) {
        authentication.authenticate(secret);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transfers.status(request.roomName()));
    }

    @PostMapping("/rebind")
    public ResponseEntity<DurableHostTransferService.RebindResult> rebind(
            @RequestHeader(value = "X-Role-Check-Secret", required = false) String secret,
            @RequestBody Rebind request) {
        authentication.authenticate(secret);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(transfers.rebind(request.roomName(), request.operationId(), request.targetSession(),
                        request.roomInstance(), request.expectedRoomInstance(), request.nonce(), request.proof()));
    }

    @PostMapping("/claim")
    public ResponseEntity<HostCredentialTransferService.Result> claim(
            @RequestHeader(value = "X-Role-Check-Secret", required = false) String secret,
            @RequestBody Confirmation request) {
        authentication.authenticate(secret);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(transfers.claim(request.roomName(), request.operationId(),
                        request.targetSession(), request.roomInstance()));
    }
}
