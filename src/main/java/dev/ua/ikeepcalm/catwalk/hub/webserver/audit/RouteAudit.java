package dev.ua.ikeepcalm.catwalk.hub.webserver.audit;

import dev.ua.ikeepcalm.catwalk.common.audit.CatWalkAudit;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which plugin registered each route (so request rows can name the owner) and emits
 * {@code api.route_registered}. One instance lives for the whole enable, across reloads.
 */
public final class RouteAudit {
    public static final String KIND_ROUTE = "route";
    public static final String KIND_WEBSOCKET = "websocket";
    public static final String KIND_HANDLER = "handler";

    private static final int MAX_OWNERS = 4_096;
    private static final int MAX_PATH = 200;

    private final CatWalkAudit audit;
    private final Map<String, String> owners = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> replaying = new ThreadLocal<>();

    public RouteAudit(CatWalkAudit audit) {
        this.audit = audit;
    }

    /**
     * One registration as the service received it; {@code method}/{@code path} are null for handler objects.
     * {@code owner} is the addon name CatWalk already works out for handler routes, else null.
     */
    public record Descriptor(String owner, String method, String path, String kind) {
    }

    /** Runs {@code action} with every row it produces marked {@code replay: true}. */
    public void runAsReplay(Runnable action) {
        Boolean previous = replaying.get();
        replaying.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            replaying.set(previous);
        }
    }

    /** Owner of the route pattern that matched a request, or null when it was not registered through CatWalk. */
    public String ownerOf(String method, String route) {
        return route == null ? null : owners.get(key(method, route));
    }

    /** Records a registration applied to the current web server (or skipped as blocked). Never throws. */
    public void registered(Descriptor route, boolean blocked, String addonName) {
        audit.guard(() -> {
            if (!blocked && route.path() != null && route.owner() != null && owners.size() < MAX_OWNERS) {
                owners.put(key(route.method(), route.path()), route.owner());
            }
            emit(route, blocked ? AuditOutcome.CANCELLED : AuditOutcome.COMMITTED, blocked, addonName, null);
        });
    }

    /** Records a registration that threw while being replayed after a reload. Never throws. */
    public void failed(Descriptor route, Throwable error) {
        audit.guard(() -> emit(route, AuditOutcome.FAILED, false, null, error));
    }

    private void emit(Descriptor route, AuditOutcome outcome, boolean blocked, String addonName, Throwable error) {
        boolean replay = Boolean.TRUE.equals(replaying.get());
        audit.emit(() -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            putIfPresent(metadata, "owner_plugin", route.owner());
            putIfPresent(metadata, "method", route.method());
            putIfPresent(metadata, "path", CatWalkAudit.bounded(route.path(), MAX_PATH));
            metadata.put("kind", route.kind());
            metadata.put("replay", replay);
            if (blocked) {
                metadata.put("blocked", true);
            }
            putIfPresent(metadata, "addon_name", addonName);
            if (error != null) {
                metadata.put("error_class", error.getClass().getName());
            }
            String businessId = route.path() != null ? route.method() + " " + route.path()
                    : route.owner() != null ? route.kind() + ":" + route.owner() : route.kind();
            return new CatWalkAudit.AuditRow("api.route_registered", outcome,
                    outcome == AuditOutcome.FAILED ? AuditRisk.NORMAL : AuditRisk.LOW, AuditPrivacy.INTERNAL,
                    null, businessId, null, blocked ? "blocked_path" : null, metadata);
        });
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }
}
