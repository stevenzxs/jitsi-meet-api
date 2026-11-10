package com.jitsimeet.api.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite 增量迁移：CREATE TABLE IF NOT EXISTS 不会为已存在的表补列，
 * 这里在应用启动后按需补齐 meeting.creator_id。
 */
@Component
public class DatabaseMigrator {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 注入 JDBC 模板。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public DatabaseMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 启动完成后执行迁移，列已存在时忽略重复添加错误。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        try {
            jdbcTemplate.execute("ALTER TABLE meeting ADD COLUMN creator_id TEXT");
        } catch (Exception ignored) {
            // 列已存在或表不存在时无需处理
        }
    }
}
