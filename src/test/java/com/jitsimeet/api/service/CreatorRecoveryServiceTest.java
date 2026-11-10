package com.jitsimeet.api.service;

import com.jitsimeet.api.dto.MeetingRequest;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CreatorRecoveryServiceTest {
    static final Path DB;
    static { try { DB = Files.createTempDirectory("jitsi-creator-").resolve("app.db"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    @Autowired MeetingService meetings;
    @Autowired MeetingMapper mapper;
    @Autowired CreatorRecoveryService recovery;
    @Autowired JdbcTemplate jdbc;

    @Test void creatorProofIsIndependentOfTransferableHostKeyAndAbsentFromResponses() {
        String key = "A".repeat(43);
        var response = meetings.createInstant(request("654321987", "host-one", key));
        assertFalse(response.toString().contains(key));
        assertTrue(recovery.matches("654321987", key));
        assertFalse(recovery.matches("654321987", "B".repeat(43)));
        assertFalse(jdbc.queryForObject("SELECT credential_hash FROM meeting_creator_recovery WHERE room_name=?",
                String.class, "654321987").contains(key));
        assertEquals(1, mapper.rotateHostKey("654321987", "host-one", "host-two", "RUNNING"));
        assertTrue(recovery.matches("654321987", key));
        jdbc.update("UPDATE meeting SET status='ENDED' WHERE room_name=?", "654321987");
        meetings.deleteHistory("654321987", "host-two");
        assertFalse(recovery.matches("654321987", key));
    }

    @Test void legacyMeetingCannotClaimCreatorAuthorityAndBadProofRollsBackCreation() {
        meetings.createInstant(request("654321988", "legacy-host", null));
        assertFalse(recovery.matches("654321988", "C".repeat(43)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM meeting_creator_recovery WHERE room_name=?",
                Integer.class, "654321988"));
        assertThrows(ResponseStatusException.class,
                () -> meetings.createInstant(request("654321989", "host", "weak")));
        assertTrue(mapper.findByRoomName("654321989").isEmpty());
    }

    private MeetingRequest request(String room, String hostKey, String recoveryKey) {
        return new MeetingRequest(room, "测试会议", "", "主持人", hostKey, "public-creator-id",
                recoveryKey, null, 60);
    }
}
