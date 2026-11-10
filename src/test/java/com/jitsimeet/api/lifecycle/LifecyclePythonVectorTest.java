package com.jitsimeet.api.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LifecyclePythonVectorTest {
    @TempDir Path temp;

    @Test void pythonMatrixPreservesUtf8ParentIndependenceAndResignedDuplicates() throws Exception {
        JsonNode matrix;
        try (var input = getClass().getResourceAsStream("/lifecycle/python-matrix.json")) {
            assertNotNull(input);
            matrix = new ObjectMapper().readTree(input);
        }
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("matrix.db"));
        var jdbc = new JdbcTemplate(ds);
        var protocol = new LifecycleProtocol(new LifecycleProperties(Map.of(matrix.get("keyId").asText(),
                new LifecycleProperties.Credential("prosody", matrix.get("secretBase64").asText())),
                List.of("muc.meet.jitsi"), List.of("breakout.meet.jitsi")),
                Clock.fixed(Instant.ofEpochSecond(matrix.get("now").asLong()), ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new LifecycleController(protocol, new LifecycleStore(ds))).build();
        assertEquals(10, matrix.get("cases").size());
        int accepted = 0;
        for (var v : matrix.get("cases")) {
            byte[] body = Base64.getDecoder().decode(v.get("bodyBase64").asText());
            assertEquals(v.get("bodySha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)), v.get("name").asText());
            var response = mvc.perform(post(LifecycleProtocol.ROUTE).contentType("application/json").content(body)
                    .header("X-Lifecycle-Key-Id", matrix.get("keyId").asText())
                    .header("X-Lifecycle-Timestamp", v.get("timestamp").asText())
                    .header("X-Lifecycle-Signature", v.get("signature").asText())).andReturn().getResponse();
            assertEquals(v.get("status").asInt(), response.getStatus(), v.get("name").asText());
            if (response.getStatus() == 200) {
                assertEquals(v.get("result").asText(), new ObjectMapper().readTree(response.getContentAsByteArray()).get("result").asText());
                if (v.get("result").asText().equals("accepted")) accepted++;
            }
            assertEquals(accepted, jdbc.queryForObject("SELECT count(*) FROM lifecycle_event", Integer.class));
            if (v.get("name").asText().equals("main-destroyed-before-child")) {
                assertEquals("observed-created", jdbc.queryForObject("SELECT state FROM lifecycle_instance WHERE instance='00000000-0000-4000-8000-000000000004'", String.class));
            }
        }
        assertEquals(6, accepted);
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM lifecycle_instance WHERE state='destroyed'", Integer.class));
    }

    @Test void pythonRawBodyAndSignatureAcceptedDuplicateAndTamperRejected() throws Exception {
        JsonNode v;
        try (var input = getClass().getResourceAsStream("/lifecycle/python-vector.json")) {
            assertNotNull(input);
            v = new ObjectMapper().readTree(input);
        }
        byte[] body = Base64.getDecoder().decode(v.get("bodyBase64").asText());
        assertEquals(v.get("bodySha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("vector.db"));
        var store = new LifecycleStore(ds);
        var protocol = new LifecycleProtocol(new LifecycleProperties(Map.of(v.get("keyId").asText(),
                new LifecycleProperties.Credential("prosody", v.get("secretBase64").asText())),
                List.of("muc.meet.jitsi"), List.of("breakout.meet.jitsi")),
                Clock.fixed(Instant.ofEpochSecond(v.get("timestamp").asLong()), ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new LifecycleController(protocol, store)).build();
        // No Java signature helper: receiver must accept the frozen Python output as-is.
        for (String result : List.of("accepted", "duplicate")) {
            mvc.perform(post(LifecycleProtocol.ROUTE).contentType("application/json").content(body)
                    .header("X-Lifecycle-Key-Id", v.get("keyId").asText())
                    .header("X-Lifecycle-Timestamp", v.get("timestamp").asText())
                    .header("X-Lifecycle-Signature", v.get("signature").asText()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(result));
        }
        byte[] tampered = java.util.Arrays.copyOf(body, body.length + 1);
        tampered[body.length] = ' ';
        mvc.perform(post(LifecycleProtocol.ROUTE).contentType("application/json").content(tampered)
                .header("X-Lifecycle-Key-Id", v.get("keyId").asText())
                .header("X-Lifecycle-Timestamp", v.get("timestamp").asText())
                .header("X-Lifecycle-Signature", v.get("signature").asText()))
                .andExpect(status().isUnauthorized());
        assertEquals(1, new JdbcTemplate(ds).queryForObject("SELECT count(*) FROM lifecycle_event", Integer.class));
    }
}
