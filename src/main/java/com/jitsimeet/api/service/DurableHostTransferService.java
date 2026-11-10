package com.jitsimeet.api.service;

import com.jitsimeet.api.mapper.MeetingMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A durable receipt binds retries to one trusted XMPP target session and room instance.
 * Only the authenticated server-to-server controller may expose this operation.
 */
@Service
public class DurableHostTransferService {
    private final JdbcTemplate jdbc;
    private final MeetingMapper meetings;
    private final JitsiTokenService tokens;
    private final HostCredentialTransferService rotation;
    private final HostAuthorityFence fence;

    public DurableHostTransferService(JdbcTemplate jdbc, MeetingMapper meetings, JitsiTokenService tokens,
                                      HostCredentialTransferService rotation, HostAuthorityFence fence) {
        this.jdbc = jdbc; this.meetings = meetings; this.tokens = tokens; this.rotation = rotation; this.fence = fence;
    }

    private record Receipt(String source, String target, String instance, String result, long expires) {}
    public record Status(String state, String operationId, String targetSession, String roomInstance,
                         long expiresAt, String identity) {
        public Status(String state, String operationId, String targetSession, String roomInstance, long expiresAt) {
            this(state, operationId, targetSession, roomInstance, expiresAt, null);
        }
    }
    public record RebindResult(String identity) {}

    /** Called only by Prosody after it has authenticated the exact live target
     * occupant. Unlike rotate, this cannot create an operation or change roles. */
    @Transactional(readOnly = true)
    public HostCredentialTransferService.Result claim(String room, String operation, String target, String instance) {
        if (room == null || !room.matches("\\d{9}")
                || operation == null || !operation.matches("[A-Za-z0-9_-]{1,64}")
                || target == null || target.length() > 512 || !target.matches("[^\\s@/]+@[^\\s/]+/[^\\s]+")
                || instance == null || !instance.matches("[A-Za-z0-9_-]{1,128}")) throw conflict();
        var meeting = meetings.findByRoomName(room).orElseThrow(DurableHostTransferService::conflict);
        if (!"RUNNING".equals(meeting.getStatus()) || meeting.getScheduledEndTime() == null
                || !meeting.getScheduledEndTime().isAfter(Instant.now())) throw conflict();
        var receipts = jdbc.query("SELECT target_session, room_instance, result_identity, expires_at "
                        + "FROM host_transfer_operation WHERE room_name=? AND operation_id=?",
                (r, i) -> new Receipt(null, r.getString(1), r.getString(2), r.getString(3), r.getLong(4)),
                room, operation);
        if (receipts.size() != 1) throw conflict();
        Receipt receipt = receipts.get(0);
        if (!target.equals(receipt.target()) || !instance.equals(receipt.instance())
                || receipt.expires() <= Instant.now().getEpochSecond()
                || !tokens.matchesHostIdentity(meeting, receipt.result())) throw conflict();
        var states = jdbc.query("SELECT state FROM meeting_host_authority WHERE room_name=? AND operation_id=?",
                (r, i) -> r.getString(1), room, operation);
        if (states.size() != 1 || !"TRANSFERRING".equals(states.get(0))) throw conflict();
        var previous = jdbc.query("SELECT previous_operation_id FROM host_transfer_recovery_audit WHERE room_name=? AND operation_id=? "
                        + "UNION ALL SELECT previous_operation_id FROM creator_host_reclaim WHERE room_name=? AND operation_id=?",
                (r, i) -> r.getString(1), room, operation, room, operation);
        if (previous.size() > 1) throw conflict();
        return new HostCredentialTransferService.Result(meeting.getHostKey(), receipt.result(), previous.isEmpty() ? null : previous.get(0));
    }

    /** A recovered Prosody instance may bind a new live XMPP session only when
     * that session proves possession of the rotated host key. This keeps the
     * database fence closed; role verification and a later receipt remain required.
     */
    @Transactional
    public RebindResult rebind(String room, String operation, String target, String instance,
                               String expectedInstance, String nonce, String proof) {
        if (room == null || !room.matches("\\d{9}")
                || operation == null || !operation.matches("[A-Za-z0-9_-]{1,64}")
                || target == null || target.length() > 512 || !target.matches("[^\\s@/]+@[^\\s/]+/[^\\s]+")
                || instance == null || !instance.matches("[A-Za-z0-9_-]{1,128}")
                || expectedInstance == null || !expectedInstance.matches("[A-Za-z0-9_-]{1,128}")
                || instance.equals(expectedInstance)
                || nonce == null || !nonce.matches("[A-Za-z0-9_-]{16,128}")
                || proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) throw conflict();
        var meeting = meetings.findByRoomName(room).orElseThrow(DurableHostTransferService::conflict);
        if (!"RUNNING".equals(meeting.getStatus()) || meeting.getScheduledEndTime() == null
                || !meeting.getScheduledEndTime().isAfter(Instant.now())) throw conflict();
        var rows = jdbc.query("SELECT * FROM host_transfer_operation WHERE room_name=? AND operation_id=?",
                (r, i) -> new Receipt(r.getString("source_identity"), r.getString("target_session"),
                        r.getString("room_instance"), r.getString("result_identity"), r.getLong("expires_at")), room, operation);
        if (rows.size() != 1 || rows.get(0).expires() <= Instant.now().getEpochSecond()
                || !tokens.matchesHostIdentity(meeting, rows.get(0).result())) throw conflict();
        var states = jdbc.query("SELECT state FROM meeting_host_authority WHERE room_name=? AND operation_id=?",
                (r, i) -> r.getString(1), room, operation);
        if (states.size() != 1 || !"TRANSFERRING".equals(states.get(0))) throw conflict();
        String message = "host-transfer-rebind-v1\n" + room + "\n" + operation + "\n" + target
                + "\n" + expectedInstance + "\n" + instance + "\n" + nonce;
        if (!validProof(meeting.getHostKey(), message, proof)) throw conflict();
        Receipt prior = rows.get(0);
        if (prior.target().equals(target) && prior.instance().equals(instance)) return new RebindResult(prior.result());
        if (!prior.instance().equals(expectedInstance)) throw conflict();
        int changed = jdbc.update("UPDATE host_transfer_operation SET target_session=?, room_instance=? "
                + "WHERE room_name=? AND operation_id=? AND target_session=? AND room_instance=? "
                + "AND expires_at>? AND EXISTS (SELECT 1 FROM meeting_host_authority "
                + "WHERE room_name=? AND operation_id=? AND state='TRANSFERRING')",
                target, instance, room, operation, prior.target(), prior.instance(),
                Instant.now().getEpochSecond(), room, operation);
        if (changed != 1) throw conflict();
        return new RebindResult(prior.result());
    }

    private static boolean validProof(String key, String message, String proof) {
        if (key == null || key.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)),
                    Base64.getUrlDecoder().decode(proof));
        } catch (Exception ignored) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Status status(String room) {
        if (room == null || !room.matches("\\d{9}")) throw conflict();
        var meeting = meetings.findByRoomName(room).orElseThrow(DurableHostTransferService::conflict);
        if (!"RUNNING".equals(meeting.getStatus()) || meeting.getScheduledEndTime() == null
                || !meeting.getScheduledEndTime().isAfter(Instant.now())) throw conflict();
        var authority = jdbc.query("SELECT state, operation_id FROM meeting_host_authority WHERE room_name=?",
                (r, i) -> new String[] { r.getString("state"), r.getString("operation_id") }, room);
        if (authority.isEmpty()) return new Status("ACTIVE", null, null, null, 0, tokens.hostIdentity(meeting));
        if (authority.size() != 1 || authority.get(0)[1] == null) throw conflict();
        String state = authority.get(0)[0];
        if (!"ACTIVE".equals(state) && !"TRANSFERRING".equals(state)) throw conflict();
        String operation = authority.get(0)[1];
        var receipts = jdbc.query("SELECT * FROM host_transfer_operation WHERE room_name=? AND operation_id=?",
                (r, i) -> new Receipt(r.getString("source_identity"), r.getString("target_session"),
                        r.getString("room_instance"), r.getString("result_identity"), r.getLong("expires_at")), room, operation);
        if (receipts.size() != 1 || !tokens.matchesHostIdentity(meeting, receipts.get(0).result())) throw conflict();
        Receipt receipt = receipts.get(0);
        return new Status(state, operation, receipt.target(), receipt.instance(), receipt.expires(), receipt.result());
    }

    @Transactional
    public HostCredentialTransferService.Result rotate(String room, String identity, String operation,
                                                       String target, String instance) {
        if (room == null || !room.matches("\\d{9}") || identity == null || identity.length() != 51
                || operation == null || !operation.matches("[A-Za-z0-9_-]{1,64}")
                || target == null || target.length() > 512 || !target.matches("[^\\s@/]+@[^\\s/]+/[^\\s]+")
                || instance == null || !instance.matches("[A-Za-z0-9_-]{1,128}")) throw conflict();
        var rows = jdbc.query("SELECT * FROM host_transfer_operation WHERE room_name=? AND operation_id=?",
                (r, i) -> new Receipt(r.getString("source_identity"), r.getString("target_session"),
                        r.getString("room_instance"), r.getString("result_identity"), r.getLong("expires_at")), room, operation);
        if (!rows.isEmpty()) {
            Receipt prior = rows.get(0);
            if (!prior.source().equals(identity) || !prior.target().equals(target) || !prior.instance().equals(instance)
                    || prior.expires() <= Instant.now().getEpochSecond()) throw conflict();
            var meeting = meetings.findByRoomName(room).orElseThrow(DurableHostTransferService::conflict);
            if (!"RUNNING".equals(meeting.getStatus()) || meeting.getScheduledEndTime() == null
                    || !meeting.getScheduledEndTime().isAfter(Instant.now())
                    || !tokens.matchesHostIdentity(meeting, prior.result())) throw conflict();
            return new HostCredentialTransferService.Result(meeting.getHostKey(), prior.result());
        }
        fence.begin(room, operation);
        // Reservation and credential CAS commit/rollback together in the same SQLite transaction.
        jdbc.update("INSERT INTO host_transfer_operation VALUES (?, ?, ?, ?, ?, ?, ?)",
                room, operation, identity, target, instance, "pending", Instant.now().plusSeconds(300).getEpochSecond());
        var result = rotation.rotate(room, identity);
        jdbc.update("UPDATE host_transfer_operation SET result_identity=? WHERE room_name=? AND operation_id=?",
                result.identity(), room, operation);
        return result;
    }

    /** Only the authenticated internal controller may submit a verified target receipt. */
    @Transactional
    public void complete(String room, String operation, String target, String instance) {
        var rows = jdbc.query("SELECT * FROM host_transfer_operation WHERE room_name=? AND operation_id=?",
                (r, i) -> new Receipt(r.getString("source_identity"), r.getString("target_session"),
                        r.getString("room_instance"), r.getString("result_identity"), r.getLong("expires_at")), room, operation);
        if (rows.size() != 1 || !rows.get(0).target().equals(target) || !rows.get(0).instance().equals(instance)
                || rows.get(0).expires() <= Instant.now().getEpochSecond()) throw conflict();
        var meeting = meetings.findByRoomName(room).orElseThrow(DurableHostTransferService::conflict);
        if (!"RUNNING".equals(meeting.getStatus()) || meeting.getScheduledEndTime() == null
                || !meeting.getScheduledEndTime().isAfter(Instant.now())
                || !tokens.matchesHostIdentity(meeting, rows.get(0).result())) throw conflict();
        fence.complete(room, operation);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Transfer operation is invalid, expired or superseded");
    }
}
