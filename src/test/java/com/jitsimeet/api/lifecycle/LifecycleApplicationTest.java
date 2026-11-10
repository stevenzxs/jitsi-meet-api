package com.jitsimeet.api.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "lifecycle.enabled=false")
@AutoConfigureMockMvc
class LifecycleApplicationTest {
    static final Path DB;
    static { try { DB = Files.createTempDirectory("jitsi-lifecycle-app-test-").resolve("app.db"); }
        catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Test void applicationDefaultOffHasNoRouteOrLifecycleTables() throws Exception {
        mvc.perform(post("/internal/lifecycle/v1/events").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE name LIKE 'lifecycle_%'", Integer.class));
    }
}
