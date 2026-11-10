package com.jitsimeet.api.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "lifecycle.enabled=true")
@AutoConfigureMockMvc
class LifecycleEnabledApplicationTest {
    static final Path DB;
    static { try { DB = Files.createTempDirectory("jitsi-lifecycle-enabled-test-").resolve("app.db"); }
        catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("lifecycle.keys.test.source-id", () -> "prosody");
        registry.add("lifecycle.keys.test.secret-base64", () -> Base64.getEncoder().encodeToString(LifecycleReceiverTest.KEY));
        registry.add("lifecycle.main-domains[0]", () -> "muc.meet.jitsi");
        registry.add("lifecycle.breakout-domains[0]", () -> "breakout.meet.jitsi");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Test void realApplicationContextPathSignsLogicalRouteAndCommits() throws Exception {
        String body = """
                {"schemaVersion":1,"sourceId":"prosody","producerEpoch":"00000000-0000-4000-8000-000000000001",
                "event":{"eventId":"00000000-0000-4000-8000-000000000003","instanceId":"00000000-0000-4000-8000-000000000002",
                "sequence":1,"type":"created","roomJid":"123456789@muc.meet.jitsi","kind":"main",
                "creationObserved":true,"binding":"unknown","parentBinding":"unknown"}}
                """;
        String time = "" + Instant.now().getEpochSecond();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        String signed = "jitsi-lifecycle-v1\ntest\n" + time + "\nPOST\n" + LifecycleReceiverTest.ROUTE + "\n" + hash;
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(LifecycleReceiverTest.KEY, "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post("/mapi" + LifecycleReceiverTest.ROUTE).contextPath("/mapi").contentType("application/json").content(body)
                .header("X-Lifecycle-Key-Id", "test").header("X-Lifecycle-Timestamp", time)
                .header("X-Lifecycle-Signature", signature)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("accepted"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lifecycle_event", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM meeting", Integer.class));
    }
}
