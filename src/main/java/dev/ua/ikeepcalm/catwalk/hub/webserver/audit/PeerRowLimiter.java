package dev.ua.ikeepcalm.catwalk.hub.webserver.audit;

import dev.ua.ikeepcalm.catwalk.common.audit.CatWalkAudit;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps the noisy {@code api.request} rows (4xx, denied, missing or wrong key) per TCP peer, so a
 * scanner or a key brute force cannot crowd out real write and reload rows. In each clock minute
 * the first {@code limit} such rows from a peer are emitted in full; later ones are only counted
 * and summarised as one {@code api.request_suppressed} row per (peer, status class, auth result)
 * once the minute has closed. Peers are keyed on the socket address, never on X-Forwarded-For.
 * At most {@link #MAX_PEERS} peers are tracked per minute; later peers share one overflow bucket.
 */
public final class PeerRowLimiter {
    public static final int DEFAULT_LIMIT = 20;
    static final String OVERFLOW_PEER = "overflow";
    static final int MAX_PEERS = 10_000;
    static final int MAX_FINGERPRINTS = 16;
    private static final long MINUTE_MS = 60_000L;
    private static final int MAX_PATH = 200;

    private final CatWalkAudit audit;
    private final int limit;
    private final FixedWindows<Window> windows = new FixedWindows<>(MINUTE_MS, Window::new);

    /** @param limit full rows per peer per minute; zero or less disables suppression */
    public PeerRowLimiter(CatWalkAudit audit, int limit) {
        this.audit = audit;
        this.limit = limit;
    }

    public record Attempt(String peer, String statusClass, String authResult, String keyFingerprint, String path) {
    }

    /**
     * Whether the row for {@code attempt} should be emitted in full. Otherwise it was counted for
     * the minute's suppression summary. Fails open: an internal error admits the row.
     */
    public boolean admit(Attempt attempt) {
        if (limit <= 0 || !audit.isEnabled()) {
            return true;
        }
        try {
            long now = System.currentTimeMillis();
            // The whole decision runs against one unsealed window, so a suppressed row is always
            // counted in a summary that is still to be emitted.
            return windows.write(now, window -> admit(window, attempt, now));
        } catch (RuntimeException | LinkageError failure) {
            audit.recordFailure(failure);
            return true;
        }
    }

    private boolean admit(Window window, Attempt attempt, long now) {
        PeerState peer = window.peer(attempt.peer());
        if (peer.admitted.getAndUpdate(n -> n < limit ? n + 1 : n) < limit) {
            return true;
        }
        peer.suppressed.computeIfAbsent(new Key(attempt.statusClass(), attempt.authResult()), Suppressed::new)
                .add(attempt, now);
        return false;
    }

    /** Timer entry point: rolls the minute and emits summaries of minutes that have closed. */
    public void tick() {
        audit.guard(() -> windows.drain(System.currentTimeMillis(), this::emit));
    }

    /** Emits every pending summary, including the current minute's. Called on disable. */
    public void flushAll() {
        audit.guard(() -> windows.drainAll(this::emit));
    }

    private void emit(Window window) {
        Map<String, Object> windowFields = new LinkedHashMap<>();
        window.describe(windowFields);
        Object windowStart = windowFields.get("window_start");
        UUID correlation = UUID.randomUUID();
        window.peers.forEach((address, peer) -> peer.suppressed.values().forEach(bucket -> audit.emit(() -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (OVERFLOW_PEER.equals(address)) {
                metadata.put("overflow", true);
            } else {
                metadata.put("remote_ip", address);
            }
            metadata.putAll(windowFields);
            metadata.put("limit", limit);
            metadata.put("emitted_count", peer.admitted.get());
            bucket.describe(metadata);
            return new CatWalkAudit.AuditRow("api.request_suppressed", AuditOutcome.OBSERVED, AuditRisk.NORMAL,
                    AuditPrivacy.STAFF_RESTRICTED, correlation,
                    address + " " + bucket.key.statusClass() + " " + bucket.key.authResult() + " @" + windowStart,
                    null, null, metadata);
        })));
    }

    private record Key(String statusClass, String authResult) {
    }

    private static final class Window extends FixedWindows.Window {
        final ConcurrentHashMap<String, PeerState> peers = new ConcurrentHashMap<>();

        Window(long start) {
            super(start, MINUTE_MS);
        }

        PeerState peer(String address) {
            String key = address == null ? "unknown" : address;
            PeerState state = peers.get(key);
            if (state != null) {
                return state;
            }
            return peers.computeIfAbsent(peers.size() >= MAX_PEERS ? OVERFLOW_PEER : key, ignored -> new PeerState());
        }
    }

    private static final class PeerState {
        /** Full rows emitted for this peer in the window; never exceeds the limit. */
        final AtomicInteger admitted = new AtomicInteger();
        final ConcurrentHashMap<Key, Suppressed> suppressed = new ConcurrentHashMap<>();
    }

    private static final class Suppressed {
        final Key key;
        private final Set<String> fingerprints = new LinkedHashSet<>();
        private boolean fingerprintsTruncated;
        private long count;
        private String firstPath;
        private String lastPath;
        private long firstSeen;
        private long lastSeen;

        Suppressed(Key key) {
            this.key = key;
        }

        synchronized void add(Attempt attempt, long now) {
            if (count++ == 0) {
                firstPath = CatWalkAudit.bounded(attempt.path(), MAX_PATH);
                firstSeen = now;
            }
            lastPath = CatWalkAudit.bounded(attempt.path(), MAX_PATH);
            lastSeen = now;
            String fingerprint = attempt.keyFingerprint();
            if (fingerprint != null && !fingerprints.contains(fingerprint)) {
                if (fingerprints.size() < MAX_FINGERPRINTS) {
                    fingerprints.add(fingerprint);
                } else {
                    fingerprintsTruncated = true;
                }
            }
        }

        synchronized void describe(Map<String, Object> metadata) {
            metadata.put("status_class", key.statusClass());
            metadata.put("auth_result", key.authResult());
            metadata.put("suppressed_count", count);
            metadata.put("distinct_key_fingerprints", fingerprints.size());
            metadata.put("key_fingerprints", String.join(",", fingerprints));
            if (fingerprintsTruncated) {
                metadata.put("key_fingerprints_truncated", true);
            }
            if (firstPath != null) {
                metadata.put("first_path", firstPath);
            }
            if (lastPath != null) {
                metadata.put("last_path", lastPath);
            }
            metadata.put("first_seen", Instant.ofEpochMilli(firstSeen).toString());
            metadata.put("last_seen", Instant.ofEpochMilli(lastSeen).toString());
        }
    }
}
