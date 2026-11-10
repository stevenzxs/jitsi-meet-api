package com.jitsimeet.api.lifecycle;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

final class LifecycleStore {
    private final DataSource dataSource;
    private static final Map<String, String> SCHEMA = new LinkedHashMap<>();
    static {
        SCHEMA.put("lifecycle_schema", "CREATE TABLE lifecycle_schema (version INTEGER NOT NULL PRIMARY KEY CHECK(version=1))");
        SCHEMA.put("lifecycle_event", "CREATE TABLE lifecycle_event (source TEXT NOT NULL, epoch TEXT NOT NULL, event_id TEXT NOT NULL, instance TEXT NOT NULL, sequence INTEGER NOT NULL, payload TEXT NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY(source,epoch,event_id))");
        SCHEMA.put("lifecycle_event_sequence", "CREATE UNIQUE INDEX lifecycle_event_sequence ON lifecycle_event(source,epoch,instance,sequence)");
        SCHEMA.put("lifecycle_instance", "CREATE TABLE lifecycle_instance (source TEXT NOT NULL, epoch TEXT NOT NULL, instance TEXT NOT NULL, identity TEXT NOT NULL, sequence INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(source,epoch,instance))");
    }
    LifecycleStore(DataSource dataSource) {
        this.dataSource = dataSource;
        try (Connection c = dataSource.getConnection()) {
            begin(c);
            try {
                String versionSchema = scalar(c, "SELECT sql FROM sqlite_master WHERE name=?", "lifecycle_schema");
                if (versionSchema == null) {
                    if (scalar(c, "SELECT name FROM sqlite_master WHERE name LIKE 'lifecycle_%' LIMIT 1") != null)
                        throw new SQLException("partial lifecycle schema");
                    for (String sql : SCHEMA.values()) execute(c, sql);
                    execute(c, "INSERT INTO lifecycle_schema(version) VALUES(1)");
                }
                for (var entry : SCHEMA.entrySet()) {
                    if (!entry.getValue().equals(scalar(c, "SELECT sql FROM sqlite_master WHERE name=?", entry.getKey())))
                        throw new SQLException("lifecycle schema mismatch");
                }
                if (!"1".equals(scalar(c, "SELECT version FROM lifecycle_schema"))) throw new SQLException("unsupported lifecycle schema");
                execute(c, "COMMIT");
            } catch (Exception failure) { rollback(c); throw failure; }
        } catch (Exception failure) { throw new IllegalStateException("lifecycle migration failed", failure); }
    }
    String accept(LifecycleProtocol.Observation event) {
        try (Connection c = dataSource.getConnection()) {
            begin(c);
            try {
                String previous = scalar(c, "SELECT payload FROM lifecycle_event WHERE source=? AND epoch=? AND event_id=?",
                        event.source(), event.epoch(), event.eventId());
                if (previous != null) {
                    if (!previous.equals(event.payload())) throw new LifecycleProtocol.Rejected(409);
                    execute(c, "COMMIT"); return "duplicate";
                }
                String identity = scalar(c, "SELECT identity FROM lifecycle_instance WHERE source=? AND epoch=? AND instance=?",
                        event.source(), event.epoch(), event.instance());
                if (identity != null && !identity.equals(event.identity())) throw new LifecycleProtocol.Rejected(409);
                if (scalar(c, "SELECT event_id FROM lifecycle_event WHERE source=? AND epoch=? AND instance=? AND sequence=?",
                        event.source(), event.epoch(), event.instance(), event.sequence()) != null) throw new LifecycleProtocol.Rejected(409);
                execute(c, "INSERT INTO lifecycle_event VALUES(?,?,?,?,?,?,?)", event.source(), event.epoch(), event.eventId(),
                        event.instance(), event.sequence(), event.payload(), System.currentTimeMillis());
                execute(c, "INSERT INTO lifecycle_instance VALUES(?,?,?,?,?,?) ON CONFLICT(source,epoch,instance) DO UPDATE SET sequence=excluded.sequence,state=excluded.state WHERE excluded.sequence>lifecycle_instance.sequence",
                        event.source(), event.epoch(), event.instance(), event.identity(), event.sequence(),
                        event.type().equals("destroyed") ? "destroyed" : "observed-created");
                execute(c, "COMMIT"); return "accepted";
            } catch (Exception failure) { rollback(c); throw failure; }
        } catch (LifecycleProtocol.Rejected conflict) { throw conflict;
        } catch (Exception failure) { throw new LifecycleProtocol.Rejected(503); }
    }
    private static void begin(Connection c) throws SQLException {
        execute(c, "PRAGMA busy_timeout=1000");
        // Require durable SQLite commits on this connection; no journal-mode changes to shared business DB.
        execute(c, "PRAGMA synchronous=FULL");
        execute(c, "PRAGMA foreign_keys=ON");
        String journal = scalar(c, "PRAGMA journal_mode");
        if (journal == null || !java.util.Set.of("delete", "truncate", "persist", "wal")
                .contains(journal.toLowerCase(java.util.Locale.ROOT))) throw new SQLException("unsafe journal mode");
        execute(c, "BEGIN IMMEDIATE");
    }
    private static void rollback(Connection c) { try { execute(c, "ROLLBACK"); } catch (SQLException ignored) { /* original failure retained */ } }
    private static void execute(Connection c, String sql, Object... params) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
            statement.execute();
        }
    }
    private static String scalar(Connection c, String sql, Object... params) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }
}
