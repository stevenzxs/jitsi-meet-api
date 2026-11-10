package com.jitsimeet.api.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

class LifecycleConfigurationTest {
    @TempDir Path temp;
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(LifecycleConfiguration.class, LifecycleController.class)
                .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("config.db")));
    }
    @Test void defaultOffHasNoReceiverOrSchema() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(LifecycleController.class).doesNotHaveBean(LifecycleStore.class);
            assertThat(new JdbcTemplate(ctx.getBean(DataSource.class)).queryForObject(
                    "SELECT count(*) FROM sqlite_master WHERE name LIKE 'lifecycle_%'", Integer.class)).isZero();
        });
    }
    @Test void enabledWithoutCredentialsFailsBeforeMigration() {
        runner().withPropertyValues("lifecycle.enabled=true").run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void enabledValidConfigCreatesReceiver() {
        runner().withPropertyValues("lifecycle.enabled=true", "lifecycle.keys.test.source-id=prosody",
                "lifecycle.keys.test.secret-base64=" + java.util.Base64.getEncoder().encodeToString(LifecycleReceiverTest.KEY),
                "lifecycle.main-domains[0]=muc.meet.jitsi", "lifecycle.breakout-domains[0]=breakout.meet.jitsi")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(LifecycleController.class).hasSingleBean(LifecycleStore.class));
    }

    @Test void credentialStringNeverExposesSecret() {
        assertThat(new LifecycleProperties.Credential("prosody", "DO_NOT_PRINT").toString()).doesNotContain("DO_NOT_PRINT");
    }

    @Test void enabledWeakSecretFailsBeforeMigration() {
        runner().withPropertyValues("lifecycle.enabled=true", "lifecycle.keys.test.source-id=prosody",
                "lifecycle.keys.test.secret-base64=c2hvcnQ=", "lifecycle.main-domains[0]=muc.meet.jitsi",
                "lifecycle.breakout-domains[0]=breakout.meet.jitsi").run(ctx -> assertThat(ctx).hasFailed());
    }
}
