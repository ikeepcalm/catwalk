package dev.ua.ikeepcalm.catwalk.hub.webserver.audit;

import dev.ua.ikeepcalm.catwalk.common.audit.CatWalkAudit;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hourly {@code api.request_summary} counts for read-only requests that get no per-request row.
 * Keyed by (route, status class) and bounded per window; keys past the bound roll into
 * {@link #OVERFLOW_ROUTE}. Recording is lock-free apart from a per-bucket monitor; emission
 * happens only from the timer and on disable, never on a request thread.
 */
public final class RequestSummary {
    public static final String OVERFLOW_ROUTE = "other";
    public static final String UNMATCHED_ROUTE = "unmatched";

    static final int MAX_KEYS = 2_000;
    private static final long HOUR_MS = 3_600_000L;
    private static final long[] LATENCY_BOUNDS_MS = {1, 2, 5, 10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000};

    private final CatWalkAudit audit;
    private final FixedWindows<Window> windows = new FixedWindows<>(HOUR_MS, Window::new);

    public RequestSummary(CatWalkAudit audit) {
        this.audit = audit;
    }

    /** Counts one request. Never throws. */
    public void record(String route, int status, long durationMs) {
        if (!audit.isEnabled()) {
            return;
        }
        audit.guard(() -> windows.write(System.currentTimeMillis(), window -> {
            window.add(route, statusClass(status), durationMs);
            return null;
        }));
    }

    /** Timer entry point: rolls the window at the hour boundary and emits windows retired earlier. */
    public void tick() {
        audit.guard(() -> windows.drain(System.currentTimeMillis(), this::emit));
    }

    /** Emits every window including the partial current one. Called on disable, after the server stopped. */
    public void flushAll() {
        audit.guard(() -> windows.drainAll(this::emit));
    }

    private void emit(Window window) {
        Map<String, Object> windowFields = new LinkedHashMap<>();
        window.describe(windowFields);
        UUID correlation = UUID.randomUUID();
        for (Bucket bucket : window.buckets.values()) {
            audit.emit(() -> bucket.toRow(correlation, windowFields));
        }
    }

    static String statusClass(int status) {
        return status >= 100 && status < 600 ? (status / 100) + "xx" : "unknown";
    }

    private record Key(String route, String statusClass) {
    }

    private static final class Window extends FixedWindows.Window {
        final ConcurrentHashMap<Key, Bucket> buckets = new ConcurrentHashMap<>();

        Window(long start) {
            super(start, HOUR_MS);
        }

        void add(String route, String statusClass, long durationMs) {
            Key key = new Key(route, statusClass);
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                if (buckets.size() >= MAX_KEYS) {
                    key = new Key(OVERFLOW_ROUTE, statusClass);
                }
                bucket = buckets.computeIfAbsent(key, Bucket::new);
            }
            bucket.add(durationMs);
        }
    }

    private static final class Bucket {
        private final Key key;
        private final long[] histogram = new long[LATENCY_BOUNDS_MS.length + 1];
        private long count;
        private long maxMs;

        Bucket(Key key) {
            this.key = key;
        }

        synchronized void add(long durationMs) {
            long duration = Math.max(0, durationMs);
            count++;
            maxMs = Math.max(maxMs, duration);
            int slot = 0;
            while (slot < LATENCY_BOUNDS_MS.length && duration > LATENCY_BOUNDS_MS[slot]) {
                slot++;
            }
            histogram[slot]++;
        }

        /** Upper bound of the histogram slot holding the median, capped at the observed maximum. */
        private long p50() {
            long target = (count + 1) / 2;
            long seen = 0;
            for (int slot = 0; slot < histogram.length; slot++) {
                seen += histogram[slot];
                if (seen >= target) {
                    return slot < LATENCY_BOUNDS_MS.length ? Math.min(LATENCY_BOUNDS_MS[slot], maxMs) : maxMs;
                }
            }
            return maxMs;
        }

        synchronized CatWalkAudit.AuditRow toRow(UUID correlation, Map<String, Object> windowFields) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("route", key.route());
            metadata.put("status_class", key.statusClass());
            metadata.put("count", count);
            metadata.putAll(windowFields);
            metadata.put("p50_ms", p50());
            metadata.put("max_ms", maxMs);
            if (OVERFLOW_ROUTE.equals(key.route())) {
                metadata.put("overflow", true);
            }
            return new CatWalkAudit.AuditRow("api.request_summary", AuditOutcome.OBSERVED, AuditRisk.LOW,
                    AuditPrivacy.INTERNAL, correlation,
                    key.route() + " " + key.statusClass() + " @" + windowFields.get("window_start"),
                    null, null, metadata);
        }
    }
}
