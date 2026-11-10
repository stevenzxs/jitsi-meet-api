package com.jitsimeet.api.service;

import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HostTransferRecoveryTest {
    static final Path DB;
    static { try { DB = Files.createTempDirectory("host-admin-recovery-").resolve("app.db"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }
    @Autowired HostTransferRecoveryService recovery;
    @Autowired DurableHostTransferService transfers;
    @Autowired HostAuthorityFence fence;
    @Autowired MeetingMapper meetings;
    @Autowired JitsiTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreatorRecoveryService creators;

    private HostCredentialTransferService.Result pending(String room) {
        var meeting = new Meeting(room, "unchanged", "password", "A", "original", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        return transfers.rotate(room, tokens.hostIdentity(meeting), "old-op", "old@test/session", "old-instance");
    }

    @Test void expiredReceiptCanBeReplacedButOnlyNewTargetCanFinish() {
        String room = "654321001";
        var old = pending(room);
        creators.register(room, "A".repeat(43));
        jdbc.update("UPDATE host_transfer_operation SET expires_at=0 WHERE room_name=?", room);
        var next = recovery.replace(room, "old-op", "old-instance", old.identity(), "recovery-op",
                "new@test/session", "new-instance", "operator-1", "ticket-123");
        assertFalse(fence.isActive(room));
        assertEquals("recovery-op", transfers.status(room).operationId());
        var claimed = transfers.claim(room, "recovery-op", "new@test/session", "new-instance");
        assertEquals(next.identity(), claimed.identity());
        assertEquals("old-op", claimed.previousOperationId());
        assertEquals(next, recovery.resume(room, "old-op", "recovery-op", "new@test/session", "new-instance"));
        assertThrows(ResponseStatusException.class, () -> recovery.resume(room, "wrong", "recovery-op", "new@test/session", "new-instance"));
        assertThrows(ResponseStatusException.class, () -> recovery.resume(room, "old-op", "recovery-op", "other@test/session", "new-instance"));
        assertThrows(ResponseStatusException.class, () -> recovery.resume(room, "old-op", "recovery-op", "new@test/session", "wrong-instance"));
        assertNotEquals(old.hostKey(), claimed.hostKey());
        assertTrue(creators.matches(room, "A".repeat(43)));
        assertEquals("unchanged", meetings.findByRoomName(room).orElseThrow().getSubject());
        assertThrows(ResponseStatusException.class, () -> transfers.complete(room, "old-op", "old@test/session", "old-instance"));
        assertThrows(ResponseStatusException.class, () -> transfers.claim(room, "old-op", "old@test/session", "old-instance"));
        assertThrows(ResponseStatusException.class, () -> recovery.replace(room, "old-op", "old-instance", old.identity(),
                "recovery-op", "new@test/session", "new-instance", "operator-1", "ticket-123"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM host_transfer_recovery_audit WHERE room_name=?", Integer.class, room));
        String audit = jdbc.queryForMap("SELECT * FROM host_transfer_recovery_audit WHERE room_name=?", room).toString();
        assertFalse(audit.contains(old.hostKey()));
        assertFalse(audit.contains(claimed.hostKey()));
        transfers.complete(room, "recovery-op", "new@test/session", "new-instance");
        assertTrue(fence.isActive(room));
        assertThrows(ResponseStatusException.class, () -> recovery.resume(room, "old-op", "recovery-op", "new@test/session", "new-instance"));
    }

    @Test void staleSnapshotOrDuplicateOperationNeverMutatesAuthority() {
        String room = "654321002";
        var old = pending(room);
        for (String[] snapshot : new String[][] { {"wrong", "old-instance", old.identity()},
                {"old-op", "wrong", old.identity()}, {"old-op", "old-instance", "host-v1-" + "Z".repeat(43)} }) {
            assertThrows(ResponseStatusException.class, () -> recovery.replace(room, snapshot[0], snapshot[1], snapshot[2],
                    "new-op", "new@test/session", "new-instance", "operator", "ticket"));
        }
        assertThrows(ResponseStatusException.class, () -> recovery.replace(room, "old-op", "old-instance", old.identity(),
                "old-op", "new@test/session", "new-instance", "operator", "ticket"));
        assertThrows(ResponseStatusException.class, () -> recovery.replace(room, "old-op", "old-instance", old.identity(),
                "new-op", "new@test/session", "old-instance", "operator", "ticket"));
        assertEquals(old.hostKey(), meetings.findByRoomName(room).orElseThrow().getHostKey());
        assertEquals("old-op", transfers.status(room).operationId());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM host_transfer_recovery_audit WHERE room_name=?", Integer.class, room));
    }

    @Test void expiredMeetingRollsBackAuthorityReplacement() {
        String room = "654321003";
        var old = pending(room);
        jdbc.update("UPDATE meeting SET scheduled_end_time=? WHERE room_name=?", Instant.now().minusSeconds(1).toString(), room);
        assertThrows(ResponseStatusException.class, () -> recovery.replace(room, "old-op", "old-instance", old.identity(),
                "new-op", "new@test/session", "new-instance", "operator", "ticket"));
        assertEquals("old-op", jdbc.queryForObject("SELECT operation_id FROM meeting_host_authority WHERE room_name=?", String.class, room));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM host_transfer_operation WHERE room_name=?", Integer.class, room));
        assertEquals(old.hostKey(), meetings.findByRoomName(room).orElseThrow().getHostKey());
    }

    @Test void auditFailureRollsBackCredentialReceiptAndAuthorityTogether() {
        String room = "654321004";
        var old = pending(room);
        jdbc.execute("CREATE TRIGGER reject_recovery_audit BEFORE INSERT ON host_transfer_recovery_audit "
                + "WHEN NEW.room_name='654321004' BEGIN SELECT RAISE(ABORT, 'audit unavailable'); END");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> recovery.replace(room,
                    "old-op", "old-instance", old.identity(), "new-op", "new@test/session", "new-instance", "operator", "ticket"));
            assertEquals(old.hostKey(), meetings.findByRoomName(room).orElseThrow().getHostKey());
            assertEquals("old-op", transfers.status(room).operationId());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM host_transfer_operation WHERE room_name=?", Integer.class, room));
            assertFalse(fence.isActive(room));
        } finally { jdbc.execute("DROP TRIGGER reject_recovery_audit"); }
    }

    @Test void completedOperationCannotBeReplaced() {
        String room = "654321005";
        var old = pending(room);
        transfers.complete(room, "old-op", "old@test/session", "old-instance");
        assertThrows(ResponseStatusException.class, () -> recovery.replace(room, "old-op", "old-instance", old.identity(),
                "new-op", "new@test/session", "new-instance", "operator", "ticket"));
        assertTrue(fence.isActive(room));
        assertEquals(old.hostKey(), meetings.findByRoomName(room).orElseThrow().getHostKey());
    }

    @Test void resumeRejectsExpiredReceiptAndMeetingWithoutRerotation() {
        String room = "654321006";
        var old = pending(room);
        recovery.replace(room, "old-op", "old-instance", old.identity(), "new-op",
                "new@test/session", "new-instance", "operator", "ticket");
        String key = meetings.findByRoomName(room).orElseThrow().getHostKey();
        jdbc.update("UPDATE host_transfer_operation SET expires_at=0 WHERE room_name=? AND operation_id='new-op'", room);
        assertThrows(ResponseStatusException.class, () -> recovery.resume(room, "old-op", "new-op", "new@test/session", "new-instance"));
        jdbc.update("UPDATE host_transfer_operation SET expires_at=? WHERE room_name=? AND operation_id='new-op'",
                Instant.now().plusSeconds(300).getEpochSecond(), room);
        jdbc.update("UPDATE meeting SET scheduled_end_time=? WHERE room_name=?", Instant.now().minusSeconds(1).toString(), room);
        assertThrows(ResponseStatusException.class, () -> recovery.resume(room, "old-op", "new-op", "new@test/session", "new-instance"));
        assertEquals(key, meetings.findByRoomName(room).orElseThrow().getHostKey());
        assertFalse(fence.isActive(room));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM host_transfer_recovery_audit WHERE room_name=?", Integer.class, room));
    }
}
