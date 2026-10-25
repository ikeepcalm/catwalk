package dev.ua.ikeepcalm.catwalk.hub.network;

import com.google.gson.reflect.TypeToken;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseManager;
import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkRequest;
import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkResponse;
import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkServer;
import dev.ua.ikeepcalm.catwalk.common.database.model.ServerAddon;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.QueueSignature;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonSingleton;
import dev.ua.ikeepcalm.catwalk.hub.webserver.CustomOpenApiGenerator.RouteType;
import dev.ua.ikeepcalm.catwalk.hub.webserver.RouteAccess;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class NetworkGateway {

    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final int MAX_PENDING_REQUESTS = 500;
    private static final Type HEADERS_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern ENDPOINT_PATH = Pattern.compile("/[A-Za-z0-9_.~{}/-]*");
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "cookie", "proxy-authorization", "x-catwalk-signature", "x-catwalk-timestamp");
    private static final Set<String> FORWARDED_RESPONSE_HEADERS = Set.of("content-type", "cache-control", "etag", "last-modified");

    private final DatabaseManager databaseManager;
    private final NetworkRegistry networkRegistry;
    private final WebServer webServer;
    private final String queueKey;

    private final Map<String, CompletableFuture<NetworkResponse>> pendingRequests = new ConcurrentHashMap<>();
    private final Set<String> registeredRoutes = ConcurrentHashMap.newKeySet();
    private final BukkitTask responsePollingTask;
    private final BukkitTask routeRefreshTask;

    public NetworkGateway(DatabaseManager databaseManager, NetworkRegistry networkRegistry, WebServer webServer, Plugin plugin) {
        this.databaseManager = databaseManager;
        this.networkRegistry = networkRegistry;
        this.webServer = webServer;
        this.queueKey = webServer.getAuthKey();
        this.responsePollingTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::pollForResponses, 20L, 20L);
        this.routeRefreshTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::registerNetworkRoutes, 100L, 1200L);
    }

    public void registerNetworkRoutes() {
        networkRegistry.getAllNetworkAddons().thenAccept(addonsByServer -> addonsByServer.forEach((serverId, addons) -> {
            for (ServerAddon addon : addons) {
                addon.getEndpoints().forEach(endpoint -> registerProxyRoute(serverId, addon.getAddonName(), endpoint));
            }
        })).exceptionally(e -> {
            CatWalkLogger.error("Failed to refresh network routes", e);
            return null;
        });
    }

    private void registerProxyRoute(String serverId, String addonName, EndpointDefinition endpoint) {
        if (!SERVER_ID.matcher(serverId).matches() || endpoint.getPath() == null || !ENDPOINT_PATH.matcher(endpoint.getPath()).matches()) {
            CatWalkLogger.warn("Ignoring addon '%s' endpoint '%s' from server '%s': invalid server id or path", addonName, endpoint.getPath(), serverId);
            return;
        }
        String serverPrefix = "/v1/servers/" + serverId;
        String proxyPath = serverPrefix + endpoint.getPath();

        for (HttpMethod method : endpoint.getMethods()) {
            if (!registeredRoutes.add(method + ":" + proxyPath)) {
                continue;
            }
            webServer.addRoute(WebServer.toHandlerType(method), proxyPath,
                    ctx -> handleProxyRequest(serverId, ctx.path().substring(serverPrefix.length()), ctx), RouteAccess.of(endpoint));
            webServer.describeRoute(method, proxyPath, RouteType.PROXY, addonName, proxied(endpoint, serverId, addonName));
            CatWalkLogger.debug("Proxy %s %s -> %s:%s (%s)", method, proxyPath, serverId, endpoint.getPath(), addonName);
        }
    }

    private EndpointDefinition proxied(EndpointDefinition endpoint, String serverId, String addonName) {
        return EndpointDefinition.builder()
                .path(endpoint.getPath())
                .methods(endpoint.getMethods())
                .summary(endpoint.getSummary() != null ? endpoint.getSummary() : "Proxy endpoint for " + endpoint.getPath())
                .description(endpoint.getDescription() != null ? endpoint.getDescription()
                        : "Proxied endpoint from server: " + serverId + " (addon: " + addonName + ")")
                .tags(endpoint.getTags() != null && !endpoint.getTags().isEmpty() ? endpoint.getTags() : List.of("Server-" + serverId, addonName))
                .requiresAuth(endpoint.isRequiresAuth())
                .scopes(endpoint.getScopes())
                .rateLimit(endpoint.getRateLimit())
                .logRequests(endpoint.isLogRequests())
                .parameters(endpoint.getParameters())
                .requestBody(endpoint.getRequestBody())
                .responses(endpoint.getResponses())
                .build();
    }

    public void registerNetworkManagementRoutes() {
        webServer.addCoreRoute(HandlerType.GET, "/v1/network/status", ctx -> ctx.future(() -> networkRegistry.getAllServers().thenAccept(servers -> ctx.json(Map.of(
                "hubServer", networkRegistry.getCurrentServerId(),
                "timestamp", System.currentTimeMillis(),
                "status", "active",
                "totalServers", servers.size(),
                "onlineServers", servers.stream().filter(s -> s.getStatus() == NetworkServer.Status.ONLINE).count()
        )))));

        webServer.addCoreRoute(HandlerType.GET, "/v1/network/servers", ctx -> ctx.future(() -> networkRegistry.getAllServers().thenAccept(servers -> ctx.json(Map.of(
                "servers", servers,
                "totalCount", servers.size()
        )))));

        webServer.addCoreRoute(HandlerType.GET, "/v1/network/addons", ctx -> ctx.future(() -> networkRegistry.getAllNetworkAddons().thenAccept(addonsByServer -> ctx.json(Map.of(
                "addonsByServer", addonsByServer,
                "totalAddons", addonsByServer.values().stream().mapToInt(List::size).sum()
        )))));

        webServer.addCoreRoute(HandlerType.GET, "/v1/network/servers/{serverId}/addons", ctx -> {
            String serverId = ctx.pathParam("serverId");
            ctx.future(() -> networkRegistry.getServerAddons(serverId).thenAccept(addons -> ctx.json(Map.of(
                    "serverId", serverId,
                    "addons", addons,
                    "count", addons.size()
            ))));
        });
    }

    private void handleProxyRequest(String targetServerId, String originalPath, Context ctx) {
        if (pendingRequests.size() >= MAX_PENDING_REQUESTS) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of("error", "Gateway is busy, try again later"));
            return;
        }
        String requestId = UUID.randomUUID().toString();
        Map<String, String> queryParams = new HashMap<>();
        ctx.queryParamMap().forEach((key, values) -> queryParams.put(key, String.join(",", values)));
        Map<String, String> headers = new HashMap<>();
        ctx.headerMap().forEach((key, value) -> {
            if (!CREDENTIAL_HEADERS.contains(key.toLowerCase())) {
                headers.put(key, value);
            }
        });

        NetworkRequest request = NetworkRequest.builder()
                .requestId(requestId)
                .targetServerId(targetServerId)
                .endpointPath(originalPath)
                .httpMethod(NetworkRequest.HttpMethod.valueOf(ctx.method().name()))
                .headers(headers)
                .queryParams(queryParams)
                .body(ctx.body())
                .timeoutSeconds(REQUEST_TIMEOUT_SECONDS)
                .maxRetries(3)
                .status(NetworkRequest.RequestStatus.PENDING)
                .build();
        String timestamp = String.valueOf(System.currentTimeMillis());
        headers.put(QueueSignature.TIMESTAMP_HEADER, timestamp);
        headers.put(QueueSignature.SIGNATURE_HEADER, RequestProcessor.requestSignature(queueKey, request, timestamp));

        CatWalkLogger.debug("Proxy %s %s -> %s (%s)", ctx.method(), originalPath, targetServerId, requestId);

        ctx.future(() -> networkRegistry.isServerOnline(targetServerId).thenCompose(online -> {
            if (!online) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of(
                        "error", "Target server '" + targetServerId + "' is not available", "requestId", requestId));
                return CompletableFuture.completedFuture(null);
            }
            return storeRequest(request).thenCompose(stored -> awaitResponse(requestId)).thenAccept(response -> {
                if (response != null && !RequestProcessor.hasValidSignature(queueKey, response)) {
                    CatWalkLogger.error("Rejected response for request %s from '%s': bad signature (every server must use the same key)", requestId, targetServerId);
                    ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", "Invalid response from target server", "requestId", requestId));
                    return;
                }
                if (response != null) {
                    writeProxyResponse(ctx, response);
                    return;
                }
                ctx.status(HttpStatus.GATEWAY_TIMEOUT).json(Map.of("error", "Request to target server timed out", "requestId", requestId));
            });
        }).exceptionally(e -> {
            CatWalkLogger.error("Proxy request %s failed", e, requestId);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Internal gateway error", "requestId", requestId));
            return null;
        }));
    }

    private CompletableFuture<Integer> storeRequest(NetworkRequest request) {
        String sql = """
                INSERT INTO request_queue (request_id, target_server_id, endpoint_path, http_method,
                                           headers, query_params, body, priority, timeout_seconds, max_retries, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """;

        return databaseManager.executeUpdateAsync(sql, stmt -> {
            stmt.setString(1, request.getRequestId());
            stmt.setString(2, request.getTargetServerId());
            stmt.setString(3, request.getEndpointPath());
            stmt.setString(4, request.getHttpMethod().name());
            stmt.setString(5, GsonSingleton.getInstance().toJson(request.getHeaders()));
            stmt.setString(6, GsonSingleton.getInstance().toJson(request.getQueryParams()));
            stmt.setString(7, request.getBody());
            stmt.setInt(8, request.getPriority());
            stmt.setInt(9, request.getTimeoutSeconds());
            stmt.setInt(10, request.getMaxRetries());
            stmt.setString(11, request.getStatus().name().toLowerCase());
        });
    }

    private CompletableFuture<NetworkResponse> awaitResponse(String requestId) {
        CompletableFuture<NetworkResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        return future.completeOnTimeout(null, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((response, error) -> pendingRequests.remove(requestId));
    }

    private void writeProxyResponse(Context ctx, NetworkResponse response) {
        ctx.status(response.getStatusCode());
        if (response.getHeaders() != null) {
            response.getHeaders().forEach((key, value) -> {
                if (FORWARDED_RESPONSE_HEADERS.contains(key.toLowerCase())) {
                    ctx.header(key, value);
                }
            });
        }
        if (response.getContentType() != null) {
            ctx.contentType(response.getContentType());
        }
        ctx.result(response.getBody() != null ? response.getBody() : "");
    }

    private void pollForResponses() {
        List<String> requestIds = new ArrayList<>(pendingRequests.keySet());
        if (requestIds.isEmpty()) {
            return;
        }

        try {
            String placeholders = String.join(",", Collections.nCopies(requestIds.size(), "?"));
            List<NetworkResponse> responses = databaseManager.executeQuery(
                    "SELECT * FROM response_queue WHERE request_id IN (" + placeholders + ")",
                    stmt -> {
                        for (int i = 0; i < requestIds.size(); i++) {
                            stmt.setString(i + 1, requestIds.get(i));
                        }
                    },
                    rs -> {
                        List<NetworkResponse> list = new ArrayList<>();
                        while (rs.next()) {
                            list.add(mapResponse(rs));
                        }
                        return list;
                    });

            for (NetworkResponse response : responses) {
                CompletableFuture<NetworkResponse> future = pendingRequests.get(response.getRequestId());
                if (future != null) {
                    future.complete(response);
                }
            }
            deleteResponses(responses);
        } catch (RuntimeException e) {
            CatWalkLogger.error("Failed to poll for proxy responses", e);
        }
    }

    private void deleteResponses(List<NetworkResponse> responses) {
        if (responses.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(responses.size(), "?"));
        databaseManager.executeUpdate("DELETE FROM response_queue WHERE request_id IN (" + placeholders + ")", stmt -> {
            for (int i = 0; i < responses.size(); i++) {
                stmt.setString(i + 1, responses.get(i).getRequestId());
            }
        });
    }

    private NetworkResponse mapResponse(ResultSet rs) throws SQLException {
        String headersJson = rs.getString("headers");
        return NetworkResponse.builder()
                .requestId(rs.getString("request_id"))
                .serverId(rs.getString("server_id"))
                .statusCode(rs.getInt("status_code"))
                .headers(headersJson != null ? GsonSingleton.getInstance().fromJson(headersJson, HEADERS_TYPE) : null)
                .body(rs.getString("body"))
                .contentType(rs.getString("content_type"))
                .processedTimeMs(rs.getObject("processed_time_ms", Integer.class))
                .createdAt(rs.getTimestamp("created_at"))
                .build();
    }

    public void shutdown() {
        responsePollingTask.cancel();
        routeRefreshTask.cancel();
        pendingRequests.values().forEach(future -> future.complete(null));
        pendingRequests.clear();
        registeredRoutes.clear();
    }
}
