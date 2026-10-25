package dev.ua.ikeepcalm.catwalk.hub.webserver;

import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import io.javalin.router.EndpointMetadata;
import lombok.Data;

import java.util.Set;

@Data
public class RouteAccess implements EndpointMetadata {

    public static final RouteAccess CORE = new RouteAccess(false, Set.of(), 0, false, true);
    public static final RouteAccess ADDON = new RouteAccess(false, Set.of(), 0, false, false);

    private final boolean requiresAuth;
    private final Set<String> scopes;
    private final int rateLimitPerMinute;
    private final boolean logRequests;
    private final boolean core;

    public static RouteAccess of(EndpointDefinition endpoint) {
        Set<String> scopes = endpoint.getScopes() != null ? Set.copyOf(endpoint.getScopes()) : Set.of();
        return new RouteAccess(endpoint.isRequiresAuth() || !scopes.isEmpty(), scopes,
                Math.max(0, endpoint.getRateLimit()), endpoint.isLogRequests(), false);
    }
}
