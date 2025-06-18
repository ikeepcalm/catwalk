package dev.ua.uaproject.catwalk.hub.network;

import com.google.gson.Gson;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.common.database.DatabaseManager;
import dev.ua.uaproject.catwalk.common.database.model.EndpointDefinition;
import dev.ua.uaproject.catwalk.common.database.model.NetworkRequest;
import dev.ua.uaproject.catwalk.common.database.model.NetworkResponse;
import dev.ua.uaproject.catwalk.common.database.model.ServerAddon;
import dev.ua.uaproject.catwalk.hub.webserver.WebServer;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class NetworkGateway {

    private final DatabaseManager databaseManager;
    private final NetworkRegistry networkRegistry;
    private final WebServer webServer;
    private final Plugin plugin;
    private final Gson gson;

    private final Map<String, CompletableFuture<NetworkResponse>> pendingRequests = new ConcurrentHashMap<>();

    private BukkitTask responsePollingTask;

    public NetworkGateway(DatabaseManager databaseManager, NetworkRegistry networkRegistry,
                          WebServer webServer, Plugin plugin) {
        this.databaseManager = databaseManager;
        this.networkRegistry = networkRegistry;
        this.webServer = webServer;
        this.plugin = plugin;
        this.gson = new Gson();

        startResponsePolling();

        log.info("[DatabaseHubGateway] Hub gateway initialized");
    }

    public void registerNetworkRoutes() {
        networkRegistry.getAllNetworkAddons().thenAccept(addonsByServer -> {
            int totalEndpoints = 0;

            for (Map.Entry<String, List<ServerAddon>> serverEntry : addonsByServer.entrySet()) {
                String serverId = serverEntry.getKey();
                List<ServerAddon> addons = serverEntry.getValue();

                for (ServerAddon addon : addons) {
                    for (EndpointDefinition endpoint : addon.getEndpoints()) {
                        registerProxyRoute(serverId, addon.getAddonName(), endpoint);
                        totalEndpoints++;
                    }
                }
            }

            log.info("[DatabaseHubGateway] Registered {} proxy routes for {} servers",
                    totalEndpoints, addonsByServer.size());
        });
    }

    private void registerProxyRoute(String serverId, String addonName, EndpointDefinition endpoint) {
        String proxyPath = "/v1/servers/" + serverId + endpoint.getPath();

        for (HttpMethod method : endpoint.getMethods()) {
            HandlerType handlerType = convertHttpMethod(method);

            Handler proxyHandler = ctx -> handleProxyRequest(serverId, endpoint.getPath(), ctx);

            String summary = endpoint.getSummary() != null ?
                    endpoint.getSummary() :
                    "Proxy endpoint for " + endpoint.getPath();

            String description = endpoint.getDescription() != null ?
                    endpoint.getDescription() :
                    "Proxied endpoint from server: " + serverId + " (addon: " + addonName + ")";

            String[] tags = endpoint.getTags() != null ?
                    endpoint.getTags().toArray(new String[0]) :
                    new String[]{"Proxy", "Server-" + serverId, addonName};

            webServer.registerProxyRoute(handlerType, proxyPath, proxyHandler, summary, description, tags);

            log.debug("[DatabaseHubGateway] Registered proxy route: {} {} → {}:{} ({})",
                    method, proxyPath, serverId, endpoint.getPath(), addonName);
        }
    }

    public void registerNetworkManagementRoutes() {
        webServer.get("/v1/network/status", ctx -> {
            try {
                // Use .get() to wait for the future to complete
                var servers = networkRegistry.getAllServers().get();
                
                Map<String, Object> status = new HashMap<>();
                status.put("hubServer", networkRegistry.getCurrentServerId());
                status.put("timestamp", System.currentTimeMillis());
                status.put("status", "active");
                status.put("totalServers", servers.size());
                status.put("onlineServers", servers.stream().mapToInt(s ->
                        s.getStatus().name().equals("ONLINE") ? 1 : 0).sum());

                ctx.json(status);
            } catch (Exception e) {
                log.error("[DatabaseHubGateway] Error in network status endpoint", e);
                ctx.status(500).json(Map.of("error", "Failed to retrieve network status: " + e.getMessage()));
            }
        });

        // Network servers endpoint
        webServer.get("/v1/network/servers", ctx -> {
            try {
                var servers = networkRegistry.getAllServers().get();
                
                Map<String, Object> response = new HashMap<>();
                response.put("servers", servers);
                response.put("totalCount", servers.size());

                ctx.json(response);
            } catch (Exception e) {
                log.error("[DatabaseHubGateway] Error in network servers endpoint", e);
                ctx.status(500).json(Map.of("error", "Failed to retrieve network servers: " + e.getMessage()));
            }
        });

        // Network addons endpoint
        webServer.get("/v1/network/addons", ctx -> {
            try {
                var addonsByServer = networkRegistry.getAllNetworkAddons().get();
                
                Map<String, Object> response = new HashMap<>();
                response.put("addonsByServer", addonsByServer);

                int totalAddons = addonsByServer.values().stream()
                        .mapToInt(List::size).sum();
                response.put("totalAddons", totalAddons);

                ctx.json(response);
            } catch (Exception e) {
                log.error("[DatabaseHubGateway] Error in network addons endpoint", e);
                ctx.status(500).json(Map.of("error", "Failed to retrieve network addons: " + e.getMessage()));
            }
        });

        // Server-specific addon endpoint
        webServer.get("/v1/network/servers/{serverId}/addons", ctx -> {
            try {
                String serverId = ctx.pathParam("serverId");
                var addons = networkRegistry.getServerAddons(serverId).get();
                
                Map<String, Object> response = new HashMap<>();
                response.put("serverId", serverId);
                response.put("addons", addons);
                response.put("count", addons.size());

                ctx.json(response);
            } catch (Exception e) {
                log.error("[DatabaseHubGateway] Error in server addons endpoint", e);
                ctx.status(500).json(Map.of("error", "Failed to retrieve server addons: " + e.getMessage()));
            }
        });

        // Debug endpoint to test basic functionality
        webServer.get("/v1/network/debug", ctx -> {
            try {
                Map<String, Object> debug = new HashMap<>();
                debug.put("isHubMode", CatWalkMain.instance.isHubMode());
                debug.put("currentServerId", networkRegistry.getCurrentServerId());
                debug.put("timestamp", System.currentTimeMillis());
                debug.put("databaseConnected", databaseManager != null);
                debug.put("message", "Network debug endpoint working");
                
                ctx.json(debug);
            } catch (Exception e) {
                log.error("[DatabaseHubGateway] Error in debug endpoint", e);
                ctx.status(500).json(Map.of("error", "Debug endpoint failed: " + e.getMessage()));
            }
        });

        log.info("[DatabaseHubGateway] Registered network management routes");
    }

    private void handleProxyRequest(String targetServerId, String originalPath, Context ctx) {
        String requestId = UUID.randomUUID().toString();

        log.debug("[DatabaseHubGateway] Proxying request {} to server '{}': {} {}",
                requestId, targetServerId, ctx.method(), originalPath);

        // Check if target server is online
        networkRegistry.getAllServers().thenAccept(servers -> {
            boolean serverOnline = servers.stream()
                    .anyMatch(s -> s.getServerId().equals(targetServerId) &&
                            s.getStatus().name().equals("ONLINE"));

            if (!serverOnline) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .json(Map.of("error", "Target server '" + targetServerId + "' is not available"));
                return;
            }

            // Create the request
            NetworkRequest request = NetworkRequest.builder()
                    .requestId(requestId)
                    .targetServerId(targetServerId)
                    .endpointPath(originalPath)
                    .httpMethod(convertJavalinMethod(ctx.method()))
                    .headers(new HashMap<>(ctx.headerMap()))
                    .queryParams(ctx.queryParamMap().entrySet().stream()
                            .collect(HashMap::new, (map, entry) ->
                                    map.put(entry.getKey(), String.join(",", entry.getValue())), HashMap::putAll))
                    .body(ctx.body())
                    .priority(0)
                    .timeoutSeconds(30)
                    .maxRetries(3)
                    .status(NetworkRequest.RequestStatus.PENDING)
                    .build();

            // Store the request in database
            storeRequest(request).thenCompose(success -> {
                if (!success) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to queue request"));
                    return CompletableFuture.completedFuture(null);
                }

                // Wait for response
                return waitForResponse(requestId, request.getTimeoutSeconds());
            }).thenAccept(response -> {
                if (response != null) {
                    handleProxyResponse(ctx, response);
                } else {
                    // Timeout or error
                    if (!ctx.res().isCommitted()) {
                        ctx.status(HttpStatus.GATEWAY_TIMEOUT)
                                .json(Map.of("error", "Request to target server timed out"));
                    }
                }
            }).exceptionally(throwable -> {
                log.error("[DatabaseHubGateway] Proxy request {} failed", requestId, throwable);
                if (!ctx.res().isCommitted()) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Internal gateway error"));
                }
                return null;
            });
        });
    }

    private CompletableFuture<Boolean> storeRequest(NetworkRequest request) {
        String sql = """
                INSERT INTO request_queue (request_id, target_server_id, endpoint_path, http_method,
                                         headers, query_params, body, priority, timeout_seconds, max_retries, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        return databaseManager.executeUpdateAsync(sql, stmt -> {
            stmt.setString(1, request.getRequestId());
            stmt.setString(2, request.getTargetServerId());
            stmt.setString(3, request.getEndpointPath());
            stmt.setString(4, request.getHttpMethod().name());
            stmt.setString(5, gson.toJson(request.getHeaders()));
            stmt.setString(6, gson.toJson(request.getQueryParams()));
            stmt.setString(7, request.getBody());
            stmt.setInt(8, request.getPriority());
            stmt.setInt(9, request.getTimeoutSeconds());
            stmt.setInt(10, request.getMaxRetries());
            stmt.setString(11, request.getStatus().name().toLowerCase());
        }).thenApply(rowsAffected -> rowsAffected > 0);
    }

    private CompletableFuture<NetworkResponse> waitForResponse(String requestId, int timeoutSeconds) {
        CompletableFuture<NetworkResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        // Set timeout
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            CompletableFuture<NetworkResponse> removed = pendingRequests.remove(requestId);
            if (removed != null && !removed.isDone()) {
                removed.completeExceptionally(new RuntimeException("Request timeout"));
            }
        }, timeoutSeconds * 20L); // Convert to ticks

        return future;
    }

    private void handleProxyResponse(Context ctx, NetworkResponse response) {
        try {
            // Set response status and headers
            ctx.status(response.getStatusCode());

            if (response.getHeaders() != null) {
                response.getHeaders().forEach((key, value) -> {
                    if (!key.equalsIgnoreCase("content-length")) {
                        ctx.header(key, value);
                    }
                });
            }

            // Set response body
            if (response.getBody() != null) {
                if (response.getContentType() != null && response.getContentType().contains("application/json")) {
                    ctx.json(response.getBody());
                } else {
                    ctx.result(response.getBody());
                }
            }

            log.debug("[DatabaseHubGateway] Proxy response completed in {}ms",
                    response.getProcessedTimeMs());

        } catch (Exception e) {
            log.error("[DatabaseHubGateway] Error handling proxy response", e);
            if (!ctx.res().isCommitted()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(Map.of("error", "Error processing response from target server"));
            }
        }
    }

    private void startResponsePolling() {
        responsePollingTask = new BukkitRunnable() {
            @Override
            public void run() {
                pollForResponses();
            }
        }.runTaskTimerAsynchronously(plugin, 20L, 40L); // Every 2 seconds

        log.info("[DatabaseHubGateway] Started response polling");
    }

    private void pollForResponses() {
        if (pendingRequests.isEmpty()) {
            return;
        }

        try {

            String sql = "SELECT * FROM response_queue " +
                    "WHERE request_id IN (" +
                    String.join(",", Collections.nCopies(pendingRequests.size(), "?")) +
                    ") ORDER BY created_at ASC";

            List<NetworkResponse> responses = databaseManager.executeQuery(sql, stmt -> {
                int index = 1;
                for (String requestId : pendingRequests.keySet()) {
                    stmt.setString(index++, requestId);
                }
            }, rs -> {
                List<NetworkResponse> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapResultSetToResponse(rs));
                }
                return list;
            });

            // Complete the futures
            for (NetworkResponse response : responses) {
                CompletableFuture<NetworkResponse> future = pendingRequests.remove(response.getRequestId());
                if (future != null && !future.isDone()) {
                    future.complete(response);
                }
            }

            // Clean up processed responses
            if (!responses.isEmpty()) {
                cleanupProcessedResponses(responses);
            }

        } catch (Exception e) {
            log.error("[DatabaseHubGateway] Error polling for responses", e);
        }
    }

    private void cleanupProcessedResponses(List<NetworkResponse> responses) {
        if (responses.isEmpty()) return;

        String sql = "DELETE FROM response_queue WHERE request_id IN (" +
                String.join(",", Collections.nCopies(responses.size(), "?")) + ")";

        databaseManager.executeUpdateAsync(sql, stmt -> {
            int index = 1;
            for (NetworkResponse response : responses) {
                stmt.setString(index++, response.getRequestId());
            }
        });
    }

    private NetworkResponse mapResultSetToResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, String> headers = null;

        try {
            String headersJson = rs.getString("headers");
            if (headersJson != null) {
                headers = gson.fromJson(headersJson, Map.class);
            }
        } catch (Exception e) {
            log.warn("[DatabaseHubGateway] Failed to parse response headers");
        }

        return NetworkResponse.builder()
                .requestId(rs.getString("request_id"))
                .serverId(rs.getString("server_id"))
                .statusCode(rs.getInt("status_code"))
                .headers(headers)
                .body(rs.getString("body"))
                .contentType(rs.getString("content_type"))
                .processedTimeMs(rs.getObject("processed_time_ms", Integer.class))
                .createdAt(rs.getTimestamp("created_at"))
                .build();
    }

    private HandlerType convertHttpMethod(HttpMethod method) {
        return switch (method) {
            case GET -> HandlerType.GET;
            case POST -> HandlerType.POST;
            case PUT -> HandlerType.PUT;
            case DELETE -> HandlerType.DELETE;
            case PATCH -> HandlerType.PATCH;
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };
    }

    private NetworkRequest.HttpMethod convertJavalinMethod(HandlerType method) {
        return NetworkRequest.HttpMethod.valueOf(method.name());
    }

    public void shutdown() {
        if (responsePollingTask != null) {
            responsePollingTask.cancel();
        }

        pendingRequests.values().forEach(future -> {
            if (!future.isDone()) {
                future.completeExceptionally(new RuntimeException("Gateway shutting down"));
            }
        });
        pendingRequests.clear();

        log.info("[DatabaseHubGateway] Hub gateway shut down");
    }
}