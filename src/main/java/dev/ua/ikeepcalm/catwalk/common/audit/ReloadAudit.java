package dev.ua.ikeepcalm.catwalk.common.audit;

import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps {@code /catwalk reload} in one {@code catwalk.reload} row. The web server stop/start and
 * route replay rows the reload causes share its correlation id. The reload itself, its exceptions
 * and therefore the command's messages are unchanged.
 */
public final class ReloadAudit {

    private ReloadAudit() {
    }

    /**
     * Security-relevant settings of one WebServer generation. Holds the key only until the row is built
     * off the server thread; the row gets its fingerprint, never the key.
     */
    private record Snapshot(boolean auth, boolean tls, int blockedPaths, int whitelistedPaths,
                            String authKey, String mode, int port) {
    }

    /** Runs {@code reload} and records it; rethrows whatever {@code reload} throws. */
    public static void run(CatWalkMain plugin, CommandSender sender, Runnable reload) {
        CatWalkAudit audit = plugin.getAudit();
        if (!audit.isEnabled()) {
            reload.run();
            return;
        }
        UUID correlation = UUID.randomUUID();
        Snapshot before = capture(plugin, audit);
        try (CatWalkAudit.Scope ignored = audit.openScope(correlation)) {
            reload.run();
        } catch (RuntimeException | Error failure) {
            emit(plugin, sender, correlation, before, failure);
            throw failure;
        }
        emit(plugin, sender, correlation, before, null);
    }

    private static Snapshot capture(CatWalkMain plugin, CatWalkAudit audit) {
        try {
            WebServer server = plugin.getWebServer();
            if (server == null) {
                return null;
            }
            Map<String, Object> description = server.auditDescription();
            return new Snapshot(Boolean.TRUE.equals(description.get("auth")), Boolean.TRUE.equals(description.get("tls")),
                    (Integer) description.get("blocked_paths_count"), (Integer) description.get("whitelisted_paths_count"),
                    server.getAuthKey(), plugin.getModeName(), plugin.getConfig().getInt("port", 4567));
        } catch (RuntimeException | LinkageError failure) {
            audit.recordFailure(failure);
            return null;
        }
    }

    private static void emit(CatWalkMain plugin, CommandSender sender, UUID correlation, Snapshot before,
                             Throwable failure) {
        CatWalkAudit audit = plugin.getAudit();
        Snapshot after = capture(plugin, audit);
        Map<String, Object> metadata = new LinkedHashMap<>();
        putActor(metadata, sender);
        UUID actor = sender instanceof Entity entity ? entity.getUniqueId() : null;
        // SHA-256 of the keys runs off the server thread.
        CompletableFuture.runAsync(() -> audit.emit(() -> {
            putSnapshot(metadata, "_before", before);
            putSnapshot(metadata, "_after", after);
            if (before != null && after != null) {
                metadata.put("key_changed", !Objects.equals(before.authKey(), after.authKey()));
                metadata.put("blocked_paths_count", after.blockedPaths());
                metadata.put("whitelisted_paths_count", after.whitelistedPaths());
            }
            if (failure != null) {
                metadata.put("error_class", failure.getClass().getName());
            }
            AuditOutcome outcome = failure == null ? AuditOutcome.COMMITTED : AuditOutcome.FAILED;
            return new CatWalkAudit.AuditRow("catwalk.reload", outcome, AuditRisk.HIGH, AuditPrivacy.STAFF_RESTRICTED,
                    correlation, plugin.getServerId(), actor, failure == null ? null : "reload_failed", metadata);
        }));
    }

    private static void putActor(Map<String, Object> metadata, CommandSender sender) {
        if (sender instanceof Player) {
            metadata.put("actor_type", "player");
            return;
        }
        metadata.put("actor_type", sender instanceof ConsoleCommandSender ? "console"
                : sender instanceof Entity ? "entity" : "other");
        metadata.put("actor_name", sender.getName());
    }

    private static void putSnapshot(Map<String, Object> metadata, String suffix, Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        metadata.put("auth_enabled" + suffix, snapshot.auth());
        metadata.put("tls" + suffix, snapshot.tls());
        metadata.put("blocked_paths_count" + suffix, snapshot.blockedPaths());
        metadata.put("whitelisted_paths_count" + suffix, snapshot.whitelistedPaths());
        metadata.put("key_fingerprint" + suffix, KeyFingerprint.of(snapshot.authKey()));
        metadata.put("mode" + suffix, snapshot.mode());
        metadata.put("port" + suffix, snapshot.port());
    }
}
