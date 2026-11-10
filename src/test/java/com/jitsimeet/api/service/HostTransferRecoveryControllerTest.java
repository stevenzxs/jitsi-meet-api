package com.jitsimeet.api.service;

import com.jitsimeet.api.controller.HostTransferRecoveryController;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostTransferRecoveryControllerTest {
    private static final String ADMIN = "test-only-admin-recovery-secret-32-characters";
    private static final String ROLE = "test-only-role-channel-secret-32-characters";

    @Test void resumeRequiresIndependentAdminAndNeverReturnsKey() {
        var service = mock(HostTransferRecoveryService.class);
        var controller = new HostTransferRecoveryController(service, ADMIN, ROLE);
        var request = new HostTransferRecoveryController.Resume("123456789", "old", "new", "target@test/session", "instance");
        assertThrows(ResponseStatusException.class, () -> controller.resume(ROLE, request));
        assertThrows(ResponseStatusException.class, () -> controller.resume(null, request));
        verifyNoInteractions(service);
        when(service.resume("123456789", "old", "new", "target@test/session", "instance"))
                .thenReturn(new HostTransferRecoveryService.Result("new", "identity"));
        var result = controller.resume(ADMIN, request);
        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertEquals("new", result.getBody().operationId());
    }

    @Test void disabledWrongOrRoleCredentialsNeverReachService() {
        var service = mock(HostTransferRecoveryService.class);
        for (String configured : new String[] { "", "short", ROLE, ADMIN }) {
            var controller = new HostTransferRecoveryController(service, configured, ROLE);
            assertThrows(ResponseStatusException.class, () -> controller.replace(null, null));
            assertThrows(ResponseStatusException.class, () -> controller.replace(ROLE, null));
            assertThrows(ResponseStatusException.class, () -> controller.replace("wrong", null));
            if (!configured.equals(ADMIN))
                assertThrows(ResponseStatusException.class, () -> controller.replace(configured, null));
        }
        verifyNoInteractions(service);
    }

    @Test void validAdminDelegatesAndReturnsOnlyNonSecretReceipt() {
        var service = mock(HostTransferRecoveryService.class);
        var controller = new HostTransferRecoveryController(service, ADMIN, ROLE);
        var request = new HostTransferRecoveryController.Replacement("123456789", "old-op", "old-instance",
                "identity", "new-op", "new@test/session", "new-instance", "operator", "ticket-1");
        when(service.replace("123456789", "old-op", "old-instance", "identity", "new-op",
                "new@test/session", "new-instance", "operator", "ticket-1"))
                .thenReturn(new HostTransferRecoveryService.Result("new-op", "new-identity"));
        assertThrows(ResponseStatusException.class, () -> controller.replace(ADMIN, null));
        var response = controller.replace(ADMIN, request);
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("new-op", response.getBody().operationId());
        verify(service).replace("123456789", "old-op", "old-instance", "identity", "new-op",
                "new@test/session", "new-instance", "operator", "ticket-1");
        assertEquals(2, response.getBody().getClass().getRecordComponents().length);
    }
}
