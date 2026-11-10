package com.jitsimeet.api.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Durable fail-closed gate for business writes during host handoff. */
@Service
public class HostAuthorityFence {
    private final JdbcTemplate jdbc;

    public HostAuthorityFence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isActive(String room) {
        var states = jdbc.query("SELECT state FROM meeting_host_authority WHERE room_name=?",
                (result, index) -> result.getString(1), room);
        return states.isEmpty() || (states.size() == 1 && "ACTIVE".equals(states.get(0)));
    }

    public void requireActive(String room) {
        if (!isActive(room)) throw conflict();
    }

    public void begin(String room, String operation) {
        int changed = jdbc.update("INSERT INTO meeting_host_authority (room_name, state, operation_id) VALUES (?, 'TRANSFERRING', ?) "
                + "ON CONFLICT(room_name) DO UPDATE SET state='TRANSFERRING', operation_id=excluded.operation_id "
                + "WHERE meeting_host_authority.state='ACTIVE'", room, operation);
        if (changed != 1) throw conflict();
    }

    public void complete(String room, String operation) {
        int changed = jdbc.update("UPDATE meeting_host_authority SET state='ACTIVE' "
                + "WHERE room_name=? AND operation_id=? AND state='TRANSFERRING'", room, operation);
        if (changed == 1) return;
        var states = jdbc.query("SELECT state FROM meeting_host_authority WHERE room_name=? AND operation_id=?",
                (result, index) -> result.getString(1), room, operation);
        if (states.size() != 1 || !"ACTIVE".equals(states.get(0))) throw conflict();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Host authority is transferring");
    }
}
