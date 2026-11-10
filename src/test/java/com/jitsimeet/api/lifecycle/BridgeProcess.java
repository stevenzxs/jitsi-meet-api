package com.jitsimeet.api.lifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only deadline for a direct child with pipe-based protocol. */
final class BridgeProcess {
    interface Conversation { void run() throws Exception; }
    static final class DeadlineExceeded extends RuntimeException {}

    static void run(Process process, Duration timeout, Conversation conversation) throws Exception {
        var expired = new AtomicBoolean();
        var timer = Executors.newSingleThreadScheduledExecutor();
        long deadline = System.nanoTime() + timeout.toNanos();
        var alarm = timer.schedule(() -> {
            expired.set(true);
            process.destroyForcibly(); // closes the blocked pipe even if thread interruption cannot
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        try {
            conversation.run();
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw new DeadlineExceeded();
            if (expired.get()) throw new DeadlineExceeded();
            if (process.exitValue() != 0) throw new IllegalStateException("bridge child exited " + process.exitValue());
        } catch (Exception failure) {
            if (expired.get()) throw new DeadlineExceeded();
            throw failure;
        } finally {
            alarm.cancel(false);
            timer.shutdownNow();
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("bridge child could not be reaped");
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
            if (!timer.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("deadline thread did not terminate");
        }
    }
}
