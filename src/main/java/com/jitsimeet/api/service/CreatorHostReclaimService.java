package com.jitsimeet.api.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Objects;

/** Internal only: Prosody must bind target to the authenticated creator origin. */
@Service
public class CreatorHostReclaimService {
    private final JdbcTemplate jdbc;
    private final CreatorRecoveryService creators;
    private final HostCredentialTransferService rotation;
    private final DurableHostTransferService transfers;

    public CreatorHostReclaimService(JdbcTemplate jdbc, CreatorRecoveryService creators,
                                    HostCredentialTransferService rotation, DurableHostTransferService transfers) {
        this.jdbc = jdbc; this.creators = creators; this.rotation = rotation; this.transfers = transfers;
    }
    public record Result(String operationId, String identity) {}
    private record Snapshot(String state, String previous, String instance, String identity) {}

    @Transactional
    public Result reclaim(String room, String credential, String state, String previous, String expectedInstance,
                          String identity, String operation, String target, String instance) {
        if (!valid(room, "\\d{9}") || !("ACTIVE".equals(state) || "TRANSFERRING".equals(state))
                || !valid(identity, "host-v1-[A-Za-z0-9_-]{43}")
                || !valid(operation, "[A-Za-z0-9_-]{1,64}") || operation.equals(previous)
                || !valid(instance, "[A-Za-z0-9_-]{1,128}") || instance.equals(expectedInstance)
                || target == null || target.length() > 512 || !valid(target, "[^\\s@/]+@[^\\s/]+/[^\\s]+")
                || (previous == null ? expectedInstance != null || !"ACTIVE".equals(state)
                    : !valid(previous, "[A-Za-z0-9_-]{1,64}") || !valid(expectedInstance, "[A-Za-z0-9_-]{1,128}")))
            throw conflict();
        // First write acquires the SQLite writer lock before snapshot/proof reads.
        // Any authorization or subsequent write failure rolls this reservation back.
        jdbc.update("UPDATE meeting_host_authority SET state=state WHERE room_name=?", room);
        if (!creators.matches(room, credential)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var retries = jdbc.query("SELECT expected_state,previous_operation_id,expected_instance,source_identity "
                        + "FROM creator_host_reclaim WHERE room_name=? AND operation_id=?",
                (r,i) -> new Snapshot(r.getString(1),r.getString(2),r.getString(3),r.getString(4)), room, operation);
        Snapshot expected = new Snapshot(state, previous, expectedInstance, identity);
        if (!retries.isEmpty()) {
            if (!expected.equals(retries.get(0))) throw conflict();
            var claimed = transfers.claim(room, operation, target, instance);
            return new Result(operation, claimed.identity());
        }
        var authority = jdbc.query("SELECT state,operation_id FROM meeting_host_authority WHERE room_name=?",
                (r,i) -> new String[]{r.getString(1),r.getString(2)}, room);
        if (authority.isEmpty()) {
            if (!"ACTIVE".equals(state) || previous != null) throw conflict();
        } else if (authority.size() != 1 || !state.equals(authority.get(0)[0])
                || !Objects.equals(previous, authority.get(0)[1])) throw conflict();
        if (previous != null) {
            int count = jdbc.queryForObject("SELECT count(*) FROM host_transfer_operation WHERE room_name=? "
                    + "AND operation_id=? AND room_instance=? AND result_identity=?", Integer.class,
                    room, previous, expectedInstance, identity);
            if (count != 1) throw conflict();
        }
        if (jdbc.queryForObject("SELECT count(*) FROM host_transfer_operation WHERE room_name=? AND operation_id=?",
                Integer.class, room, operation) != 0) throw conflict();
        int changed = authority.isEmpty()
                ? jdbc.update("INSERT INTO meeting_host_authority(room_name,state,operation_id) VALUES (?,'TRANSFERRING',?)", room, operation)
                : jdbc.update("UPDATE meeting_host_authority SET state='TRANSFERRING',operation_id=? "
                    + "WHERE room_name=? AND state=? AND operation_id IS ?", operation, room, state, previous);
        if (changed != 1) throw conflict();
        var rotated = rotation.rotate(room, identity);
        jdbc.update("INSERT INTO host_transfer_operation(room_name,operation_id,source_identity,target_session,room_instance,result_identity,expires_at) "
                + "VALUES (?,?,?,?,?,?,?)", room, operation, identity, target, instance, rotated.identity(), Instant.now().plusSeconds(300).getEpochSecond());
        jdbc.update("INSERT INTO creator_host_reclaim(room_name,operation_id,expected_state,previous_operation_id,expected_instance,source_identity,created_at) "
                + "VALUES (?,?,?,?,?,?,?)", room, operation, state, previous, expectedInstance, identity, Instant.now().getEpochSecond());
        return new Result(operation, rotated.identity());
    }
    private static boolean valid(String value, String regex) { return value != null && value.matches(regex); }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Creator reclaim snapshot is invalid or superseded");
    }
}
