package com.jitsimeet.api.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

/** Operator-authorized replacement, never an unlock or an identity-based key lookup. */
@Service
public class HostTransferRecoveryService {
    private final JdbcTemplate jdbc;
    private final HostCredentialTransferService rotation;
    private final DurableHostTransferService transfers;

    public HostTransferRecoveryService(JdbcTemplate jdbc, HostCredentialTransferService rotation, DurableHostTransferService transfers) {
        this.jdbc = jdbc;
        this.rotation = rotation;
        this.transfers = transfers;
    }

    public record Result(String operationId, String identity) {}

    @Transactional
    public Result replace(String room, String expectedOperation, String expectedInstance, String expectedIdentity,
                          String operation, String target, String instance, String operator, String reason) {
        if (!matches(room, "\\d{9}") || !matches(expectedOperation, "[A-Za-z0-9_-]{1,64}")
                || !matches(operation, "[A-Za-z0-9_-]{1,64}") || operation.equals(expectedOperation)
                || !matches(expectedInstance, "[A-Za-z0-9_-]{1,128}")
                || !matches(instance, "[A-Za-z0-9_-]{1,128}") || instance.equals(expectedInstance)
                || !matches(expectedIdentity, "host-v1-[A-Za-z0-9_-]{43}")
                || target == null || target.length() > 512 || !matches(target, "[^\\s@/]+@[^\\s/]+/[^\\s]+")
                || !matches(operator, "[A-Za-z0-9_.@-]{1,128}")
                || !matches(reason, "[A-Za-z0-9_.:/ -]{1,256}") || reason.isBlank()) throw conflict();
        // Acquire the SQLite writer lock with the snapshot CAS first. Every
        // subsequent credential/receipt/audit write rolls back with this change.
        int changed = jdbc.update("UPDATE meeting_host_authority SET operation_id=? "
                + "WHERE room_name=? AND state='TRANSFERRING' AND operation_id=? "
                + "AND EXISTS (SELECT 1 FROM host_transfer_operation WHERE room_name=? AND operation_id=? "
                + "AND room_instance=? AND result_identity=?) "
                + "AND NOT EXISTS (SELECT 1 FROM host_transfer_operation WHERE room_name=? AND operation_id=?)",
                operation, room, expectedOperation, room, expectedOperation, expectedInstance, expectedIdentity, room, operation);
        if (changed != 1) throw conflict();
        var result = rotation.rotate(room, expectedIdentity);
        long now = Instant.now().getEpochSecond();
        jdbc.update("INSERT INTO host_transfer_operation "
                + "(room_name,operation_id,source_identity,target_session,room_instance,result_identity,expires_at) "
                + "VALUES (?,?,?,?,?,?,?)", room, operation, expectedIdentity, target, instance, result.identity(), now + 300);
        jdbc.update("INSERT INTO host_transfer_recovery_audit "
                + "(room_name,operation_id,previous_operation_id,operator_id,reason,created_at) VALUES (?,?,?,?,?,?)",
                room, operation, expectedOperation, operator, reason, now);
        return new Result(operation, result.identity());
    }

    private static boolean matches(String value, String pattern) { return value != null && value.matches(pattern); }
    /** Read-only retry on the independent administrator channel. Never rerotates. */
    @Transactional(readOnly = true)
    public Result resume(String room, String previous, String operation, String target, String instance) {
        var rows = jdbc.query("SELECT o.result_identity FROM host_transfer_operation o "
                + "JOIN meeting_host_authority a ON a.room_name=o.room_name AND a.operation_id=o.operation_id "
                + "JOIN host_transfer_recovery_audit r ON r.room_name=o.room_name AND r.operation_id=o.operation_id "
                + "JOIN meeting m ON m.room_name=o.room_name "
                + "WHERE o.room_name=? AND o.operation_id=? AND r.previous_operation_id=? "
                + "AND o.target_session=? AND o.room_instance=? AND a.state='TRANSFERRING' AND o.expires_at>?",
                (r, i) -> r.getString(1), room, operation, previous, target, instance, Instant.now().getEpochSecond());
        if (rows.size() != 1) throw conflict();
        transfers.claim(room, operation, target, instance); // Also checks live meeting/current credential identity.
        return new Result(operation, rows.get(0));
    }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Recovery snapshot is invalid or superseded");
    }
}
