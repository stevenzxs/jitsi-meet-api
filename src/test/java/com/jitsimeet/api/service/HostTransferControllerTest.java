package com.jitsimeet.api.service;

import com.jitsimeet.api.config.JitsiTokenProperties;
import com.jitsimeet.api.controller.HostRoleCheckController;
import com.jitsimeet.api.controller.HostTransferController;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostTransferControllerTest {
    @Test void unauthenticatedRequestNeverReachesRotation() {
        var config = new JitsiTokenProperties();
        config.setRoleCheckSecret("test-only-secret-at-least-32-characters");
        var service = mock(DurableHostTransferService.class);
        var auth = new HostRoleCheckController(mock(MeetingMapper.class), mock(JitsiTokenService.class), config,
                mock(HostAuthorityFence.class));
        var controller = new HostTransferController(auth, service);
        assertThrows(ResponseStatusException.class, () -> controller.rotate(null, null));
        assertThrows(ResponseStatusException.class, () -> controller.rotate("wrong", null));
        verifyNoInteractions(service);
    }

    @Test void credentialResponseIsNotCacheable() {
        var config = new JitsiTokenProperties();
        String secret = "test-only-secret-at-least-32-characters";
        config.setRoleCheckSecret(secret);
        var service = mock(DurableHostTransferService.class);
        when(service.rotate("123456789", "identity", "op", "target@test/session", "instance"))
                .thenReturn(new HostCredentialTransferService.Result("new-key", "new-id"));
        var auth = new HostRoleCheckController(mock(MeetingMapper.class), mock(JitsiTokenService.class), config,
                mock(HostAuthorityFence.class));
        var response = new HostTransferController(auth, service).rotate(secret,
                new HostTransferController.Transfer("123456789", "identity", "op", "target@test/session", "instance"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("new-key", response.getBody().hostKey());
    }

    @Test void confirmationRequiresServiceSecretAndDelegatesBoundReceipt() {
        var config = new JitsiTokenProperties();
        String secret = "test-only-secret-at-least-32-characters";
        config.setRoleCheckSecret(secret);
        var service = mock(DurableHostTransferService.class);
        var auth = new HostRoleCheckController(mock(MeetingMapper.class), mock(JitsiTokenService.class), config,
                mock(HostAuthorityFence.class));
        var controller = new HostTransferController(auth, service);
        var confirmation = new HostTransferController.Confirmation("123456789", "op",
                "target@test/session", "instance");
        assertThrows(ResponseStatusException.class, () -> controller.complete(null, confirmation));
        assertThrows(ResponseStatusException.class, () -> controller.complete("wrong", confirmation));
        verifyNoInteractions(service);
        var response = controller.complete(secret, confirmation);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(service).complete("123456789", "op", "target@test/session", "instance");
    }

    @Test void durableStatusRequiresServiceSecretAndNeverReturnsCredential() {
        var config = new JitsiTokenProperties();
        String secret = "test-only-secret-at-least-32-characters";
        config.setRoleCheckSecret(secret);
        var service = mock(DurableHostTransferService.class);
        var auth = new HostRoleCheckController(mock(MeetingMapper.class), mock(JitsiTokenService.class), config,
                mock(HostAuthorityFence.class));
        var controller = new HostTransferController(auth, service);
        var request = new HostTransferController.StatusRequest("123456789");
        assertThrows(ResponseStatusException.class, () -> controller.status(null, request));
        verifyNoInteractions(service);
        var expected = new DurableHostTransferService.Status("TRANSFERRING", "op", "target@test/session",
                "instance", 123L);
        when(service.status("123456789")).thenReturn(expected);
        var response = controller.status(secret, request);
        assertEquals(expected, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertFalse(response.getBody().toString().contains("hostKey"));
    }

    @Test void recoveredSessionProofOnlyPassesThroughAuthenticatedServerRoute() {
        var config = new JitsiTokenProperties();
        String secret = "test-only-secret-at-least-32-characters";
        config.setRoleCheckSecret(secret);
        var service = mock(DurableHostTransferService.class);
        var auth = new HostRoleCheckController(mock(MeetingMapper.class), mock(JitsiTokenService.class), config,
                mock(HostAuthorityFence.class));
        var controller = new HostTransferController(auth, service);
        var request = new HostTransferController.Rebind("123456789", "op", "new@test/session",
                "new-instance", "old-instance", "challenge-1234567890", "proof");
        assertThrows(ResponseStatusException.class, () -> controller.rebind(null, request));
        verifyNoInteractions(service);
        when(service.rebind("123456789", "op", "new@test/session", "new-instance",
                "old-instance", "challenge-1234567890", "proof"))
                .thenReturn(new DurableHostTransferService.RebindResult("identity"));
        var response = controller.rebind(secret, request);
        assertEquals("identity", response.getBody().identity());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test void lostCredentialClaimRequiresServiceSecretAndDisablesCaching() {
        var config = new JitsiTokenProperties();
        String secret = "test-only-secret-at-least-32-characters";
        config.setRoleCheckSecret(secret);
        var service = mock(DurableHostTransferService.class);
        var auth = new HostRoleCheckController(mock(MeetingMapper.class), mock(JitsiTokenService.class), config,
                mock(HostAuthorityFence.class));
        var controller = new HostTransferController(auth, service);
        var request = new HostTransferController.Confirmation("123456789", "op", "target@test/session", "instance");
        assertThrows(ResponseStatusException.class, () -> controller.claim(null, request));
        verifyNoInteractions(service);
        var expected = new HostCredentialTransferService.Result("new-key", "new-id");
        when(service.claim("123456789", "op", "target@test/session", "instance")).thenReturn(expected);
        var response = controller.claim(secret, request);
        assertEquals(expected, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }
}
