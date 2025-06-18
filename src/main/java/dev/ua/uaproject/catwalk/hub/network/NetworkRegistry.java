package dev.ua.uaproject.catwalk.hub.network;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.common.database.DatabaseManager;
import dev.ua.uaproject.catwalk.common.database.model.EndpointDefinition;
import dev.ua.uaproject.catwalk.common.database.model.NetworkServer;
import dev.ua.uaproject.catwalk.common.database.model.ServerAddon;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class NetworkRegistry {

    private final DatabaseManager databaseManager;
    private final Gson gson;

    @Getter
    private final String currentServerId;
    private final boolean isHubServer;

    private final Map<String, NetworkServer> serverCache = new ConcurrentHashMap<>();
    private final Map<String, List<ServerAddon>> addonCache = new ConcurrentHashMap<>();

    private BukkitTask heartbeatTask;
    private BukkitTask cacheRefreshTask;

    public NetworkRegistry(DatabaseManager databaseManager, String serverId, boolean isHub) {
        this.databaseManager = databaseManager;
        this.gson = new Gson();
        this.currentServerId = serverId;
        this.isHubServer = isHub;

        initializeCurrentServer();
        startHeartbeat();
        startCacheRefresh();
    }

    private void initializeCurrentServer() {
        NetworkServer currentServer = NetworkServer.builder()
                .serverId(currentServerId)
                .serverName(currentServerId)
                .serverType(isHubServer ? NetworkServer.ServerType.HUB : NetworkServer.ServerType.BACKEND)
                .status(NetworkServer.Status.ONLINE)
                .onlinePlayers(0)
                .maxPlayers(100)
                .metadata(new HashMap<>())
                .build();

        registerServer(currentServer);
        log.info("[NetworkRegistry] Initialized server '{}' as {}", currentServerId,
                isHubServer ? "HUB" : "BACKEND");
    }

    // Server management
    public void registerServer(NetworkServer server) {
        String sql = """
                INSERT INTO servers (server_id, server_name, server_type, host, port, 
                                   online_players, max_players, status, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    server_name = VALUES(server_name),
                    server_type = VALUES(server_type),
                    host = VALUES(host),
                    port = VALUES(port),
                    online_players = VALUES(online_players),
                    max_players = VALUES(max_players),
                    status = VALUES(status),
                    metadata = VALUES(metadata),
                    last_heartbeat = CURRENT_TIMESTAMP
                """;

        try {
            databaseManager.executeUpdate(sql, stmt -> {
                stmt.setString(1, server.getServerId());
                stmt.setString(2, server.getServerName());
                stmt.setString(3, server.getServerType().name().toLowerCase());
                stmt.setString(4, server.getHost());
                stmt.setObject(5, server.getPort());
                stmt.setInt(6, server.getOnlinePlayers());
                stmt.setInt(7, server.getMaxPlayers());
                stmt.setString(8, server.getStatus().name().toLowerCase());
                stmt.setString(9, gson.toJson(server.getMetadata()));
            });

            serverCache.put(server.getServerId(), server);
            log.debug("[NetworkRegistry] Registered server: {}", server.getServerId());

        } catch (Exception e) {
            log.error("[NetworkRegistry] Failed to register server: {}", server.getServerId(), e);
        }
    }

    public void updateServerStats(String serverId, int onlinePlayers, int maxPlayers) {
        String sql = """
                UPDATE servers 
                SET online_players = ?, max_players = ?, last_heartbeat = CURRENT_TIMESTAMP
                WHERE server_id = ?
                """;

        databaseManager.executeUpdateAsync(sql, stmt -> {
            stmt.setInt(1, onlinePlayers);
            stmt.setInt(2, maxPlayers);
            stmt.setString(3, serverId);
        }).thenRun(() -> {
            NetworkServer cached = serverCache.get(serverId);
            if (cached != null) {
                cached.setOnlinePlayers(onlinePlayers);
                cached.setMaxPlayers(maxPlayers);
            }
        });
    }

    // Addon management
    public void registerAddon(String serverId, ServerAddon addon) {
        String sql = """
                INSERT INTO server_addons (server_id, addon_name, addon_version, enabled, endpoints, openapi_spec)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    addon_version = VALUES(addon_version),
                    enabled = VALUES(enabled),
                    endpoints = VALUES(endpoints),
                    openapi_spec = VALUES(openapi_spec),
                    updated_at = CURRENT_TIMESTAMP
                """;

        try {
            databaseManager.executeUpdate(sql, stmt -> {
                stmt.setString(1, serverId);
                stmt.setString(2, addon.getAddonName());
                stmt.setString(3, addon.getAddonVersion());
                stmt.setBoolean(4, addon.isEnabled());
                stmt.setString(5, gson.toJson(addon.getEndpoints()));
                stmt.setString(6, addon.getOpenApiSpec() != null ? gson.toJson(addon.getOpenApiSpec()) : null);
            });

            // Update cache
            addonCache.computeIfAbsent(serverId, k -> new ArrayList<>())
                    .removeIf(existing -> existing.getAddonName().equals(addon.getAddonName()));
            addonCache.get(serverId).add(addon);

            log.info("[NetworkRegistry] Registered addon '{}' for server '{}' with {} endpoints",
                    addon.getAddonName(), serverId, addon.getEndpoints().size());

        } catch (Exception e) {
            log.error("[NetworkRegistry] Failed to register addon '{}' for server '{}'",
                    addon.getAddonName(), serverId, e);
        }
    }

    public void registerAddonFromHandler(String serverId, String addonName, Object handlerInstance) {
        List<EndpointDefinition> endpoints = extractEndpointsFromHandler(handlerInstance);
        Map<String, Object> openApiSpec = generateOpenApiSpec(addonName, endpoints);

        ServerAddon addon = ServerAddon.builder()
                .serverId(serverId)
                .addonName(addonName)
                .addonVersion("1.0.0") // Could be extracted from plugin metadata
                .enabled(true)
                .endpoints(endpoints)
                .openApiSpec(openApiSpec)
                .build();

        registerAddon(serverId, addon);
    }

    // Data retrieval methods
    public CompletableFuture<List<NetworkServer>> getAllServers() {
        return databaseManager.executeQueryAsync(
                "SELECT * FROM servers WHERE status = 'online' ORDER BY server_type, server_id",
                null,
                rs -> {
                    List<NetworkServer> servers = new ArrayList<>();
                    while (rs.next()) {
                        servers.add(mapResultSetToServer(rs));
                    }
                    return servers;
                }
        );
    }

    public CompletableFuture<List<ServerAddon>> getServerAddons(String serverId) {
        return databaseManager.executeQueryAsync(
                "SELECT * FROM server_addons WHERE server_id = ? AND enabled = TRUE ORDER BY addon_name",
                stmt -> stmt.setString(1, serverId),
                rs -> {
                    List<ServerAddon> addons = new ArrayList<>();
                    while (rs.next()) {
                        addons.add(mapResultSetToAddon(rs));
                    }
                    return addons;
                }
        );
    }

    public CompletableFuture<Map<String, List<ServerAddon>>> getAllNetworkAddons() {
        return databaseManager.executeQueryAsync(
                """
                        SELECT sa.*, s.server_type FROM server_addons sa
                        JOIN servers s ON sa.server_id = s.server_id
                        WHERE sa.enabled = TRUE AND s.status = 'online'
                        ORDER BY sa.server_id, sa.addon_name
                        """,
                null,
                rs -> {
                    Map<String, List<ServerAddon>> addonsByServer = new HashMap<>();
                    while (rs.next()) {
                        ServerAddon addon = mapResultSetToAddon(rs);
                        addonsByServer.computeIfAbsent(addon.getServerId(), k -> new ArrayList<>())
                                .add(addon);
                    }
                    return addonsByServer;
                }
        );
    }

    public List<EndpointDefinition> getAllNetworkEndpoints() {
        // Use cache if available for performance
        List<EndpointDefinition> allEndpoints = new ArrayList<>();

        for (List<ServerAddon> serverAddons : addonCache.values()) {
            for (ServerAddon addon : serverAddons) {
                allEndpoints.addAll(addon.getEndpoints());
            }
        }

        return allEndpoints;
    }

    // Cache management
    private void refreshCache() {
        // Refresh server cache
        getAllServers().thenAccept(servers -> {
            serverCache.clear();
            servers.forEach(server -> serverCache.put(server.getServerId(), server));
        });

        // Refresh addon cache
        getAllNetworkAddons().thenAccept(addonsByServer -> {
            addonCache.clear();
            addonCache.putAll(addonsByServer);
        });
    }

    // Background tasks
    private void startHeartbeat() {
        heartbeatTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateServerStats(currentServerId,
                        org.bukkit.Bukkit.getOnlinePlayers().size(),
                        org.bukkit.Bukkit.getMaxPlayers());
            }
        }.runTaskTimerAsynchronously(CatWalkMain.instance, 100L, 600L); // Every 30 seconds
    }

    private void startCacheRefresh() {
        cacheRefreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                refreshCache();
            }
        }.runTaskTimerAsynchronously(CatWalkMain.instance, 200L, 2400L); // Every 2 minutes
    }

    // Helper methods
    private List<EndpointDefinition> extractEndpointsFromHandler(Object handlerInstance) {
        List<EndpointDefinition> endpoints = new ArrayList<>();

        Class<?> clazz = handlerInstance.getClass();
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            io.javalin.openapi.OpenApi openApiAnnotation = method.getAnnotation(io.javalin.openapi.OpenApi.class);
            if (openApiAnnotation == null) continue;

            dev.ua.uaproject.catwalk.bridge.annotations.BridgeEventHandler bridgeAnnotation =
                    method.getAnnotation(dev.ua.uaproject.catwalk.bridge.annotations.BridgeEventHandler.class);

            EndpointDefinition endpoint = EndpointDefinition.builder()
                    .path(openApiAnnotation.path())
                    .methods(Arrays.asList(openApiAnnotation.methods()))
                    .summary(openApiAnnotation.summary())
                    .description(openApiAnnotation.description())
                    .tags(Arrays.asList(openApiAnnotation.tags()))
                    .requiresAuth(bridgeAnnotation == null || bridgeAnnotation.requiresAuth())
                    .build();

            endpoints.add(endpoint);
        }

        return endpoints;
    }

    private Map<String, Object> generateOpenApiSpec(String addonName, List<EndpointDefinition> endpoints) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("openapi", "3.0.0");

        Map<String, Object> info = new HashMap<>();
        info.put("title", addonName + " API");
        info.put("version", "1.0.0");
        spec.put("info", info);

        Map<String, Object> paths = new HashMap<>();
        for (EndpointDefinition endpoint : endpoints) {
            Map<String, Object> pathItem = new HashMap<>();

            for (io.javalin.openapi.HttpMethod method : endpoint.getMethods()) {
                Map<String, Object> operation = new HashMap<>();
                operation.put("summary", endpoint.getSummary());
                operation.put("description", endpoint.getDescription());
                operation.put("tags", endpoint.getTags());

                pathItem.put(method.name().toLowerCase(), operation);
            }

            paths.put(endpoint.getPath(), pathItem);
        }
        spec.put("paths", paths);

        return spec;
    }

    private NetworkServer mapResultSetToServer(java.sql.ResultSet rs) throws java.sql.SQLException {
        Type metadataType = new TypeToken<Map<String, Object>>() {
        }.getType();
        Map<String, Object> metadata;

        try {
            String metadataJson = rs.getString("metadata");
            metadata = metadataJson != null ? gson.fromJson(metadataJson, metadataType) : new HashMap<>();
        } catch (JsonSyntaxException e) {
            log.warn("[NetworkRegistry] Failed to parse server metadata, using empty map");
            metadata = new HashMap<>();
        }

        return NetworkServer.builder()
                .serverId(rs.getString("server_id"))
                .serverName(rs.getString("server_name"))
                .serverType(NetworkServer.ServerType.valueOf(rs.getString("server_type").toUpperCase()))
                .host(rs.getString("host"))
                .port(rs.getObject("port", Integer.class))
                .onlinePlayers(rs.getInt("online_players"))
                .maxPlayers(rs.getInt("max_players"))
                .status(NetworkServer.Status.valueOf(rs.getString("status").toUpperCase()))
                .lastHeartbeat(rs.getTimestamp("last_heartbeat"))
                .createdAt(rs.getTimestamp("created_at"))
                .metadata(metadata)
                .build();
    }

    private ServerAddon mapResultSetToAddon(java.sql.ResultSet rs) throws java.sql.SQLException {
        Type endpointsType = new TypeToken<List<EndpointDefinition>>() {
        }.getType();
        Type openApiType = new TypeToken<Map<String, Object>>() {
        }.getType();

        List<EndpointDefinition> endpoints;
        Map<String, Object> openApiSpec = null;

        try {
            String endpointsJson = rs.getString("endpoints");
            endpoints = gson.fromJson(endpointsJson, endpointsType);

            String openApiJson = rs.getString("openapi_spec");
            if (openApiJson != null) {
                openApiSpec = gson.fromJson(openApiJson, openApiType);
            }
        } catch (JsonSyntaxException e) {
            log.warn("[NetworkRegistry] Failed to parse addon data for {}", rs.getString("addon_name"));
            endpoints = new ArrayList<>();
        }

        return ServerAddon.builder()
                .serverId(rs.getString("server_id"))
                .addonName(rs.getString("addon_name"))
                .addonVersion(rs.getString("addon_version"))
                .enabled(rs.getBoolean("enabled"))
                .endpoints(endpoints)
                .openApiSpec(openApiSpec)
                .registeredAt(rs.getTimestamp("registered_at"))
                .updatedAt(rs.getTimestamp("updated_at"))
                .build();
    }

    public void shutdown() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (cacheRefreshTask != null) {
            cacheRefreshTask.cancel();
        }

        // Mark server as offline
        String sql = "UPDATE servers SET status = 'offline' WHERE server_id = ?";
        try {
            databaseManager.executeUpdate(sql, stmt -> stmt.setString(1, currentServerId));
            log.info("[NetworkRegistry] Marked server '{}' as offline", currentServerId);
        } catch (Exception e) {
            log.error("[NetworkRegistry] Failed to mark server as offline", e);
        }
    }
}