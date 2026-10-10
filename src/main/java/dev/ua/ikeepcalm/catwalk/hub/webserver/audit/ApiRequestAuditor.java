package dev.ua.ikeepcalm.catwalk.hub.webserver.audit;

import dev.ua.ikeepcalm.catwalk.common.audit.CatWalkAudit;
import dev.ua.ikeepcalm.catwalk.common.audit.KeyFingerprint;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Request-side audit for one WebServer instance. Called only from the existing before,
 * beforeMatched and after hooks; every entry point is a no-op when auditing is off and swallows
 * (and counts) its own failures, so it can never change a status code or a response.
 * Never records the key, an Authorization value, or a request/response body.
 */
public final class ApiRequestAuditor {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    public static final String AUTH_OK = "ok";
    public static final String AUTH_MISSING = "missing";
    public static final String AUTH_INVALID = "invalid";
    public static final String AUTH_WHITELISTED = "whitelisted";
    public static final String AUTH_DISABLED = "disabled";

    /**
     * Set by a route that rejects a call the gateway let through (bridge handlers demand a Bearer
     * token even when the gateway accepted the cookie, or when gateway auth is off).
     */
    public static final String ATTR_HANDLER_AUTH = "catwalk.handler_auth";
    public static final String HANDLER_AUTH_BEARER_REQUIRED = "bearer_required";
    public static final String HANDLER_AUTH_BEARER_INVALID = "bearer_invalid";

    private static final String ATTR_REQUEST_ID = "catwalk-audit-request-id";
    private static final String ATTR_REQUEST_ID_SOURCE = "catwalk-audit-request-id-source";
    private static final String ATTR_ROUTE = "catwalk-audit-route";
    private static final String ATTR_CREDENTIAL = "catwalk-audit-credential";

    private static final Set<HandlerType> READ_ONLY = EnumSet.of(HandlerType.GET, HandlerType.HEAD, HandlerType.OPTIONS);
    private static final int MAX_PATH = 200;
    private static final int MAX_USER_AGENT = 120;
    private static final int MAX_IP = 64;

    private final CatWalkAudit audit;
    private final RequestSummary summary;
    private final PeerRowLimiter limiter;
    private final RouteAudit routes;
    private final String configuredKey;
    private final String cookieName;

    public ApiRequestAuditor(CatWalkAudit audit, RequestSummary summary, PeerRowLimiter limiter, RouteAudit routes,
                             String configuredKey, String cookieName) {
        this.audit = audit;
        this.summary = summary;
        this.limiter = limiter;
        this.routes = routes;
        this.configuredKey = configuredKey;
        this.cookieName = cookieName;
    }

    /** The presented credential reduced to a fingerprint, computed at most once per request. */
    private record Credential(String fingerprint, boolean presented, boolean valid) {
    }

    public boolean isEnabled() {
        return audit.isEnabled();
    }

    /** {@code before}: adopts a caller UUID from X-Request-Id or generates one, and echoes it back. */
    public void onRequestStart(Context ctx) {
        if (!audit.isEnabled()) {
            return;
        }
        audit.guard(() -> {
            String supplied = ctx.header(REQUEST_ID_HEADER);
            UUID requestId = canonicalUuid(supplied);
            ctx.attribute(ATTR_REQUEST_ID_SOURCE, requestId != null ? "caller" : (supplied == null ? "generated" : "replaced"));
            if (requestId == null) {
                requestId = UUID.randomUUID();
            }
            ctx.attribute(ATTR_REQUEST_ID, requestId);
            ctx.header(REQUEST_ID_HEADER, requestId.toString());
        });
    }

    /** {@code beforeMatched}: remembers the matched route pattern and fingerprints the presented key. */
    public void onRouteMatched(Context ctx) {
        if (!audit.isEnabled()) {
            return;
        }
        audit.guard(() -> {
            putIfPresent(ctx, ATTR_ROUTE, matchedRoute(ctx));
            credential(ctx);
        });
    }

    /**
     * For a path-param-free beforeMatched handler Javalin has already bound the matched HTTP
     * endpoint, so this is its pattern (e.g. {@code /v1/players/{uuid}}). Null when the match came
     * from something other than an HTTP route, where Javalin refuses the call.
     */
    private static String matchedRoute(Context ctx) {
        try {
            return ctx.endpointHandlerPath();
        } catch (IllegalStateException noHttpEndpoint) {
            return null;
        }
    }

    private static void putIfPresent(Context ctx, String attribute, Object value) {
        if (value != null) {
            ctx.attribute(attribute, value);
        }
    }

    /** Auth result for a request no route matched, where beforeMatched never evaluated the key. */
    public String unmatchedAuthResult(Context ctx) {
        Credential credential = credential(ctx);
        return credential.valid() ? AUTH_OK : (credential.presented() ? AUTH_INVALID : AUTH_MISSING);
    }

    /**
     * {@code after}: one {@code api.request} row for writes, for every 4xx/5xx response and for
     * unauthenticated calls; allowed read-only calls are only counted in the hourly summary.
     *
     * @param matched    whether a route matched (beforeMatched ran)
     * @param authResult evaluated inside the guard; one of the {@code AUTH_*} values
     */
    public void onRequestComplete(Context ctx, long durationMs, boolean matched, Supplier<String> authResult) {
        if (!audit.isEnabled()) {
            return;
        }
        audit.guard(() -> {
            String auth = authResult.get();
            int status = ctx.statusCode();
            String route = matched ? ctx.attribute(ATTR_ROUTE) : null;
            if (needsRow(ctx.method(), status, matched, auth)) {
                if (!throttleable(ctx.method(), status, auth) || limiter.admit(attempt(ctx, status, auth))) {
                    emitRequest(ctx, durationMs, route, auth, status);
                }
            } else {
                summary.record(matched ? Objects.requireNonNullElse(route, pathOf(ctx)) : RequestSummary.UNMATCHED_ROUTE,
                        status, durationMs);
            }
        });
    }

    /**
     * Writes and every 4xx/5xx response always get a row; so does any call carrying a wrong key,
     * and a call that reached a protected route without one. Read-only calls that succeeded
     * (2xx/3xx) with a valid key, on a whitelisted path, or with auth disabled are only counted.
     */
    static boolean needsRow(HandlerType method, int status, boolean matched, String auth) {
        if (!READ_ONLY.contains(method) || status >= 400 || AUTH_INVALID.equals(auth)) {
            return true;
        }
        return matched && AUTH_MISSING.equals(auth);
    }

    /**
     * Rows the per-peer limiter may suppress: 4xx (including denials) and calls with a missing or
     * wrong key. Never 5xx, and never a write the gateway authenticated or whitelisted.
     */
    static boolean throttleable(HandlerType method, int status, String auth) {
        if (status >= 500) {
            return false;
        }
        if (!READ_ONLY.contains(method) && (AUTH_OK.equals(auth) || AUTH_WHITELISTED.equals(auth))) {
            return false;
        }
        return status >= 400 || AUTH_MISSING.equals(auth) || AUTH_INVALID.equals(auth);
    }

    /** Keyed on the TCP peer, never on the caller-controlled X-Forwarded-For. */
    private PeerRowLimiter.Attempt attempt(Context ctx, int status, String auth) {
        return new PeerRowLimiter.Attempt(ctx.ip(), RequestSummary.statusClass(status), auth,
                credential(ctx).fingerprint(), pathOf(ctx));
    }

    static AuditOutcome outcomeFor(int status) {
        if (status == 401 || status == 403) {
            return AuditOutcome.DENIED;
        }
        if (status >= 500) {
            return AuditOutcome.FAILED;
        }
        return status >= 200 && status < 400 ? AuditOutcome.COMMITTED : AuditOutcome.OBSERVED;
    }

    private void emitRequest(Context ctx, long durationMs, String route, String auth, int status) {
        AuditOutcome outcome = outcomeFor(status);
        String method = ctx.method().name();
        String path = pathOf(ctx);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("method", method);
        metadata.put("path", path);
        putIfPresent(metadata, "route", route);
        metadata.put("status", status);
        metadata.put("duration_ms", durationMs);
        putClientAddress(metadata, ctx);
        metadata.put("auth_result", auth);
        putIfPresent(metadata, "handler_auth", ctx.attribute(ATTR_HANDLER_AUTH));
        metadata.put("key_fingerprint", credential(ctx).fingerprint());
        putIfPresent(metadata, "user_agent", CatWalkAudit.bounded(ctx.userAgent(), MAX_USER_AGENT));
        putSizes(metadata, ctx);
        putIfPresent(metadata, "owner_plugin", routes.ownerOf(method, route));
        putIfPresent(metadata, "request_id_source", ctx.attribute(ATTR_REQUEST_ID_SOURCE));
        UUID requestId = ctx.attribute(ATTR_REQUEST_ID);
        String reason = outcome == AuditOutcome.DENIED || outcome == AuditOutcome.FAILED ? "http_" + status : null;
        audit.emit(() -> new CatWalkAudit.AuditRow("api.request", outcome,
                outcome == AuditOutcome.DENIED ? AuditRisk.HIGH : AuditRisk.NORMAL, AuditPrivacy.STAFF_RESTRICTED,
                requestId, method + " " + (route != null ? route : path), null, reason, metadata));
    }

    private Credential credential(Context ctx) {
        Credential cached = ctx.attribute(ATTR_CREDENTIAL);
        if (cached != null) {
            return cached;
        }
        Credential computed = evaluate(ctx.header("Authorization"), ctx.cookie(cookieName), configuredKey);
        ctx.attribute(ATTR_CREDENTIAL, computed);
        return computed;
    }

    /** Mirrors WebServer's check: a matching Bearer token or cookie authenticates; either one counts as presented. */
    static Credential evaluate(String authHeader, String cookie, String configuredKey) {
        String bearer = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7) : null;
        if (bearer != null && Objects.equals(bearer, configuredKey)) {
            return new Credential(KeyFingerprint.of(bearer), true, true);
        }
        if (cookie != null && Objects.equals(cookie, configuredKey)) {
            return new Credential(KeyFingerprint.of(cookie), true, true);
        }
        boolean presented = authHeader != null || cookie != null;
        if (bearer != null && !bearer.isEmpty()) {
            return new Credential(KeyFingerprint.of(bearer), true, false);
        }
        if (cookie != null && !cookie.isEmpty()) {
            return new Credential(KeyFingerprint.of(cookie), true, false);
        }
        return new Credential(presented ? KeyFingerprint.INVALID_FORMAT : KeyFingerprint.NONE, presented, false);
    }

    /** First X-Forwarded-For hop, or null when the header is absent or blank. */
    public static String forwardedFor(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return null;
        }
        return forwarded.split(",")[0].trim();
    }

    /**
     * {@code remote_ip} is always the TCP peer. The caller controls X-Forwarded-For, so its first
     * hop is kept only as the claim {@code forwarded_for}.
     */
    private static void putClientAddress(Map<String, Object> metadata, Context ctx) {
        String forwarded = forwardedFor(ctx);
        putIfPresent(metadata, "remote_ip", CatWalkAudit.bounded(ctx.ip(), MAX_IP));
        metadata.put("forwarded", forwarded != null);
        if (forwarded != null) {
            metadata.put("forwarded_for", CatWalkAudit.bounded(forwarded, MAX_IP));
        }
    }

    /**
     * Request Content-Length when declared, and the uncompressed size of a buffered result. Streamed
     * results are skipped because measuring them would mean reading the body.
     */
    private static void putSizes(Map<String, Object> metadata, Context ctx) {
        long requestBytes = ctx.req().getContentLengthLong();
        if (requestBytes >= 0) {
            metadata.put("request_bytes", requestBytes);
        }
        InputStream result = ctx.resultInputStream();
        if (result instanceof ByteArrayInputStream buffered) {
            metadata.put("response_bytes", (long) buffered.available());
        }
    }

    private static String pathOf(Context ctx) {
        // Context#path is the request URI, which never includes the query string.
        return CatWalkAudit.bounded(ctx.path(), MAX_PATH);
    }

    private static UUID canonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
