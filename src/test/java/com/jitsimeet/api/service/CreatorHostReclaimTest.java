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
class CreatorHostReclaimTest {
    static final Path DB;
    static { try { DB = Files.createTempDirectory("creator-reclaim-").resolve("app.db"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }
    static final String CREDENTIAL = "C".repeat(43);
    @Autowired CreatorHostReclaimService reclaim;
    @Autowired CreatorRecoveryService creators;
    @Autowired DurableHostTransferService transfers;
    @Autowired MeetingMapper meetings;
    @Autowired JitsiTokenService tokens;
    @Autowired HostAuthorityFence fence;
    @Autowired JdbcTemplate jdbc;

    private String meeting(String room, boolean credential) {
        var m = new Meeting(room, "subject", "password", "A", "original", "public-creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(m);
        assertEquals(tokens.hostIdentity(m), transfers.status(room).identity());
        if (credential) creators.register(room, CREDENTIAL);
        return tokens.hostIdentity(m);
    }
    private CreatorHostReclaimService.Result take(String room, String proof, String state, String previous,
                                                  String oldInstance, String identity, String op, String target) {
        return reclaim.reclaim(room, proof, state, previous, oldInstance, identity, op, target, "creator-instance");
    }

    @Test void activeMeetingReclaimsAndExactRetryDoesNotRotateAgain() {
        String room = "754321001", identity = meeting(room, true);
        var next = take(room, CREDENTIAL, "ACTIVE", null, null, identity, "reclaim-one", "creator@test/device");
        assertFalse(fence.isActive(room));
        var key = transfers.claim(room, "reclaim-one", "creator@test/device", "creator-instance");
        assertEquals(next.identity(), key.identity());
        assertNotEquals("original", key.hostKey());
        assertEquals(next, take(room, CREDENTIAL, "ACTIVE", null, null, identity, "reclaim-one", "creator@test/device"));
        assertEquals(key.hostKey(), meetings.findByRoomName(room).orElseThrow().getHostKey());
        assertThrows(ResponseStatusException.class, () -> take(room, "X".repeat(43), "ACTIVE", null, null,
                identity, "reclaim-one", "creator@test/device"));
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "ACTIVE", null, null,
                identity, "reclaim-one", "other@test/device"));
        transfers.complete(room, "reclaim-one", "creator@test/device", "creator-instance");
        assertTrue(fence.isActive(room));
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "ACTIVE", null, null,
                identity, "reclaim-one", "creator@test/device"));
        assertTrue(creators.matches(room, CREDENTIAL));
        assertEquals("subject", meetings.findByRoomName(room).orElseThrow().getSubject());
    }

    @Test void expiredPendingCanBeReplacedAndOldClaimAckAndProofBecomeInvalid() {
        String room = "754321002", identity = meeting(room, true);
        var old = transfers.rotate(room, identity, "old-op", "old@test/session", "old-instance");
        jdbc.update("UPDATE host_transfer_operation SET expires_at=0 WHERE room_name=?", room);
        take(room, CREDENTIAL, "TRANSFERRING", "old-op", "old-instance", old.identity(), "reclaim-two", "creator@test/device");
        var next = transfers.claim(room, "reclaim-two", "creator@test/device", "creator-instance");
        assertEquals("old-op", next.previousOperationId());
        assertNotEquals(old.hostKey(), next.hostKey());
        assertFalse(fence.isActive(room));
        assertThrows(ResponseStatusException.class, () -> transfers.claim(room, "old-op", "old@test/session", "old-instance"));
        assertThrows(ResponseStatusException.class, () -> transfers.complete(room, "old-op", "old@test/session", "old-instance"));
        assertThrows(ResponseStatusException.class, () -> transfers.rebind(room, "old-op", "old@test/new", "new-instance",
                "old-instance", "N".repeat(16), "Z".repeat(43)));
        String record = jdbc.queryForMap("SELECT * FROM creator_host_reclaim WHERE room_name=?", room).toString();
        assertFalse(record.contains(CREDENTIAL));
        assertFalse(record.contains(next.hostKey()));
        transfers.complete(room, "reclaim-two", "creator@test/device", "creator-instance");
        assertTrue(fence.isActive(room));
    }

    @Test void missingWrongCrossRoomCredentialsAndStaleSnapshotNeverMutate() {
        String room = "754321003", identity = meeting(room, false);
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "ACTIVE", null, null, identity, "op", "creator@test/device"));
        creators.register(room, "D".repeat(43));
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "ACTIVE", null, null, identity, "op", "creator@test/device"));
        assertThrows(ResponseStatusException.class, () -> take(room, "D".repeat(43), "ACTIVE", "fake-op", null, identity, "op", "creator@test/device"));
        var old = transfers.rotate(room, identity, "old", "old@test/session", "old-instance");
        assertThrows(ResponseStatusException.class, () -> take(room, "D".repeat(43), "ACTIVE", null, null, old.identity(), "op", "creator@test/device"));
        assertThrows(ResponseStatusException.class, () -> take(room, "D".repeat(43), "TRANSFERRING", "old", "wrong", old.identity(), "op", "creator@test/device"));
        assertEquals(old.hostKey(), meetings.findByRoomName(room).orElseThrow().getHostKey());
        assertEquals("old", transfers.status(room).operationId());
    }

    @Test void completedTransferCanBeReclaimedWithSnapshot() {
        String room = "754321004", identity = meeting(room, true);
        var old = transfers.rotate(room, identity, "done", "old@test/session", "old-instance");
        transfers.complete(room, "done", "old@test/session", "old-instance");
        take(room, CREDENTIAL, "ACTIVE", "done", "old-instance", old.identity(), "take-back", "creator@test/device");
        assertEquals("done", transfers.claim(room, "take-back", "creator@test/device", "creator-instance").previousOperationId());
        assertFalse(fence.isActive(room));
    }

    @Test void livePendingCanBeReplacedButStaleReclaimCannotOverwriteIt() {
        String room = "754321006", identity = meeting(room, true);
        var old = transfers.rotate(room, identity, "live", "old@test/session", "old-instance");
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "TRANSFERRING", "live", "old-instance",
                identity, "wrong-identity", "creator@test/device"));
        take(room, CREDENTIAL, "TRANSFERRING", "live", "old-instance", old.identity(), "take-live", "creator@test/device");
        String key = meetings.findByRoomName(room).orElseThrow().getHostKey();
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "TRANSFERRING", "live", "old-instance",
                old.identity(), "stale", "creator@test/device"));
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "TRANSFERRING", "live", "old-instance",
                old.identity(), "take-live", "other@test/device"));
        jdbc.update("UPDATE host_transfer_operation SET expires_at=0 WHERE room_name=? AND operation_id='take-live'", room);
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "TRANSFERRING", "live", "old-instance",
                old.identity(), "take-live", "creator@test/device"));
        assertEquals(key, meetings.findByRoomName(room).orElseThrow().getHostKey());
        assertFalse(fence.isActive(room));
    }

    @Test void expiredMeetingAndFailedReceiptWriteRollBackReservationAndKey() {
        String room = "754321005", identity = meeting(room, true);
        jdbc.execute("CREATE TRIGGER reject_reclaim BEFORE INSERT ON creator_host_reclaim BEGIN SELECT RAISE(ABORT,'unavailable'); END");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> take(room, CREDENTIAL, "ACTIVE", null, null,
                    identity, "op", "creator@test/device"));
            assertTrue(fence.isActive(room));
            assertEquals("original", meetings.findByRoomName(room).orElseThrow().getHostKey());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM host_transfer_operation WHERE room_name=?", Integer.class, room));
        } finally { jdbc.execute("DROP TRIGGER reject_reclaim"); }
        jdbc.update("UPDATE meeting SET scheduled_end_time=? WHERE room_name=?", Instant.now().minusSeconds(1).toString(), room);
        assertThrows(ResponseStatusException.class, () -> take(room, CREDENTIAL, "ACTIVE", null, null, identity, "op", "creator@test/device"));
        assertTrue(fence.isActive(room));
        assertEquals("original", meetings.findByRoomName(room).orElseThrow().getHostKey());
    }
}
