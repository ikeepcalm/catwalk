package dev.ua.ikeepcalm.catwalk.hub.network;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseManager;
import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkServer;
import dev.ua.ikeepcalm.catwalk.common.database.model.ServerAddon;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.OpenApiSchemas;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonSingleton;
import io.javalin.openapi.HttpMethod;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class NetworkRegistry {

    private static final Type METADATA_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Type ENDPOINTS_TYPE = new TypeToken<List<EndpointDefinition>>() {}.getType();

    private final DatabaseManager databaseManager;
    private final Gson gson = GsonSingleton.getInstance();
    @Getter
    private final String currentServerId;
    private final BukkitTask heartbeatTask;

    public NetworkRegistry(DatabaseManager databaseManager, String serverId, boolean hub) {
        this.databaseManager = databaseManager;
        this.currentServerId = serverId;

        registerServer(NetworkServer.builder()
                .serverId(serverId)
                .serverName(serverId)
                .serverType(hub ? NetworkServer.ServerType.HUB : NetworkServer.ServerType.BACKEND)
                .status(NetworkServer.Status.ONLINE)
                .maxPlayers(Bukkit.getMaxPlayers())
                .metadata(new HashMap<>())
                .build());

        this.heartbeatTask = Bukkit.getScheduler().runTaskTimer(CatWalkMain.getInstance(), this::heartbeat, 100L, 600L);
    }

    public void registerServer(NetworkServer server) {
        String sql = """
                INSERT INTO servers (server_id, server_name, server_type, host, port,
                                     online_players, max_players, status, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
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
        CatWalkLogger.info("Registered server '%s' as %s", server.getServerId(), server.getServerType());
    }

    private void heartbeat() {
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        String sql = """
                UPDATE servers
                SET online_players = ?, max_players = ?, status = 'online', last_heartbeat = CURRENT_TIMESTAMP
                WHERE server_id = ?
                """;

        databaseManager.executeUpdateAsync(sql, stmt -> {
            stmt.setInt(1, onlinePlayers);
            stmt.setInt(2, maxPlayers);
            stmt.setString(3, currentServerId);
        }).exceptionally(e -> {
            CatWalkLogger.error("Heartbeat for '%s' failed", e, currentServerId);
            return null;
        });
    }

    public void registerAddonFromHandler(String serverId, String addonName, Object handlerInstance) {
        List<EndpointDefinition> endpoints = OpenApiSchemas.endpoints(handlerInstance);
        registerAddon(ServerAddon.builder()
                .serverId(serverId)
                .addonName(addonName)
                .addonVersion("1.0.0")
                .enabled(true)
                .endpoints(endpoints)
                .openApiSpec(openApiSpec(addonName, endpoints))
                .build());
    }

    private void registerAddon(ServerAddon addon) {
        String sql = """
                INSERT INTO server_addons (server_id, addon_name, addon_version, enabled, endpoints, openapi_spec, registered_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    addon_version = VALUES(addon_version),
                    enabled = VALUES(enabled),
                    endpoints = VALUES(endpoints),
                    openapi_spec = VALUES(openapi_spec),
                    updated_at = CURRENT_TIMESTAMP
                """;

        try {
            databaseManager.executeUpdate(sql, stmt -> {
                stmt.setString(1, addon.getServerId());
                stmt.setString(2, addon.getAddonName());
                stmt.setString(3, addon.getAddonVersion());
                stmt.setBoolean(4, addon.isEnabled());
                stmt.setString(5, gson.toJson(addon.getEndpoints()));
                stmt.setString(6, gson.toJson(addon.getOpenApiSpec()));
            });
            CatWalkLogger.info("Registered addon '%s' for server '%s' with %d endpoints",
                    addon.getAddonName(), addon.getServerId(), addon.getEndpoints().size());
        } catch (DatabaseManager.DatabaseException e) {
            CatWalkLogger.error("Failed to register addon '%s' for server '%s'", e, addon.getAddonName(), addon.getServerId());
        }
    }

    public CompletableFuture<List<NetworkServer>> getAllServers() {
        return databaseManager.executeQueryAsync("SELECT * FROM servers WHERE status = 'online' ORDER BY server_type, server_id", null, rs -> {
            List<NetworkServer> servers = new ArrayList<>();
            while (rs.next()) {
                servers.add(mapServer(rs));
            }
            return servers;
        });
    }

    public CompletableFuture<Boolean> isServerOnline(String serverId) {
        return databaseManager.executeQueryAsync("SELECT 1 FROM servers WHERE server_id = ? AND status = 'online'",
                stmt -> stmt.setString(1, serverId), ResultSet::next);
    }

    public CompletableFuture<List<ServerAddon>> getServerAddons(String serverId) {
        return databaseManager.executeQueryAsync("SELECT * FROM server_addons WHERE server_id = ? AND enabled = TRUE ORDER BY addon_name",
                stmt -> stmt.setString(1, serverId), rs -> {
                    List<ServerAddon> addons = new ArrayList<>();
                    while (rs.next()) {
                        addons.add(mapAddon(rs));
                    }
                    return addons;
                });
    }

    public CompletableFuture<Map<String, List<ServerAddon>>> getAllNetworkAddons() {
        return databaseManager.executeQueryAsync("""
                SELECT sa.* FROM server_addons sa
                JOIN servers s ON sa.server_id = s.server_id
                WHERE sa.enabled = TRUE AND s.status = 'online'
                ORDER BY sa.server_id, sa.addon_name
                """, null, rs -> {
            Map<String, List<ServerAddon>> addonsByServer = new LinkedHashMap<>();
            while (rs.next()) {
                ServerAddon addon = mapAddon(rs);
                addonsByServer.computeIfAbsent(addon.getServerId(), k -> new ArrayList<>()).add(addon);
            }
            return addonsByServer;
        });
    }

    private Map<String, Object> openApiSpec(String addonName, List<EndpointDefinition> endpoints) {
        Map<String, Object> paths = new LinkedHashMap<>();
        for (EndpointDefinition endpoint : endpoints) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pathItem = (Map<String, Object>) paths.computeIfAbsent(endpoint.getPath(), p -> new LinkedHashMap<>());
            for (HttpMethod method : endpoint.getMethods()) {
                pathItem.put(method.name().toLowerCase(), OpenApiSchemas.operation(endpoint));
            }
        }
        return Map.of(
                "openapi", "3.0.3",
                "info", Map.of("title", addonName + " API", "version", "1.0.0"),
                "paths", paths
        );
    }

    private NetworkServer mapServer(ResultSet rs) throws SQLException {
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
                .metadata(parseJson(rs.getString("metadata"), METADATA_TYPE, new HashMap<>(), rs.getString("server_id")))
                .build();
    }

    private ServerAddon mapAddon(ResultSet rs) throws SQLException {
        String addonName = rs.getString("addon_name");
        return ServerAddon.builder()
                .serverId(rs.getString("server_id"))
                .addonName(addonName)
                .addonVersion(rs.getString("addon_version"))
                .enabled(rs.getBoolean("enabled"))
                .endpoints(parseJson(rs.getString("endpoints"), ENDPOINTS_TYPE, new ArrayList<>(), addonName))
                .openApiSpec(parseJson(rs.getString("openapi_spec"), METADATA_TYPE, null, addonName))
                .registeredAt(rs.getTimestamp("registered_at"))
                .updatedAt(rs.getTimestamp("updated_at"))
                .build();
    }

    private <T> T parseJson(String json, Type type, T fallback, String owner) {
        if (json == null) {
            return fallback;
        }
        try {
            T value = gson.fromJson(json, type);
            return value != null ? value : fallback;
        } catch (JsonParseException e) {
            CatWalkLogger.warn("Ignoring malformed JSON stored for '%s': %s", owner, e.getMessage());
            return fallback;
        }
    }

    public void shutdown() {
        heartbeatTask.cancel();
        try {
            databaseManager.executeUpdate("UPDATE servers SET status = 'offline' WHERE server_id = ?", stmt -> stmt.setString(1, currentServerId));
        } catch (DatabaseManager.DatabaseException e) {
            CatWalkLogger.error("Failed to mark server '%s' offline", e, currentServerId);
        }
    }
}
