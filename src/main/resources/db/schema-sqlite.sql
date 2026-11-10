CREATE TABLE IF NOT EXISTS meeting (
    id TEXT NOT NULL PRIMARY KEY,
    room_name TEXT NOT NULL,
    subject TEXT NOT NULL,
    password TEXT,
    display_name TEXT,
    host_key TEXT,
    creator_id TEXT,
    scheduled_start_time TEXT NOT NULL,
    scheduled_end_time TEXT NOT NULL,
    started_at TEXT,
    ended_at TEXT,
    status TEXT NOT NULL,
    join_url TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_meeting_room_name ON meeting (room_name);

-- Creator recovery authority is independent from the transferable host key.
-- Older meetings have no row and cannot reclaim host authority implicitly.
CREATE TABLE IF NOT EXISTS meeting_creator_recovery (
    room_name TEXT NOT NULL PRIMARY KEY,
    credential_hash TEXT NOT NULL
);

-- Transfer receipts contain no additional copy of the secret credential.
CREATE TABLE IF NOT EXISTS host_transfer_operation (
    room_name TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    source_identity TEXT NOT NULL,
    target_session TEXT NOT NULL,
    room_instance TEXT NOT NULL,
    result_identity TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY (room_name, operation_id)
);
-- A transfer blocks both old and new host credentials until receipt is confirmed.
CREATE TABLE IF NOT EXISTS meeting_host_authority (
    room_name TEXT NOT NULL PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'TRANSFERRING')),
    operation_id TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_meeting_visible_time ON meeting (status, scheduled_start_time);

-- Operator recovery audit deliberately excludes credentials and request payloads.
CREATE TABLE IF NOT EXISTS creator_host_reclaim (
    room_name TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    expected_state TEXT NOT NULL,
    previous_operation_id TEXT,
    expected_instance TEXT,
    source_identity TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (room_name, operation_id)
);

CREATE TABLE IF NOT EXISTS host_transfer_recovery_audit (
    room_name TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    previous_operation_id TEXT NOT NULL,
    operator_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (room_name, operation_id)
);

-- 会议电子屏状态：主持人保存后落库，迟到入会者进会时主动拉取，不依赖主持人在线。
CREATE TABLE IF NOT EXISTS meeting_banner (
    room_name TEXT NOT NULL PRIMARY KEY,
    visible INTEGER NOT NULL DEFAULT 0,
    text TEXT NOT NULL DEFAULT '',
    font_size INTEGER NOT NULL DEFAULT 32,
    color TEXT NOT NULL DEFAULT '#FFFFFF',
    font_family TEXT NOT NULL DEFAULT '',
    bg_color TEXT NOT NULL DEFAULT '',
    logo_url TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL DEFAULT 0
);

-- 已有数据库的会标列迁移：SQLite 的 ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报错，由 continue-on-error 跳过。
ALTER TABLE meeting_banner ADD COLUMN logo_url TEXT NOT NULL DEFAULT '';
