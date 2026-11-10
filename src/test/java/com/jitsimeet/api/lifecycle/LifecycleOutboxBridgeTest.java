package com.jitsimeet.api.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@Tag("lifecycle-cross-language")
class LifecycleOutboxBridgeTest {
    @TempDir Path temp;

    @Test @Timeout(30)
    void pythonOutboxSignsAndHandlesRealReceiverFailuresWithoutSockets() throws Exception {
        var driver = Path.of(System.getProperty("lifecycle.test.cfg"), "tests", "outbox_spring_driver.py").toAbsolutePath().normalize();
        assertTrue(java.nio.file.Files.exists(driver), "offline Python bridge driver missing");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("receiver.db"));
        var jdbc = new JdbcTemplate(ds);
        var protocol = new LifecycleProtocol(new LifecycleProperties(Map.of("vector-key",
                new LifecycleProperties.Credential("prosody", Base64.getEncoder().encodeToString(
                        "public-offline-vector-key-32bytes!".getBytes(StandardCharsets.UTF_8)))),
                List.of("muc.meet.jitsi"), List.of("breakout.meet.jitsi")),
                Clock.fixed(Instant.ofEpochSecond(1792195200L), ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new LifecycleController(protocol, new LifecycleStore(ds))).build();
        var process = new ProcessBuilder(System.getProperty("lifecycle.test.python", "python"),
                "-u", driver.toString(), temp.resolve("outbox.db").toString())
                .redirectError(temp.resolve("python-stderr.txt").toFile()).start();
        var json = new ObjectMapper();
        BridgeProcess.run(process, Duration.ofSeconds(20), () -> {
          boolean done = false;
          int requests = 0;
          try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var writer = process.getOutputStream()) {
            String line;
            while ((line = reader.readLine()) != null) {
                var message = json.readTree(line);
                if (message.path("command").asText().equals("done")) { done = true; break; }
                Map<String, Object> reply;
                if (message.path("command").asText().equals("fault")) {
                    if (message.path("enabled").asBoolean()) {
                        jdbc.execute("CREATE TRIGGER bridge_fault BEFORE INSERT ON lifecycle_event BEGIN SELECT RAISE(ABORT,'synthetic storage fault'); END");
                    } else jdbc.execute("DROP TRIGGER bridge_fault");
                    reply = Map.of("ok", true);
                } else {
                    assertEquals("request", message.path("command").asText());
                    var response = mvc.perform(post(LifecycleProtocol.ROUTE).contentType("application/json")
                            .content(Base64.getDecoder().decode(message.get("bodyBase64").asText()))
                            .header("X-Lifecycle-Key-Id", "vector-key")
                            .header("X-Lifecycle-Timestamp", message.get("timestamp").asText())
                            .header("X-Lifecycle-Signature", message.get("signature").asText()))
                            .andReturn().getResponse();
                    requests++;
                    reply = Map.of("status", response.getStatus(), "bodyBase64", Base64.getEncoder().encodeToString(response.getContentAsByteArray()),
                            "ledgerCount", jdbc.queryForObject("SELECT count(*) FROM lifecycle_event", Integer.class));
                }
                writer.write((json.writeValueAsString(reply) + "\n").getBytes(StandardCharsets.UTF_8));
                writer.flush();
            }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Python bridge did not exit");
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(temp.resolve("python-stderr.txt")));
            assertTrue(done, "Python assertions did not complete");
            assertEquals(8, requests);
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM lifecycle_event", Integer.class));
          }
        });
    }
}
