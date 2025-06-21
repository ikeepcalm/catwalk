package dev.ua.uaproject.catwalk.hub.network;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.bridge.annotations.ApiProperty;
import dev.ua.uaproject.catwalk.bridge.annotations.ApiSchema;
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
        NetworkServer currentServer = NetworkServer.builder().serverId(currentServerId).serverName(currentServerId).serverType(isHubServer ? NetworkServer.ServerType.HUB : NetworkServer.ServerType.BACKEND).status(NetworkServer.Status.ONLINE).onlinePlayers(0).maxPlayers(100).metadata(new HashMap<>()).build();

        registerServer(currentServer);
        log.info("[NetworkRegistry] Initialized server '{}' as {}", currentServerId, isHubServer ? "HUB" : "BACKEND");
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
            addonCache.computeIfAbsent(serverId, k -> new ArrayList<>()).removeIf(existing -> existing.getAddonName().equals(addon.getAddonName()));
            addonCache.get(serverId).add(addon);

            log.info("[NetworkRegistry] Registered addon '{}' for server '{}' with {} endpoints", addon.getAddonName(), serverId, addon.getEndpoints().size());

        } catch (Exception e) {
            log.error("[NetworkRegistry] Failed to register addon '{}' for server '{}'", addon.getAddonName(), serverId, e);
        }
    }

    public void registerAddonFromHandler(String serverId, String addonName, Object handlerInstance) {
        List<EndpointDefinition> endpoints = extractEndpointsFromHandler(handlerInstance);
        Map<String, Object> openApiSpec = generateOpenApiSpec(addonName, endpoints);

        ServerAddon addon = ServerAddon.builder().serverId(serverId).addonName(addonName).addonVersion("1.0.0").enabled(true).endpoints(endpoints).openApiSpec(openApiSpec).build();

        registerAddon(serverId, addon);
    }

    // Data retrieval methods
    public CompletableFuture<List<NetworkServer>> getAllServers() {
        return databaseManager.executeQueryAsync("SELECT * FROM servers WHERE status = 'online' ORDER BY server_type, server_id", null, rs -> {
            List<NetworkServer> servers = new ArrayList<>();
            while (rs.next()) {
                servers.add(mapResultSetToServer(rs));
            }
            return servers;
        });
    }

    public CompletableFuture<List<ServerAddon>> getServerAddons(String serverId) {
        return databaseManager.executeQueryAsync("SELECT * FROM server_addons WHERE server_id = ? AND enabled = TRUE ORDER BY addon_name", stmt -> stmt.setString(1, serverId), rs -> {
            List<ServerAddon> addons = new ArrayList<>();
            while (rs.next()) {
                addons.add(mapResultSetToAddon(rs));
            }
            return addons;
        });
    }

    public CompletableFuture<Map<String, List<ServerAddon>>> getAllNetworkAddons() {
        return databaseManager.executeQueryAsync("""
                SELECT sa.*, s.server_type FROM server_addons sa
                JOIN servers s ON sa.server_id = s.server_id
                WHERE sa.enabled = TRUE AND s.status = 'online'
                ORDER BY sa.server_id, sa.addon_name
                """, null, rs -> {
            Map<String, List<ServerAddon>> addonsByServer = new HashMap<>();
            while (rs.next()) {
                ServerAddon addon = mapResultSetToAddon(rs);
                addonsByServer.computeIfAbsent(addon.getServerId(), k -> new ArrayList<>()).add(addon);
            }
            return addonsByServer;
        });
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
                updateServerStats(currentServerId, org.bukkit.Bukkit.getOnlinePlayers().size(), org.bukkit.Bukkit.getMaxPlayers());
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

            dev.ua.uaproject.catwalk.bridge.annotations.BridgeEventHandler bridgeAnnotation = method.getAnnotation(dev.ua.uaproject.catwalk.bridge.annotations.BridgeEventHandler.class);

            // Extract request body schema if present
            Map<String, Object> requestBodySchema = null;
            if (openApiAnnotation.requestBody() != null && openApiAnnotation.requestBody().content().length > 0) {
                requestBodySchema = extractRequestBodySchema(openApiAnnotation.requestBody());
            }

            // Extract response schemas if present
            Map<String, Object> responseSchemas = new HashMap<>();
            if (openApiAnnotation.responses().length > 0) {
                responseSchemas = extractResponseSchemas(openApiAnnotation.responses());
            }

            // Extract parameters
            Map<String, Object> parameters = extractParameters(openApiAnnotation);

            EndpointDefinition endpoint = EndpointDefinition.builder().path(openApiAnnotation.path()).methods(Arrays.asList(openApiAnnotation.methods())).summary(openApiAnnotation.summary()).description(openApiAnnotation.description()).tags(Arrays.asList(openApiAnnotation.tags())).requiresAuth(bridgeAnnotation == null || bridgeAnnotation.requiresAuth()).parameters(parameters).requestBody(requestBodySchema).responses(responseSchemas).build();

            endpoints.add(endpoint);
        }

        return endpoints;
    }

// Replace the extractRequestBodySchema method in NetworkRegistry.java:

    private Map<String, Object> extractRequestBodySchema(io.javalin.openapi.OpenApiRequestBody requestBody) {
        Map<String, Object> requestBodySchema = new HashMap<>();

        if (!requestBody.description().isEmpty()) {
            requestBodySchema.put("description", requestBody.description());
        }
        requestBodySchema.put("required", requestBody.required());

        Map<String, Object> content = new HashMap<>();

        for (io.javalin.openapi.OpenApiContent contentItem : requestBody.content()) {
            Map<String, Object> mediaType = new HashMap<>();


            // Get the actual class from the 'from' attribute with error handling
            Class<?> typeClass = null;
            String mimeType = "application/json"; // Default mime type

            try {
                // Try to get the class from the 'from' attribute first
                try {
                    Class<?> fromClass = contentItem.from();
                    if (fromClass != Object.class) {
                        typeClass = fromClass;
                    }
                } catch (TypeNotPresentException | NoClassDefFoundError e) {
                    // Continue to try type() method
                }

                // Fallback to type() if from() didn't work
                if (typeClass == null && !contentItem.type().equals("-- This string represents a null value and shouldn't be used --")) {
                    typeClass = Class.forName(contentItem.type());
                }

                // Get mime type - use default if it's the auto-detect string
                if (!contentItem.mimeType().equals("-- This string represents a null value and shouldn't be used --")) {
                    mimeType = contentItem.mimeType();
                }

                if (typeClass != null) {
                    Map<String, Object> schema = generateDetailedSchemaMap(typeClass);
                    mediaType.put("schema", schema);
                } else {
                    log.warn("Could not determine class for content item, using generic object schema");
                    Map<String, Object> schema = new HashMap<>();
                    schema.put("type", "object");
                    mediaType.put("schema", schema);
                }

            } catch (ClassNotFoundException e) {
                log.warn("Could not find class for schema generation: {}", contentItem.type());
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                mediaType.put("schema", schema);
            } catch (Exception e) {
                log.warn("Error processing content item: {}", e.getMessage());
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                mediaType.put("schema", schema);
            }

            // IMPORTANT: Add the example from the annotation
            if (!contentItem.example().isEmpty() &&
                    !contentItem.example().equals("-- This string represents a null value and shouldn't be used --")) {
                try {
                    // Try to parse as JSON
                    Object example = gson.fromJson(contentItem.example(), Object.class);
                    mediaType.put("example", example);
                } catch (Exception e) {
                    mediaType.put("example", contentItem.example());
                }
            }

            content.put(mimeType, mediaType);
        }

        requestBodySchema.put("content", content);
        return requestBodySchema;
    }

    private Map<String, Object> extractResponseSchemas(io.javalin.openapi.OpenApiResponse[] responses) {
        Map<String, Object> responseSchemas = new HashMap<>();

        for (io.javalin.openapi.OpenApiResponse response : responses) {
            Map<String, Object> responseObj = new HashMap<>();
            responseObj.put("description", response.description());

            if (response.content().length > 0) {
                Map<String, Object> content = new HashMap<>();
                for (io.javalin.openapi.OpenApiContent contentItem : response.content()) {
                    Map<String, Object> mediaType = new HashMap<>();

                    // Get the actual class from the 'from' attribute with error handling
                    Class<?> typeClass = null;
                    String mimeType = "application/json"; // Default mime type

                    try {
                        // Try to get the class from the 'from' attribute first
                        try {
                            Class<?> fromClass = contentItem.from();
                            if (fromClass != Object.class) {
                                typeClass = fromClass;
                            }
                        } catch (TypeNotPresentException | NoClassDefFoundError e) {
                            // Continue to try type() method
                        }

                        // Fallback to type() if from() didn't work
                        if (typeClass == null && !contentItem.type().equals("AUTODETECT - Will be replaced later")) {
                            typeClass = Class.forName(contentItem.type());
                        }

                        // Get mime type - use default if it's the auto-detect string
                        if (!contentItem.mimeType().equals("AUTODETECT - Will be replaced later")) {
                            mimeType = contentItem.mimeType();
                        }

                        if (typeClass != null) {
                            Map<String, Object> schema = generateDetailedSchemaMap(typeClass);
                            mediaType.put("schema", schema);
                        } else {
                            log.warn("Could not determine response class for content item, using generic object schema");
                            Map<String, Object> schema = new HashMap<>();
                            schema.put("type", "object");
                            mediaType.put("schema", schema);
                        }

                    } catch (ClassNotFoundException e) {
                        log.warn("Could not find response class: {}", contentItem.type());
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        mediaType.put("schema", schema);
                    } catch (Exception e) {
                        log.warn("Error processing response content item: {}", e.getMessage());
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        mediaType.put("schema", schema);
                    }

                    // Only add example if it's not the null placeholder
                    if (!contentItem.example().isEmpty() &&
                            !contentItem.example().equals("-- This string represents a null value and shouldn't be used --")) {
                        try {
                            Object example = gson.fromJson(contentItem.example(), Object.class);
                            mediaType.put("example", example);
                        } catch (Exception e) {
                            mediaType.put("example", contentItem.example());
                        }
                    }

                    content.put(mimeType, mediaType);
                }
                responseObj.put("content", content);
            }

            responseSchemas.put(response.status(), responseObj);
        }

        return responseSchemas;
    }

    private Map<String, Object> extractParameters(io.javalin.openapi.OpenApi openApiAnnotation) {
        Map<String, Object> allParameters = new HashMap<>();

        // Query parameters
        if (openApiAnnotation.queryParams().length > 0) {
            List<Map<String, Object>> queryParams = new ArrayList<>();
            for (var queryParam : openApiAnnotation.queryParams()) {
                Map<String, Object> param = new HashMap<>();
                param.put("name", queryParam.name());
                param.put("in", "query");
                param.put("required", queryParam.required());
                if (!queryParam.description().isEmpty()) {
                    param.put("description", queryParam.description());
                }

                Map<String, Object> schema = new HashMap<>();
                schema.put("type", getOpenApiTypeString(queryParam.type()));
                param.put("schema", schema);

                queryParams.add(param);
            }
            allParameters.put("query", queryParams);
        }

        // Path parameters
        if (openApiAnnotation.pathParams().length > 0) {
            List<Map<String, Object>> pathParams = new ArrayList<>();
            for (var pathParam : openApiAnnotation.pathParams()) {
                Map<String, Object> param = new HashMap<>();
                param.put("name", pathParam.name());
                param.put("in", "path");
                param.put("required", true);
                if (!pathParam.description().isEmpty()) {
                    param.put("description", pathParam.description());
                }

                Map<String, Object> schema = new HashMap<>();
                schema.put("type", getOpenApiTypeString(pathParam.type()));
                param.put("schema", schema);

                pathParams.add(param);
            }
            allParameters.put("path", pathParams);
        }

        return allParameters;
    }

    /**
     * Generate detailed schema as Map (for JSON serialization to database)
     */
    private Map<String, Object> generateDetailedSchemaMap(Class<?> clazz) {
        Map<String, Object> schema = new HashMap<>();


        // Check for @ApiSchema annotation first
        if (clazz.isAnnotationPresent(ApiSchema.class)) {

            ApiSchema apiSchema =
                    clazz.getAnnotation(ApiSchema.class);

            schema.put("type", "object");

            if (!apiSchema.description().isEmpty()) {
                schema.put("description", apiSchema.description());
            }

            Map<String, Object> properties = new HashMap<>();
            List<String> required = new ArrayList<>();


            for (ApiProperty property : apiSchema.properties()) {
                Map<String, Object> propSchema = new HashMap<>();
                propSchema.put("type", property.type());

                if (!property.description().isEmpty()) {
                    propSchema.put("description", property.description());
                }

                if (!property.example().isEmpty()) {
                    propSchema.put("example", property.example());
                }

                properties.put(property.name(), propSchema);

                if (property.required()) {
                    required.add(property.name());
                }
            }

            if (!properties.isEmpty()) {
                schema.put("properties", properties);
            }

            if (!required.isEmpty()) {
                schema.put("required", required);
            }

            return schema;
        } else {
        }

        // Fallback to reflection-based generation
        return generateSchemaFromReflectionMap(clazz);
    }


    /**
     * Fallback schema generation using reflection (as Map)
     */
    private Map<String, Object> generateSchemaFromReflectionMap(Class<?> clazz) {
        Map<String, Object> schema = new HashMap<>();

        // Handle primitives and basic types
        if (isPrimitiveOrWrapper(clazz) || clazz == String.class) {
            schema.put("type", getOpenApiTypeString(clazz));
            return schema;
        }

        if (clazz.isArray()) {
            schema.put("type", "array");
            Map<String, Object> items = generateDetailedSchemaMap(clazz.getComponentType());
            schema.put("items", items);
            return schema;
        }

        if (Collection.class.isAssignableFrom(clazz)) {
            schema.put("type", "array");
            Map<String, Object> items = new HashMap<>();
            items.put("type", "object");
            schema.put("items", items);
            return schema;
        }

        // For complex objects, use reflection
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        try {
            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();

            for (java.lang.reflect.Field field : fields) {
                // Skip static, transient, and synthetic fields
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || java.lang.reflect.Modifier.isTransient(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }

                // Skip fields marked with @JsonIgnore
                if (field.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                    continue;
                }

                String fieldName = getJsonPropertyName(field);
                Map<String, Object> fieldSchema = new HashMap<>();
                fieldSchema.put("type", getOpenApiTypeString(field.getType()));

                // Add description based on field name
                String description = generateFieldDescription(field.getName());
                if (description != null && !description.isEmpty()) {
                    fieldSchema.put("description", description);
                }

                properties.put(fieldName, fieldSchema);

                // Mark primitive types as required by default
                if (field.getType().isPrimitive()) {
                    required.add(fieldName);
                }
            }

        } catch (Exception e) {
            log.warn("Error generating schema for class {}: {}", clazz.getName(), e.getMessage());
        }

        if (!properties.isEmpty()) {
            schema.put("properties", properties);
        }

        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * Get the JSON property name for a field
     */
    private String getJsonPropertyName(java.lang.reflect.Field field) {
        com.fasterxml.jackson.annotation.JsonProperty jsonProperty = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);

        if (jsonProperty != null && !jsonProperty.value().isEmpty()) {
            return jsonProperty.value();
        }

        return field.getName();
    }

    /**
     * Generate field descriptions
     */
    private String generateFieldDescription(String fieldName) {
        String lowerFieldName = fieldName.toLowerCase().replace("_", "");

        return switch (lowerFieldName) {
            case "userid" -> "Unique identifier for the user";
            case "usernickname" -> "Minecraft username/nickname";
            case "servicename" -> "Name of the service or product";
            case "discordid" -> "Discord user identifier";
            case "status" -> "Current status of the item";
            case "oldnickname" -> "Previous nickname";
            case "newnickname" -> "New nickname";
            case "applicationstatus" -> "Status of the application";
            case "success" -> "Whether the operation was successful";
            case "message" -> "Human-readable message describing the result";
            case "data" -> "Response data payload";
            default -> "The " + fieldName + " field";
        };
    }

    /**
     * Check if a type is primitive or wrapper
     */
    private boolean isPrimitiveOrWrapper(Class<?> type) {
        return type.isPrimitive() || type.equals(Integer.class) || type.equals(Long.class) || type.equals(Double.class) || type.equals(Float.class) || type.equals(Boolean.class) || type.equals(Character.class) || type.equals(Byte.class) || type.equals(Short.class) || type.equals(String.class);
    }

    /**
     * Get OpenAPI type as string
     */
    private String getOpenApiTypeString(Class<?> javaType) {
        if (javaType == String.class) return "string";
        if (javaType == Integer.class || javaType == int.class) return "integer";
        if (javaType == Long.class || javaType == long.class) return "integer";
        if (javaType == Double.class || javaType == double.class || javaType == Float.class || javaType == float.class)
            return "number";
        if (javaType == Boolean.class || javaType == boolean.class) return "boolean";
        if (javaType.isArray() || Collection.class.isAssignableFrom(javaType)) return "array";
        return "object";
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

                // Add detailed parameters if available
                if (endpoint.getParameters() != null && !endpoint.getParameters().isEmpty()) {
                    List<Map<String, Object>> allParams = new ArrayList<>();

                    if (endpoint.getParameters().containsKey("query")) {
                        allParams.addAll((List<Map<String, Object>>) endpoint.getParameters().get("query"));
                    }
                    if (endpoint.getParameters().containsKey("path")) {
                        allParams.addAll((List<Map<String, Object>>) endpoint.getParameters().get("path"));
                    }

                    if (!allParams.isEmpty()) {
                        operation.put("parameters", allParams);
                    }
                }

                // Add detailed request body if available
                if (endpoint.getRequestBody() != null) {
                    operation.put("requestBody", endpoint.getRequestBody());
                }

                // Add detailed responses if available
                if (endpoint.getResponses() != null && !endpoint.getResponses().isEmpty()) {
                    operation.put("responses", endpoint.getResponses());
                } else {
                    // Default response
                    Map<String, Object> responses = new HashMap<>();
                    Map<String, Object> defaultResponse = new HashMap<>();
                    defaultResponse.put("description", "Successful operation");
                    responses.put("200", defaultResponse);
                    operation.put("responses", responses);
                }

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

        return NetworkServer.builder().serverId(rs.getString("server_id")).serverName(rs.getString("server_name")).serverType(NetworkServer.ServerType.valueOf(rs.getString("server_type").toUpperCase())).host(rs.getString("host")).port(rs.getObject("port", Integer.class)).onlinePlayers(rs.getInt("online_players")).maxPlayers(rs.getInt("max_players")).status(NetworkServer.Status.valueOf(rs.getString("status").toUpperCase())).lastHeartbeat(rs.getTimestamp("last_heartbeat")).createdAt(rs.getTimestamp("created_at")).metadata(metadata).build();
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

        return ServerAddon.builder().serverId(rs.getString("server_id")).addonName(rs.getString("addon_name")).addonVersion(rs.getString("addon_version")).enabled(rs.getBoolean("enabled")).endpoints(endpoints).openApiSpec(openApiSpec).registeredAt(rs.getTimestamp("registered_at")).updatedAt(rs.getTimestamp("updated_at")).build();
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