package com.jitsimeet.api.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LifecycleReceiverTest {
    static final String ROUTE = "/internal/lifecycle/v1/events";
    static final long NOW = 1792195200L;
    static final byte[] KEY = "local-test-only-signing-key-32bytes".getBytes(StandardCharsets.UTF_8);
    static final String EPOCH = "00000000-0000-4000-8000-000000000001";
    static final String INSTANCE = "00000000-0000-4000-8000-000000000002";
    @TempDir Path temp;
    JdbcTemplate jdbc;
    LifecycleStore store;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("receiver.db"));
        jdbc = new JdbcTemplate(ds);
        store = new LifecycleStore(ds);
        var credential = new LifecycleProperties.Credential("prosody", Base64.getEncoder().encodeToString(KEY));
        var properties = new LifecycleProperties(Map.of("test-key", credential), List.of("muc.meet.jitsi"),
                List.of("breakout.meet.jitsi"));
        var protocol = new LifecycleProtocol(properties, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new LifecycleController(protocol, store))
                .setControllerAdvice(new com.jitsimeet.api.exception.ApiExceptionHandler()).build();
    }

    Map<String, Object> envelope(String type, String id) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", id); event.put("instanceId", INSTANCE);
        event.put("sequence", type.equals("created") ? 1 : 2); event.put("type", type);
        event.put("roomJid", "123456789@muc.meet.jitsi"); event.put("kind", "main");
        event.put("creationObserved", true); event.put("binding", "unknown"); event.put("parentBinding", "unknown");
        return new LinkedHashMap<>(Map.of("schemaVersion", 1, "sourceId", "prosody", "producerEpoch", EPOCH, "event", event));
    }

    @SuppressWarnings("unchecked") Map<String, Object> event(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("event");
    }

    String sign(byte[] body, String keyId, String time) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String input = "jitsi-lifecycle-v1\n" + keyId + "\n" + time + "\nPOST\n" + ROUTE + "\n" + hash;
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    int send(byte[] body, String keyId, String time, String signature) throws Exception {
        return mvc.perform(post(ROUTE).contentType("application/json").content(body)
                .header("X-Lifecycle-Key-Id", keyId).header("X-Lifecycle-Timestamp", time)
                .header("X-Lifecycle-Signature", signature)).andReturn().getResponse().getStatus();
    }

    int send(Map<String, Object> value) throws Exception {
        byte[] body = json.writeValueAsBytes(value);
        return send(body, "test-key", "" + NOW, sign(body, "test-key", "" + NOW));
    }

    int count() { return jdbc.queryForObject("SELECT count(*) FROM lifecycle_event", Integer.class); }

    @Test void acceptedThenDuplicateAcknowledgesOnce() throws Exception {
        var value = envelope("created", UUID.randomUUID().toString());
        byte[] body = json.writeValueAsBytes(value);
        for (String result : List.of("accepted", "duplicate")) {
            mvc.perform(post(ROUTE).contentType("application/json").content(body)
                    .header("X-Lifecycle-Key-Id", "test-key").header("X-Lifecycle-Timestamp", NOW)
                    .header("X-Lifecycle-Signature", sign(body, "test-key", "" + NOW)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(result));
        }
        assertEquals(1, count());
    }

    @Test void missingWrongUnknownAndExpiredAuthNeverWrites() throws Exception {
        byte[] body = json.writeValueAsBytes(envelope("created", UUID.randomUUID().toString()));
        mvc.perform(post(ROUTE).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        assertEquals(401, send(body, "test-key", "" + NOW, "0".repeat(64)));
        assertEquals(401, send(body, "unknown", "" + NOW, sign(body, "unknown", "" + NOW)));
        String old = "" + (NOW - 301);
        assertEquals(401, send(body, "test-key", old, sign(body, "test-key", old)));
        assertEquals(0, count());
    }

    @Test void sourceMustMatchCredential() throws Exception {
        var value = envelope("created", UUID.randomUUID().toString()); value.put("sourceId", "forged");
        assertEquals(401, send(value)); assertEquals(0, count());
    }

    @Test void oversizeAndDuplicateKeysAreRejected() throws Exception {
        byte[] huge = new byte[16385];
        assertEquals(413, send(huge, "test-key", "" + NOW, sign(huge, "test-key", "" + NOW)));
        byte[] duplicate = "{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        assertEquals(400, send(duplicate, "test-key", "" + NOW, sign(duplicate, "test-key", "" + NOW)));
        assertEquals(0, count());
    }

    @Test void invalidContractsAndDomainsNeverWrite() throws Exception {
        for (var change : List.of(Map.entry("sequence", (Object) true), Map.entry("sequence", 3),
                Map.entry("roomJid", "room@evil.invalid"), Map.entry("password", "secret"),
                Map.entry("parentInstanceId", INSTANCE), Map.entry("creationObserved", false))) {
            var value = envelope("created", UUID.randomUUID().toString()); event(value).put(change.getKey(), change.getValue());
            assertEquals(400, send(value));
        }
        var version = envelope("created", UUID.randomUUID().toString()); version.put("schemaVersion", 2);
        assertEquals(400, send(version)); assertEquals(0, count());
    }

    @Test void reorderedCreationCannotReviveDestroyedInstance() throws Exception {
        assertEquals(200, send(envelope("destroyed", UUID.randomUUID().toString())));
        assertEquals(200, send(envelope("created", UUID.randomUUID().toString())));
        assertEquals("destroyed", jdbc.queryForObject("SELECT state FROM lifecycle_instance", String.class));
        assertEquals(2, count());
    }

    @Test void conflictingEventSequenceAndIdentityRollback() throws Exception {
        var value = envelope("created", UUID.randomUUID().toString()); assertEquals(200, send(value));
        event(value).put("roomJid", "other@muc.meet.jitsi"); assertEquals(409, send(value));
        assertEquals(409, send(envelope("created", UUID.randomUUID().toString())));
        var end = envelope("destroyed", UUID.randomUUID().toString()); event(end).put("roomJid", "other@muc.meet.jitsi");
        assertEquals(409, send(end)); assertEquals(1, count());
    }

    @Test void commitFailureReturnsUnavailableAndRollsBackLedger() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_projection BEFORE INSERT ON lifecycle_instance BEGIN SELECT RAISE(ABORT,'test'); END");
        assertEquals(503, send(envelope("created", UUID.randomUUID().toString()))); assertEquals(0, count());
    }

    @Test void reopenPreservesLedgerAndProjection() throws Exception {
        var value = envelope("created", UUID.randomUUID().toString()); assertEquals(200, send(value));
        new LifecycleStore(jdbc.getDataSource());
        assertEquals(1, count()); assertEquals(200, send(value)); assertEquals(1, count());
    }

    @Test void concurrentDuplicateRequestsNeverDoubleApply() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var value = envelope("created", UUID.randomUUID().toString());
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .<java.util.concurrent.Callable<Integer>>mapToObj(i -> () -> send(value)).toList();
            for (var result : pool.invokeAll(tasks)) assertTrue(List.of(200, 503).contains(result.get()));
            assertEquals(200, send(value)); assertEquals(1, count());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lifecycle_instance", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void damagedSchemaFailsClosed() {
        jdbc.execute("DROP INDEX lifecycle_event_sequence");
        assertThrows(RuntimeException.class, () -> new LifecycleStore(jdbc.getDataSource()));
    }

    @Test void hugeVersionMustNotWrapToOne() throws Exception {
        var value = envelope("created", UUID.randomUUID().toString()); value.put("schemaVersion", 4294967297L);
        assertEquals(400, send(value)); assertEquals(0, count());
    }

    @Test void parentEndDoesNotEndChildAndEpochsStayIsolated() throws Exception {
        assertEquals(200, send(envelope("destroyed", UUID.randomUUID().toString())));
        var child = envelope("created", UUID.randomUUID().toString());
        event(child).put("instanceId", UUID.randomUUID().toString()); event(child).put("kind", "breakout");
        event(child).put("roomJid", "child@breakout.meet.jitsi"); event(child).put("parentInstanceId", INSTANCE);
        event(child).put("parentBinding", "observed"); assertEquals(200, send(child));
        var newer = envelope("created", UUID.randomUUID().toString()); newer.put("producerEpoch", UUID.randomUUID().toString());
        assertEquals(200, send(newer));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM lifecycle_instance WHERE state='observed-created'", Integer.class));
    }

    @Test void lockTimeoutDoesNotAcknowledgeAndRetrySucceeds() throws Exception {
        var value = envelope("created", UUID.randomUUID().toString());
        try (var c = jdbc.getDataSource().getConnection(); var s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            assertEquals(503, send(value)); s.execute("ROLLBACK");
        }
        assertEquals(0, count()); assertEquals(200, send(value)); assertEquals(1, count());
    }

    @Test void deferredCommitFailureRollsBackBothTables() throws Exception {
        // A deferred FK proves COMMIT failure rather than only INSERT failure.
        jdbc.execute("CREATE TABLE commit_parent(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE commit_child(id TEXT REFERENCES commit_parent(id) DEFERRABLE INITIALLY DEFERRED)");
        jdbc.execute("CREATE TRIGGER fail_commit AFTER INSERT ON lifecycle_instance BEGIN INSERT INTO commit_child VALUES('missing'); END");
        assertEquals(503, send(envelope("created", UUID.randomUUID().toString())));
        assertEquals(0, count());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lifecycle_instance", Integer.class));
    }

    @Test void malformedUtf8TrailingJsonAndDuplicateHeadersNeverWrite() throws Exception {
        for (byte[] body : List.of(new byte[]{(byte) 0xc3, 0x28}, "{} {}".getBytes(StandardCharsets.UTF_8))) {
            assertEquals(400, send(body, "test-key", "" + NOW, sign(body, "test-key", "" + NOW)));
        }
        byte[] body = json.writeValueAsBytes(envelope("created", UUID.randomUUID().toString()));
        mvc.perform(post(ROUTE).contentType("application/json").content(body)
                .header("X-Lifecycle-Key-Id", "test-key", "test-key")
                .header("X-Lifecycle-Timestamp", NOW).header("X-Lifecycle-Signature", sign(body, "test-key", "" + NOW)))
                .andExpect(status().isUnauthorized());
        assertEquals(0, count());
    }

    @Test void volatileJournalCannotClaimDurableAcknowledgement() throws Exception {
        try (var ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                "jdbc:sqlite:" + temp.resolve("volatile.db"), true)) {
            new JdbcTemplate(ds).execute("PRAGMA journal_mode=MEMORY");
            assertThrows(IllegalStateException.class, () -> new LifecycleStore(ds));
        }
    }

    @Test void rotatedKeyAndReformattedBodyRemainSameEvent() throws Exception {
        var properties = new LifecycleProperties(Map.of(
                "test-key", new LifecycleProperties.Credential("prosody", Base64.getEncoder().encodeToString(KEY)),
                "rotated-key", new LifecycleProperties.Credential("prosody", Base64.getEncoder().encodeToString(KEY))),
                List.of("muc.meet.jitsi"), List.of("breakout.meet.jitsi"));
        mvc = MockMvcBuilders.standaloneSetup(new LifecycleController(new LifecycleProtocol(properties,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)), store)).build();
        var value = envelope("created", UUID.randomUUID().toString()); assertEquals(200, send(value));
        byte[] formatted = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        String time = "" + (NOW + 1);
        assertEquals(200, send(formatted, "rotated-key", time, sign(formatted, "rotated-key", time)));
        assertEquals(1, count());
    }

    @Test void absentCreationAndSameNameNewInstanceRemainIndependent() throws Exception {
        var missing = envelope("destroyed", UUID.randomUUID().toString());
        event(missing).put("sequence", 1); event(missing).put("creationObserved", false);
        assertEquals(200, send(missing));
        var fresh = envelope("created", UUID.randomUUID().toString());
        event(fresh).put("instanceId", UUID.randomUUID().toString()); assertEquals(200, send(fresh));
        assertEquals(2, count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lifecycle_instance WHERE state='destroyed'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lifecycle_instance WHERE state='observed-created'", Integer.class));
    }

    @Test void signedBodyTamperingAndFutureTimestampNeverWrite() throws Exception {
        byte[] original = json.writeValueAsBytes(envelope("created", UUID.randomUUID().toString()));
        byte[] tampered = java.util.Arrays.copyOf(original, original.length + 1); tampered[tampered.length - 1] = ' ';
        assertEquals(401, send(tampered, "test-key", "" + NOW, sign(original, "test-key", "" + NOW)));
        String future = "" + (NOW + 301);
        assertEquals(401, send(original, "test-key", future, sign(original, "test-key", future)));
        assertEquals(0, count());
    }

    @Test void unknownContentLengthStillBoundsBody() throws Exception {
        mvc.perform(post(ROUTE).contentType("application/json").content(new byte[16385])
                .with(request -> {
                    var unknownLength = org.mockito.Mockito.spy(request);
                    org.mockito.Mockito.doReturn(-1L).when(unknownLength).getContentLengthLong();
                    return unknownLength;
                }))
                .andExpect(status().isPayloadTooLarge());
        assertEquals(0, count());
    }
}
