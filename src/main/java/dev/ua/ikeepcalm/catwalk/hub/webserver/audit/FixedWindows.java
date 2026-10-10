package dev.ua.ikeepcalm.catwalk.hub.webserver.audit;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * Clock-aligned, fixed-length time windows that only ever roll forward, plus a queue of retired
 * windows waiting to be emitted. Request threads change a window only through {@link #write};
 * the owner's timer calls {@link #drain} and the disable path calls {@link #drainAll}. Only one
 * drain runs at a time, so the disable flush waits for a timer drain that is mid-emission.
 *
 * <p>A window is sealed under its write lock just before it is emitted. A writer holds the read
 * lock for its whole update and retries on the current window if it finds the window sealed, so
 * a writer stalled past the grace period lands in the next window instead of in one that was
 * already emitted, and nothing is counted twice.
 */
final class FixedWindows<W extends FixedWindows.Window> {
    /** Lets writers that read the old window just before the roll finish before it is emitted. */
    private static final long FLUSH_GRACE_MS = 2_000L;

    private final long lengthMs;
    private final LongFunction<W> factory;
    private final Object rollLock = new Object();
    private final Object flushLock = new Object();
    private final ConcurrentLinkedQueue<W> retired = new ConcurrentLinkedQueue<>();
    private volatile W current;

    /** @param factory creates the window starting at the given epoch millis */
    FixedWindows(long lengthMs, LongFunction<W> factory) {
        this.lengthMs = lengthMs;
        this.factory = factory;
    }

    /**
     * Applies {@code op} to the window covering {@code now} while that window cannot be emitted.
     * Retries on the then-current window if the one it found was sealed in the meantime.
     */
    <T> T write(long now, Function<W, T> op) {
        while (true) {
            W window = at(now);
            Lock read = window.gate.readLock();
            read.lock();
            try {
                if (!window.sealed) {
                    return op.apply(window);
                }
            } finally {
                read.unlock();
            }
        }
    }

    /**
     * The window covering {@code now}. A reading up to one window behind the current window (a
     * thread that lost the race at a boundary, or a small clock correction) gets the current
     * window. A reading further behind means the clock had jumped ahead and was corrected: the
     * future window is retired as a {@code clock_step} window so allowances and counts resume
     * at the corrected time instead of staying pinned in the future.
     */
    private W at(long now) {
        long start = now - Math.floorMod(now, lengthMs);
        W window = current;
        if (covers(window, start)) {
            return window;
        }
        synchronized (rollLock) {
            window = current;
            if (covers(window, start)) {
                return window;
            }
            W fresh = factory.apply(start);
            // Publish the new window before the old one can be drained and sealed, so a writer
            // that finds the old one sealed never sees it as current again.
            current = fresh;
            if (window != null) {
                if (start > window.start) {
                    // A rolled window is always complete; only drainAll() marks one partial.
                    window.retiredAt = Math.max(now, window.end);
                } else {
                    window.clockStep = true;
                    window.retiredAt = window.start;
                }
                retired.add(window);
            }
            return fresh;
        }
    }

    private boolean covers(W window, long start) {
        return window != null && window.start - lengthMs <= start && start <= window.start;
    }

    /** Rolls if a boundary passed, then emits windows retired at least the grace period ago. */
    void drain(long now, Consumer<W> emitter) {
        at(now);
        synchronized (flushLock) {
            drainRetired(now, false, emitter);
        }
    }

    /** Emits every window, including the current one as a partial window. */
    void drainAll(Consumer<W> emitter) {
        synchronized (flushLock) {
            long now = System.currentTimeMillis();
            synchronized (rollLock) {
                W window = current;
                current = null;
                if (window != null) {
                    window.retiredAt = now;
                    retired.add(window);
                }
            }
            drainRetired(now, true, emitter);
        }
    }

    private void drainRetired(long now, boolean force, Consumer<W> emitter) {
        W window;
        while ((window = retired.peek()) != null) {
            long age = now - window.retiredAt;
            // A negative age means the clock moved back since the window was retired. Sealing
            // makes an early drain safe, so do not wait for the clock to catch up.
            if (!force && age >= 0 && age < FLUSH_GRACE_MS) {
                return;
            }
            if (retired.remove(window)) {
                seal(window);
                emitter.accept(window);
            }
        }
    }

    /** Waits for in-flight writers, then turns later ones away to the current window. */
    private static void seal(Window window) {
        Lock write = window.gate.writeLock();
        write.lock();
        try {
            window.sealed = true;
        } finally {
            write.unlock();
        }
    }

    /** One window; subclasses hold the counters. */
    abstract static class Window {
        final long start;
        final long end;
        volatile long retiredAt;
        /** Retired because the clock moved back by more than a window; its times are in the future. */
        volatile boolean clockStep;
        final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
        /** Guarded by {@link #gate}: set once, just before the window is emitted. */
        boolean sealed;

        Window(long start, long lengthMs) {
            this.start = start;
            this.end = start + lengthMs;
        }

        /** Nominal end, or the flush time for a window cut short by {@link FixedWindows#drainAll}. */
        long effectiveEnd() {
            return Math.min(end, Math.max(retiredAt, start));
        }

        boolean partial() {
            return effectiveEnd() < end;
        }

        /** Adds {@code window_start}, {@code window_end} and, when set, {@code partial} and {@code clock_step}. */
        void describe(Map<String, Object> metadata) {
            metadata.put("window_start", Instant.ofEpochMilli(start).toString());
            metadata.put("window_end", Instant.ofEpochMilli(effectiveEnd()).toString());
            if (partial()) {
                metadata.put("partial", true);
            }
            if (clockStep) {
                metadata.put("clock_step", true);
            }
        }
    }
}
