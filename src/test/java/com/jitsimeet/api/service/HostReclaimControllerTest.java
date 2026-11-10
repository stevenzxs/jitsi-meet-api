package com.jitsimeet.api.service;

import com.jitsimeet.api.controller.HostReclaimController;
import com.jitsimeet.api.controller.HostRoleCheckController;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostReclaimControllerTest {
    @Test void authenticationPrecedesServiceAndResponseDoesNotContainKeys() {
        var auth = mock(HostRoleCheckController.class);
        var service = mock(CreatorHostReclaimService.class);
        var controller = new HostReclaimController(auth, service);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(auth).authenticate("bad");
        var request = new HostReclaimController.Reclaim("123456789", "creator-proof", "ACTIVE", null, null,
                "identity", "op", "creator@test/device", "instance");
        assertThrows(ResponseStatusException.class, () -> controller.reclaim("bad", request));
        verifyNoInteractions(service);
        assertThrows(ResponseStatusException.class, () -> controller.reclaim("valid", null));
        when(service.reclaim("123456789", "creator-proof", "ACTIVE", null, null, "identity", "op", "creator@test/device", "instance"))
                .thenReturn(new CreatorHostReclaimService.Result("op", "new-identity"));
        var response = controller.reclaim("valid", request);
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("op", response.getBody().operationId());
        assertEquals(2, response.getBody().getClass().getRecordComponents().length);
    }
}
