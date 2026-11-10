package com.jitsimeet.api.service;

import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BannerLogoServiceTest {
    private static final Path DIRECTORY;
    static {
        try { DIRECTORY = Files.createTempDirectory("jitsi-logos-"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DIRECTORY.resolve("app.db"));
        registry.add("banner.logo-directory", () -> DIRECTORY.resolve("logos").toString());
    }

    @Autowired BannerLogoService logos;
    @Autowired DurableHostTransferService transfers;
    @Autowired JitsiTokenService tokens;
    @Autowired MeetingMapper meetings;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test void publishedLogoIsReadableByNginxWorkerOnPosix() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(DIRECTORY).supportsFileAttributeView("posix"));
        String room = "876543213";
        meetings.insert(new Meeting(room, "subject", "", "A", "read-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING));
        logos.put(room, room, "public.png", "read-key", new byte[] { 1 }, "image/png");
        var file = DIRECTORY.resolve("logos").resolve(room).resolve("public.png");
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"),
                Files.getPosixFilePermissions(file));
    }

    @Test void logoWriteHoldsDatabaseWriterLockUntilTransactionEnds() throws Exception {
        String room = "876543215";
        var meeting = new Meeting(room, "subject", "", "A", "old-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            logos.put(room, room, "locked.png", "old-key", new byte[] { 7 }, "image/png");
            written.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Writer release timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }));
        try {
            assertTrue(written.await(10, TimeUnit.SECONDS));
            // An independent connection cannot commit rotation while a logo write
            // has touched the filesystem but its enclosing transaction is open.
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + DIRECTORY.resolve("app.db"));
                 var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=100");
                var error = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                        "UPDATE meeting SET host_key='unauthorized-new-key' WHERE room_name='" + room + "'"));
                assertEquals(5, error.getErrorCode()); // SQLITE_BUSY
            }
        } finally {
            release.countDown();
            try { writer.get(10, TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); }
        }
        var rotated = transfers.rotate(room, tokens.hostIdentity(meeting), "op-logo-lock",
                "target@test/session", "instance-one");
        assertThrows(ResponseStatusException.class,
                () -> logos.delete(room, room, "locked.png", "old-key"));
        assertArrayEquals(new byte[] { 7 }, Files.readAllBytes(DIRECTORY.resolve("logos").resolve(room).resolve("locked.png")));
        assertFalse(rotated.hostKey().equals("old-key"));
    }

    @Test void filesystemFailureRollsBackWriterLockAndAllowsLaterTransfer() throws Exception {
        String room = "876543214";
        var meeting = new Meeting(room, "subject", "", "A", "old-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        Files.createDirectories(DIRECTORY.resolve("logos"));
        Files.write(DIRECTORY.resolve("logos").resolve(room), new byte[] { 1 });
        var error = assertThrows(ResponseStatusException.class,
                () -> logos.put(room, room, "blocked.png", "old-key", new byte[] { 7 }, "image/png"));
        assertEquals(500, error.getStatusCode().value());
        assertEquals("old-key", meetings.findByRoomName(room).orElseThrow().getHostKey());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM meeting_host_authority WHERE room_name=?", Integer.class, room));
        assertDoesNotThrow(() -> transfers.rotate(room, tokens.hostIdentity(meeting), "op-logo-failure",
                "target@test/session", "instance-one"));
    }

    @Test void fileWritesFollowCurrentHostAndTransferFence() throws Exception {
        String room = "876543217";
        String name = "sample.png";
        var meeting = new Meeting(room, "subject", "", "A", "old-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        meetings.insert(meeting);
        byte[] first = { 1, 2, 3 };
        logos.put(room, "library", name, "old-key", first, "image/png");
        assertArrayEquals(first, Files.readAllBytes(DIRECTORY.resolve("logos/library").resolve(name)));

        var rotated = transfers.rotate(room, tokens.hostIdentity(meeting), "op-logo",
                "target@test/session", "instance-one");
        assertThrows(ResponseStatusException.class,
                () -> logos.put(room, "library", name, "old-key", new byte[] { 4 }, "image/png"));
        assertThrows(ResponseStatusException.class,
                () -> logos.put(room, "library", name, rotated.hostKey(), new byte[] { 4 }, "image/png"));
        assertThrows(ResponseStatusException.class,
                () -> logos.delete(room, "library", name, rotated.hostKey()));
        assertArrayEquals(first, Files.readAllBytes(DIRECTORY.resolve("logos/library").resolve(name)));

        transfers.complete(room, "op-logo", "target@test/session", "instance-one");
        logos.put(room, "library", name, rotated.hostKey(), new byte[] { 5 }, "image/png");
        assertArrayEquals(new byte[] { 5 }, Files.readAllBytes(DIRECTORY.resolve("logos/library").resolve(name)));
        logos.delete(room, "library", name, rotated.hostKey());
        assertFalse(Files.exists(DIRECTORY.resolve("logos/library").resolve(name)));
    }

    @Test void invalidScopeAndPayloadNeverCreateFiles() {
        assertThrows(ResponseStatusException.class,
                () -> logos.put("876543217", "876543216", "x.png", "key", new byte[] { 1 }, "image/png"));
        assertThrows(ResponseStatusException.class,
                () -> logos.put("876543217", "library", "../x.png", "key", new byte[] { 1 }, "image/png"));
        assertThrows(ResponseStatusException.class,
                () -> logos.put("876543217", "library", "x.png", "key", new byte[2 * 1024 * 1024 + 1], "image/png"));
        assertThrows(ResponseStatusException.class,
                () -> logos.put("876543217", "library", "x.svg", "key", new byte[] { 1 }, "image/png"));
    }
}
