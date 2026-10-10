package dev.ua.ikeepcalm.catwalk.common.audit;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditProducer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Best-effort bridge to the shared Mysterria audit ledger. Every method is thread-safe, never
 * throws and never blocks on I/O: the client queues rows and writes the spool on its own worker.
 * When the client cannot start (or {@code audit.enabled} is false) every call is a no-op.
 */
public final class CatWalkAudit implements AutoCloseable {
    public static final String PRODUCER_ID = "catwalk";

    private static final long WARN_INTERVAL_MS = 5 * 60_000L;
    private static final int MAX_TEXT = 256;
    private static final int MAX_KEYS = 48;

    /** Null when auditing is disabled or the client failed to initialise. */
    private final AuditProducer producer;
    private final Logger logger;
    private final String serverId;
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong lastWarnAt = new AtomicLong();
    private final ThreadLocal<UUID> scope = new ThreadLocal<>();

    private CatWalkAudit(AuditProducer producer, Logger logger) {
        this.producer = producer;
        this.logger = logger;
        this.serverId = configuredServerId();
    }

    public static CatWalkAudit disabled() {
        return new CatWalkAudit(null, null);
    }

    public static CatWalkAudit create(JavaPlugin plugin, boolean enabled) {
        if (!enabled) {
            return disabled();
        }
        try {
            AuditProducer producer = AuditProducer.create(plugin.getDataFolder().toPath().toAbsolutePath().getParent()
                    .resolve("mysterria-audit-spool"), PRODUCER_ID, plugin.getPluginMeta().getVersion());
            return new CatWalkAudit(producer, plugin.getLogger());
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().warning("Audit client unavailable; CatWalk audit events are disabled: " + failure);
            return disabled();
        }
    }

    public boolean isEnabled() {
        return producer != null;
    }

    /** Server id as the audit client resolves it, so metadata matches the envelope's {@code server}. */
    public String serverId() {
        return serverId;
    }

    /**
     * Builds and emits one row. The builder runs on the caller's thread; any exception it throws is
     * swallowed and counted. Rows without a correlation id use the thread's open scope, if any.
     */
    public void emit(Supplier<AuditRow> source) {
        if (producer == null || source == null) {
            return;
        }
        try {
            AuditRow row = source.get();
            if (row == null) {
                return;
            }
            UUID correlation = row.correlationId() != null ? row.correlationId() : currentCorrelation();
            producer.emit(row.eventType(), row.outcome(), row.risk(), row.privacy(), correlation,
                    row.businessId() == null ? null : bounded(row.businessId(), MAX_TEXT),
                    row.actorId(), null, null,
                    row.reason() == null ? null : bounded(row.reason(), MAX_TEXT),
                    boundedMetadata(row.metadata()));
        } catch (RuntimeException | LinkageError failure) {
            recordFailure(failure);
        }
    }

    /** Runs audit-side work (metadata collection, bookkeeping) under the same never-throw guard. */
    public void guard(Runnable action) {
        if (producer == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            recordFailure(failure);
        }
    }

    /**
     * Makes rows emitted on this thread without an explicit correlation id share {@code correlationId}
     * until the returned scope is closed. Used to tie a reload to the stop/start/replay rows it causes.
     */
    public Scope openScope(UUID correlationId) {
        UUID previous = scope.get();
        scope.set(correlationId);
        return () -> {
            if (previous == null) {
                scope.remove();
            } else {
                scope.set(previous);
            }
        };
    }

    /** Counts a failure and writes at most one warning line per five minutes. Never throws. */
    public void recordFailure(Throwable failure) {
        long total = failures.incrementAndGet();
        try {
            if (producer != null) {
                producer.recordFailure();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Failure accounting is itself best effort.
        }
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (logger == null || now - last < WARN_INTERVAL_MS || !lastWarnAt.compareAndSet(last, now)) {
            return;
        }
        try {
            logger.warning("Audit emission failed (" + total + " failure(s) since enable, last: "
                    + failure.getClass().getName() + "); requests are unaffected");
        } catch (RuntimeException ignored) {
            // Logging must not break the caller either.
        }
    }

    private UUID currentCorrelation() {
        UUID scoped = scope.get();
        return scoped != null ? scoped : UUID.randomUUID();
    }

    private static Map<String, Object> boundedMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && result.size() < MAX_KEYS) {
                result.put(bounded(key, 64), value instanceof Number || value instanceof Boolean
                        ? value : bounded(String.valueOf(value), MAX_TEXT));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /** Truncates to {@code limit} code points; null stays null. */
    public static String bounded(String value, int limit) {
        if (value == null || value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }

    /** Mirrors the client's own lookup so the value is never hardcoded here. */
    private static String configuredServerId() {
        String value = System.getProperty("mysterria.audit.server-id");
        if (value == null || value.isBlank()) {
            value = System.getenv("MYSTERRIA_AUDIT_SERVER_ID");
        }
        return value == null || value.isBlank() ? "unknown-local" : value.trim();
    }

    @Override
    public void close() {
        if (producer == null) {
            return;
        }
        try {
            producer.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Shutdown must continue even if the audit client cannot flush.
        }
    }

    /** Closes a correlation scope; never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** One audit row; metadata values must be plain values (strings, numbers, booleans). */
    public record AuditRow(String eventType, AuditOutcome outcome, AuditRisk risk, AuditPrivacy privacy,
                           UUID correlationId, String businessId, UUID actorId, String reason,
                           Map<String, ?> metadata) {
    }
}
