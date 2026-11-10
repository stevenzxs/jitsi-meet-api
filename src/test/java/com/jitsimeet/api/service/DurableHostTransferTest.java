package com.jitsimeet.api.service;

import com.jitsimeet.api.controller.HostRoleCheckController;
import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.dto.HostTokenRequest;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DurableHostTransferTest {
    private static final String ROLE_CHECK_SECRET = "test-only-role-channel-secret-32-characters";
    static final Path DB;
    static { try { DB = Files.createTempDirectory("jitsi-transfer-").resolve("app.db"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("jitsi-token.role-check-secret", () -> ROLE_CHECK_SECRET);
    }
    @Autowired DurableHostTransferService service;
    @Autowired JitsiTokenService tokens;
    @Autowired MeetingMapper meetings;
    @Autowired JdbcTemplate jdbc;
    @Autowired HostAuthorityFence fence;
    @Autowired MeetingService business;
    @Autowired HostRoleCheckController roleCheck;

    @Test void staleAuthorizedSnapshotCannotRestoreOldKeyOrMutateSubjectOrStatus() {
        var meeting = new Meeting("765432198", "原主题", "", "A", "original-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        assertEquals("ACTIVE", service.status("765432198").state());
        var stale = meetings.findByRoomName("765432198").orElseThrow();
        var next = service.rotate("765432198", tokens.hostIdentity(stale), "op-fence",
                "b@test/session", "instance-one");
        assertEquals(0, meetings.updateSubjectAuthorized("765432198", stale.getHostKey(),
                stale.getStatus(), "不允许的修改"));
        assertEquals(0, meetings.updateStatusAuthorized("765432198", stale.getHostKey(),
                stale.getStatus(), Meeting.STATUS_ENDED));
        var current = meetings.findByRoomName("765432198").orElseThrow();
        assertEquals(next.hostKey(), current.getHostKey());
        assertEquals("原主题", current.getSubject());
        assertEquals(Meeting.STATUS_RUNNING, current.getStatus());
        assertEquals(0, meetings.updateSubjectAuthorized("765432198", next.hostKey(), current.getStatus(), "确认前禁止写入"));
        assertFalse(fence.isActive("765432198"));
        var pendingStatus = service.status("765432198");
        assertEquals("TRANSFERRING", pendingStatus.state());
        assertEquals("op-fence", pendingStatus.operationId());
        assertEquals("b@test/session", pendingStatus.targetSession());
        assertEquals("instance-one", pendingStatus.roomInstance());
        assertFalse(roleCheck.check(ROLE_CHECK_SECRET,
                new HostRoleCheckController.Check("765432198", next.identity())));
        assertFalse(new HostAuthorityFence(jdbc).isActive("765432198"));
        assertFalse(business.isBannerWritable("765432198", next.hostKey()));
        assertThrows(ResponseStatusException.class,
                () -> business.hostToken("765432198", new HostTokenRequest(next.hostKey(), "新主持人")));
        service.complete("765432198", "op-fence", "b@test/session", "instance-one");
        assertEquals("ACTIVE", service.status("765432198").state());
        assertTrue(roleCheck.check(ROLE_CHECK_SECRET,
                new HostRoleCheckController.Check("765432198", next.identity())));
        assertTrue(business.isBannerWritable("765432198", next.hostKey()));
        assertEquals(1, meetings.updateSubjectAuthorized("765432198", next.hostKey(), current.getStatus(), "新主持主题"));
    }

    @Test void lostResponseRetriesSameResultAndLaterTransferInvalidatesOldReceipt() {
        var meeting = new Meeting("987654321", "主题不变", "", "A", "original-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        String original = tokens.hostIdentity(meeting);
        var first = service.rotate("987654321", original, "op-one", "b@test/session", "instance-one");
        var retry = service.rotate("987654321", original, "op-one", "b@test/session", "instance-one");
        assertEquals(first, retry);
        var recreated = new DurableHostTransferService(jdbc, meetings, tokens,
                new HostCredentialTransferService(meetings, tokens), fence);
        assertEquals(first, recreated.rotate("987654321", original, "op-one", "b@test/session", "instance-one"));
        assertEquals("TRANSFERRING", recreated.status("987654321").state());
        assertEquals("主题不变", meetings.findByRoomName("987654321").orElseThrow().getSubject());
        assertThrows(ResponseStatusException.class, () -> service.rotate("987654321", original,
                "op-one", "attacker@test/session", "instance-one"));
        assertThrows(ResponseStatusException.class, () -> service.rotate("987654321", original,
                "op-one", "b@test/session", "another-instance"));
        assertThrows(ResponseStatusException.class, () -> service.complete("987654321", "op-one",
                "attacker@test/session", "instance-one"));
        assertFalse(fence.isActive("987654321"));
        recreated.complete("987654321", "op-one", "b@test/session", "instance-one");
        assertDoesNotThrow(() -> service.complete("987654321", "op-one", "b@test/session", "instance-one"));
        service.rotate("987654321", first.identity(), "op-two", "c@test/session", "instance-one");
        assertThrows(ResponseStatusException.class, () -> service.rotate("987654321", original,
                "op-one", "b@test/session", "instance-one"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM host_transfer_operation WHERE room_name='987654321'", Integer.class));
        assertFalse(jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE name='host_transfer_operation'", String.class)
                .contains("host_key"));
    }

    @Test void failedRotationDoesNotLeaveSuccessfulOperation() {
        assertThrows(ResponseStatusException.class, () -> service.rotate("123456789", "forged", "op-fail",
                "b@test/session", "instance-one"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM host_transfer_operation WHERE operation_id='op-fail'", Integer.class));
    }

    @Test void inconsistentDurableAuthorityIsNeverReportedAsActive() {
        var meeting = new Meeting("876543210", "subject", "", "A", "key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        jdbc.update("INSERT INTO meeting_host_authority VALUES (?, 'TRANSFERRING', ?)",
                "876543210", "missing-operation");
        assertThrows(ResponseStatusException.class, () -> service.status("876543210"));
    }

    @Test void newSessionRequiresRotatedKeyProofAndKeepsFenceUntilReceipt() throws Exception {
        String room = "876543211";
        var meeting = new Meeting(room, "subject", "", "A", "old-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        var rotated = service.rotate(room, tokens.hostIdentity(meeting), "op-rebind",
                "old@test/session", "old-instance");
        String target = "new@test/session";
        String instance = "new-instance";
        String nonce = "recovery-challenge-123456";
        String proof = proof(rotated.hostKey(), room, "op-rebind", target, "old-instance", instance, nonce);
        assertThrows(ResponseStatusException.class, () -> service.rebind(room, "op-rebind", target,
                instance, "old-instance", nonce,
                proof("old-key", room, "op-rebind", target, "old-instance", instance, nonce)));
        assertThrows(ResponseStatusException.class, () -> service.rebind(room, "op-rebind", target,
                instance, "old-instance", nonce,
                proof(rotated.hostKey(), "123456789", "op-rebind", target, "old-instance", instance, nonce)));
        assertEquals("old@test/session", service.status(room).targetSession());
        var rebuilt = new DurableHostTransferService(jdbc, meetings, tokens,
                new HostCredentialTransferService(meetings, tokens), new HostAuthorityFence(jdbc));
        assertEquals(rotated.identity(), rebuilt.rebind(room, "op-rebind", target, instance,
                "old-instance", nonce, proof).identity());
        assertEquals(rotated.identity(), service.rebind(room, "op-rebind", target, instance,
                "old-instance", nonce, proof).identity());
        assertEquals(target, service.status(room).targetSession());
        assertEquals(instance, service.status(room).roomInstance());
        assertFalse(fence.isActive(room));
        String anotherProof = proof(rotated.hostKey(), room, "op-rebind", "attacker@test/session",
                "old-instance", "other-instance", nonce);
        assertThrows(ResponseStatusException.class, () -> service.rebind(room, "op-rebind",
                "attacker@test/session", "other-instance", "old-instance", nonce, anotherProof));
        assertThrows(ResponseStatusException.class,
                () -> service.complete(room, "op-rebind", "old@test/session", "old-instance"));
        rebuilt.complete(room, "op-rebind", target, instance);
        assertTrue(fence.isActive(room));
        assertThrows(ResponseStatusException.class,
                () -> service.rebind(room, "op-rebind", target, instance, "old-instance", nonce, proof));
    }

    private static String proof(String key, String room, String operation, String target,
                                String expectedInstance, String instance, String nonce) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String message = "host-transfer-rebind-v1\n" + room + "\n" + operation + "\n" + target
                + "\n" + expectedInstance + "\n" + instance + "\n" + nonce;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void expiredReceiptCannotReturnCredential() {
        var meeting = new Meeting("876543219", "主题", "", "A", "key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        String original = tokens.hostIdentity(meeting);
        service.rotate("876543219", original, "op-expired", "b@test/session", "instance-one");
        jdbc.update("UPDATE host_transfer_operation SET expires_at=0 WHERE room_name=?", "876543219");
        assertThrows(ResponseStatusException.class, () -> service.rotate("876543219", original,
                "op-expired", "b@test/session", "instance-one"));
        assertThrows(ResponseStatusException.class, () -> service.claim("876543219", "op-expired",
                "b@test/session", "instance-one"));
    }

    @Test void expiredMeetingCannotCompletePendingTransferOrOpenFence() {
        String room = "876543213";
        var meeting = new Meeting(room, "subject", "", "A", "old-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        service.rotate(room, tokens.hostIdentity(meeting), "op-meeting-expired",
                "target@test/session", "instance-one");
        jdbc.update("UPDATE meeting SET scheduled_end_time=? WHERE room_name=?",
                Instant.now().minusSeconds(60).toString(), room);
        assertThrows(ResponseStatusException.class, () -> service.complete(room, "op-meeting-expired",
                "target@test/session", "instance-one"));
        assertFalse(fence.isActive(room));
    }

    @Test void exactPendingTargetCanClaimLostCredentialButNoOtherSessionCan() {
        String room = "876543218";
        var meeting = new Meeting(room, "主题", "", "A", "old-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        var rotated = service.rotate(room, tokens.hostIdentity(meeting), "op-claim",
                "target@test/session", "instance-one");
        assertEquals(rotated, service.claim(room, "op-claim", "target@test/session", "instance-one"));
        assertEquals(rotated, service.claim(room, "op-claim", "target@test/session", "instance-one"));
        assertThrows(ResponseStatusException.class,
                () -> service.claim(room, "op-claim", "other@test/session", "instance-one"));
        assertThrows(ResponseStatusException.class,
                () -> service.claim(room, "op-claim", "target@test/session", "other-instance"));
        assertFalse(fence.isActive(room));
        service.complete(room, "op-claim", "target@test/session", "instance-one");
        assertThrows(ResponseStatusException.class,
                () -> service.claim(room, "op-claim", "target@test/session", "instance-one"));
    }
}
