package com.jitsimeet.api.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stores a separate, one-time creator capability for future host reclaim. */
@Service
public class CreatorRecoveryService {
    private final JdbcTemplate jdbc;

    public CreatorRecoveryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void register(String roomName, String credential) {
        if (credential == null) return; // Legacy clients create meetings without reclaim authority.
        if (!valid(credential)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid creator credential");
        jdbc.update("INSERT INTO meeting_creator_recovery (room_name, credential_hash) VALUES (?, ?)",
                roomName, digest(roomName, credential));
    }

    public boolean matches(String roomName, String credential) {
        if (roomName == null || !roomName.matches("\\d{9}") || !valid(credential)) return false;
        var rows = jdbc.query("SELECT credential_hash FROM meeting_creator_recovery WHERE room_name=?",
                (result, index) -> result.getString(1), roomName);
        return rows.size() == 1 && MessageDigest.isEqual(
                rows.get(0).getBytes(StandardCharsets.US_ASCII),
                digest(roomName, credential).getBytes(StandardCharsets.US_ASCII));
    }

    public void delete(String roomName) {
        jdbc.update("DELETE FROM meeting_creator_recovery WHERE room_name=?", roomName);
    }

    private static boolean valid(String credential) {
        return credential != null && credential.matches("[A-Za-z0-9_-]{43}");
    }

    private static String digest(String roomName, String credential) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(
                    ("cloudmeet-creator-recovery-v1\n" + roomName + "\n" + credential).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
