package com.jitsimeet.api.lifecycle;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.io.IOException;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "lifecycle.enabled", havingValue = "true")
final class LifecycleController {
    private final LifecycleProtocol protocol;
    private final LifecycleStore store;
    LifecycleController(LifecycleProtocol protocol, LifecycleStore store) { this.protocol = protocol; this.store = store; }

    @PostMapping(value = LifecycleProtocol.ROUTE, consumes = "application/json", produces = "application/json")
    ResponseEntity<Map<String, String>> accept(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > LifecycleProtocol.MAX_BODY) throw new LifecycleProtocol.Rejected(413);
        byte[] body = request.getInputStream().readNBytes(LifecycleProtocol.MAX_BODY + 1);
        if (body.length > LifecycleProtocol.MAX_BODY) throw new LifecycleProtocol.Rejected(413);
        var observation = protocol.decode(body, header(request, "X-Lifecycle-Key-Id"),
                header(request, "X-Lifecycle-Timestamp"), header(request, "X-Lifecycle-Signature"));
        return ResponseEntity.ok(Map.of("result", store.accept(observation)));
    }
    private String header(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements()) throw new LifecycleProtocol.Rejected(401);
        return value;
    }
    @ExceptionHandler(LifecycleProtocol.Rejected.class)
    ResponseEntity<Map<String, String>> rejected(LifecycleProtocol.Rejected error) {
        return ResponseEntity.status(error.status).body(Map.of("result", "rejected"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unavailable(Exception error) {
        return ResponseEntity.status(503).body(Map.of("result", "unavailable"));
    }
}
