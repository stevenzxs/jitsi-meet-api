package com.jitsimeet.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Serializes logo file writes with host-key rotation using SQLite's writer lock. */
@Service
public class BannerLogoService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final MeetingService meetings;
    private final JdbcTemplate jdbc;
    private final Path root;

    public BannerLogoService(MeetingService meetings, JdbcTemplate jdbc,
                             @Value("${banner.logo-directory:/data/banner-logos}") String directory) {
        this.meetings = meetings;
        this.jdbc = jdbc;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Transactional
    public void put(String room, String scope, String name, String key, byte[] bytes, String contentType) {
        Path target = target(room, scope, name);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES || !matchesType(name, contentType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid logo image");
        lockAuthorizedWrite(room, key);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".logo-", ".tmp");
            Files.write(temporary, bytes);
            // Public logos are served by a different UID (nginx worker). Apply
            // read-only sharing before atomic publication, not to existing files.
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Logo write failed", error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    @Transactional
    public void delete(String room, String scope, String name, String key) {
        Path target = target(room, scope, name);
        lockAuthorizedWrite(room, key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Logo delete failed", error);
        }
    }

    private void lockAuthorizedWrite(String room, String key) {
        if (!meetings.isBannerWritable(room, key)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        // This no-op UPDATE obtains the SQLite writer lock before touching the
        // filesystem. Rotation must wait; its credential CAS cannot commit first.
        int changed = jdbc.update("UPDATE meeting SET host_key=host_key WHERE room_name=? AND host_key=? "
                + "AND NOT EXISTS (SELECT 1 FROM meeting_host_authority "
                + "WHERE room_name=? AND state<>'ACTIVE')", room, key, room);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private Path target(String room, String scope, String name) {
        if (room == null || !room.matches("\\d{9}") || scope == null
                || !(scope.equals("library") || scope.equals(room)) || name == null
                || !name.matches("[A-Za-z0-9_-]+\\.(?:png|jpe?g|svg|webp)"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid logo path");
        return root.resolve(scope).resolve(name);
    }

    private boolean matchesType(String name, String type) {
        return switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "png" -> "image/png".equals(type);
            case "jpg", "jpeg" -> "image/jpeg".equals(type);
            case "svg" -> "image/svg+xml".equals(type);
            case "webp" -> "image/webp".equals(type);
            default -> false;
        };
    }
}
