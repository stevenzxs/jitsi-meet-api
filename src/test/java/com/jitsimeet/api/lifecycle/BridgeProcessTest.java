package com.jitsimeet.api.lifecycle;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class BridgeProcessTest {
    Process child(String mode) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), mode).start();
    }

    @Test void blockedReadTimesOutAndChildIsReaped() throws Exception {
        var process = child("hang");
        assertThrows(BridgeProcess.DeadlineExceeded.class, () -> BridgeProcess.run(process, Duration.ofMillis(500), () -> {
            process.getInputStream().readAllBytes();
        }));
        assertFalse(process.isAlive());
    }

    @Test void nonzeroExitFailsAndChildIsReaped() throws Exception {
        var process = child("fail");
        assertThrows(IllegalStateException.class, () -> BridgeProcess.run(process, Duration.ofSeconds(5), () -> {
            process.getInputStream().readAllBytes();
        }));
        assertFalse(process.isAlive());
    }

    @Test void protocolFailureStillReapsChild() throws Exception {
        var process = child("hang");
        assertThrows(IllegalArgumentException.class, () -> BridgeProcess.run(process, Duration.ofSeconds(5), () -> {
            throw new IllegalArgumentException("protocol failed");
        }));
        assertFalse(process.isAlive());
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("fail")) System.exit(7);
            Thread.sleep(60000);
        }
    }
}
