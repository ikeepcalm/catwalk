package dev.ua.uaproject.catwalk.hub.webserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.bridge.annotations.ApiProperty;
import dev.ua.uaproject.catwalk.bridge.annotations.ApiSchema;
import dev.ua.uaproject.catwalk.common.database.model.EndpointDefinition;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced Custom OpenAPI specification generator that handles all types of routes:
 * - Static routes (manually registered)
 * - Annotated handler routes (with @OpenApi)
 * - Dynamic proxy routes (from backend servers)
 */
@Slf4j
public class CustomOpenApiGenerator {

    private final CatWalkMain plugin;
    private final Map<String, RouteInfo> registeredRoutes = new ConcurrentHashMap<>();

    public CustomOpenApiGenerator(CatWalkMain plugin) {
        this.plugin = plugin;
    }

    /**
     * Register a static route (called automatically from WebServer.addRoute)
     */
    public void registerStaticRoute(HandlerType method, String path, Handler handler) {
        String key = method + ":" + path;

        if (isInternalPath(path)) {
            return;
        }

        RouteInfo routeInfo = new RouteInfo(method, path, handler, null);
        routeInfo.setRouteType(RouteType.STATIC);
        routeInfo.setStaticSummary(generateStaticSummary(method, path));
        routeInfo.setStaticDescription(generateStaticDescription(method, path));
        routeInfo.setStaticTags(generateStaticTags(path));

        registeredRoutes.put(key, routeInfo);

    }

    /**
     * Register a route handler instance for scanning OpenApi annotations
     */
    public void registerHandlerInstance(Object handlerInstance, String pluginName) {
        Class<?> clazz = handlerInstance.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            OpenApi openApiAnnotation = method.getAnnotation(OpenApi.class);
            if (openApiAnnotation == null) continue;

            HttpMethod[] httpMethods = openApiAnnotation.methods().length > 0 ?
                    openApiAnnotation.methods() :
                    new HttpMethod[]{HttpMethod.GET};

            for (HttpMethod httpMethod : httpMethods) {
                HandlerType handlerType = convertHttpMethodToHandlerType(httpMethod);
                String key = handlerType + ":" + openApiAnnotation.path();

                RouteInfo routeInfo = new RouteInfo(
                        handlerType,
                        openApiAnnotation.path(),
                        null,
                        openApiAnnotation
                );
                routeInfo.setRouteType(RouteType.ANNOTATED);
                routeInfo.setPluginName(pluginName);
                routeInfo.setHandlerInstance(handlerInstance);
                routeInfo.setHandlerMethod(method);

                registeredRoutes.put(key, routeInfo);

            }
        }
    }

    /**
     * Register a proxy route with custom OpenAPI documentation
     */
    public void registerProxyRoute(HandlerType method, String path, String summary, String description, String[] tags, EndpointDefinition detailedEndpoint) {
        String key = method + ":" + path;

        RouteInfo routeInfo = new RouteInfo(method, path, null, null);
        routeInfo.setRouteType(RouteType.PROXY);
        routeInfo.setProxySummary(summary);
        routeInfo.setProxyDescription(description);
        routeInfo.setProxyTags(tags);
        routeInfo.setDetailedEndpoint(detailedEndpoint);

        registeredRoutes.put(key, routeInfo);

    }

    public void registerProxyRoute(HandlerType method, String path, String summary, String description, String[] tags) {
        registerProxyRoute(method, path, summary, description, tags, null);
    }

    /**
     * Generate the complete OpenAPI specification as JSON string
     */
    public String generateOpenApiSpec() {
        JsonObject spec = new JsonObject();

        // Basic OpenAPI info
        spec.addProperty("openapi", "3.0.0");

        // Info section
        JsonObject info = new JsonObject();
        info.addProperty("title", "CatWalk API");
        info.addProperty("description", "CatWalk REST API for Minecraft server management with dynamic addon support");
        info.addProperty("version", plugin.getPluginMeta().getVersion());

        JsonObject contact = new JsonObject();
        contact.addProperty("name", "CatWalk Support");
        contact.addProperty("url", "https://github.com/mc-uaproject/catwalk");
        info.add("contact", contact);

        spec.add("info", info);

        // Servers section
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        // server.addProperty("url", "http://localhost:" + plugin.getConfig().getInt("port", 4567));
        server.addProperty("url", "https://catwalk.uaproject.xyz");
        server.addProperty("description", "CatWalk API Server (" + (plugin.isHubMode() ? "Hub Gateway" : "Backend Server") + ")");
        servers.add(server);
        spec.add("servers", servers);

        // Security schemes
        JsonObject components = new JsonObject();
        JsonObject securitySchemes = new JsonObject();

        if (plugin.getConfig().getBoolean("useKeyAuth", true)) {
            JsonObject bearerAuth = new JsonObject();
            bearerAuth.addProperty("type", "http");
            bearerAuth.addProperty("scheme", "bearer");
            bearerAuth.addProperty("bearerFormat", "API Key");
            bearerAuth.addProperty("description", "API Key authentication using Bearer token");
            securitySchemes.add("bearerAuth", bearerAuth);
        }

        components.add("securitySchemes", securitySchemes);
        spec.add("components", components);

        // Global security requirement
        if (plugin.getConfig().getBoolean("useKeyAuth", true)) {
            JsonArray security = new JsonArray();
            JsonObject securityReq = new JsonObject();
            securityReq.add("bearerAuth", new JsonArray());
            security.add(securityReq);
            spec.add("security", security);
        }

        // Paths section
        JsonObject paths = new JsonObject();

        // Group routes by path
        Map<String, Map<String, RouteInfo>> pathGroups = new HashMap<>();
        for (RouteInfo route : registeredRoutes.values()) {
            pathGroups.computeIfAbsent(route.getPath(), k -> new HashMap<>())
                    .put(route.getMethod().toString().toLowerCase(), route);
        }

        // Generate path documentation
        for (Map.Entry<String, Map<String, RouteInfo>> pathGroup : pathGroups.entrySet()) {
            String path = pathGroup.getKey();
            Map<String, RouteInfo> methods = pathGroup.getValue();

            JsonObject pathObj = new JsonObject();

            for (Map.Entry<String, RouteInfo> methodEntry : methods.entrySet()) {
                String httpMethod = methodEntry.getKey();
                RouteInfo route = methodEntry.getValue();

                JsonObject operation = generateOperationObject(route);
                pathObj.add(httpMethod, operation);
            }

            paths.add(path, pathObj);
        }

        spec.add("paths", paths);

        // Tags section
        JsonArray tags = new JsonArray();
        Set<String> tagNames = new HashSet<>();

        for (RouteInfo route : registeredRoutes.values()) {
            switch (route.getRouteType()) {
                case PROXY:
                    if (route.getProxyTags() != null) {
                        tagNames.addAll(Arrays.asList(route.getProxyTags()));
                    }
                    break;
                case ANNOTATED:
                    if (route.getOpenApiAnnotation() != null) {
                        tagNames.addAll(Arrays.asList(route.getOpenApiAnnotation().tags()));
                    }
                    break;
                case STATIC:
                    if (route.getStaticTags() != null) {
                        tagNames.addAll(Arrays.asList(route.getStaticTags()));
                    }
                    break;
            }
        }

        for (String tagName : tagNames) {
            JsonObject tag = new JsonObject();
            tag.addProperty("name", tagName);

            // Add better descriptions for common tags
            String description = switch (tagName) {
                case "Stats" -> "Server statistics and performance metrics";
                case "Network" -> "Network management and server discovery";
                case "Proxy" -> "Proxied endpoints from backend servers";
                case "Core" -> "Core CatWalk API endpoints";
                case "Admin" -> "Administrative functions";
                default -> tagName + " related endpoints";
            };

            tag.addProperty("description", description);
            tags.add(tag);
        }

        if (!tags.isEmpty()) {
            spec.add("tags", tags);
        }

        // Add metadata about route types
        JsonObject catWalkInfo = new JsonObject();
        catWalkInfo.addProperty("totalRoutes", registeredRoutes.size());
        catWalkInfo.addProperty("staticRoutes", registeredRoutes.values().stream().mapToInt(r -> r.getRouteType() == RouteType.STATIC ? 1 : 0).sum());
        catWalkInfo.addProperty("annotatedRoutes", registeredRoutes.values().stream().mapToInt(r -> r.getRouteType() == RouteType.ANNOTATED ? 1 : 0).sum());
        catWalkInfo.addProperty("proxyRoutes", registeredRoutes.values().stream().mapToInt(r -> r.getRouteType() == RouteType.PROXY ? 1 : 0).sum());
        catWalkInfo.addProperty("generatedAt", System.currentTimeMillis());

        JsonObject extensions = new JsonObject();
        extensions.add("x-catwalk-info", catWalkInfo);
        spec.add("x-catwalk-extensions", extensions);

        return spec.toString();
    }

    private JsonObject generateOperationObject(RouteInfo route) {
        JsonObject operation = new JsonObject();

        switch (route.getRouteType()) {
            case PROXY:
                generateProxyOperation(operation, route);
                break;
            case ANNOTATED:
                generateAnnotatedOperation(operation, route);
                break;
            case STATIC:
                generateStaticOperation(operation, route);
                break;
        }

        return operation;
    }

    private void generateProxyOperation(JsonObject operation, RouteInfo route) {
        if (route.getProxySummary() != null) {
            operation.addProperty("summary", route.getProxySummary());
        }

        if (route.getProxyDescription() != null) {
            operation.addProperty("description", route.getProxyDescription());
        }

        if (route.getProxyTags() != null) {
            JsonArray tags = new JsonArray();
            for (String tag : route.getProxyTags()) {
                tags.add(tag);
            }
            operation.add("tags", tags);
        }

        String operationId = route.getMethod().toString().toLowerCase() +
                route.getPath().replaceAll("[^a-zA-Z0-9]", "");
        operation.addProperty("operationId", operationId);

        // Check if we have detailed endpoint information
        EndpointDefinition detailedEndpoint = route.getDetailedEndpoint();

        if (detailedEndpoint != null) {

            // Add detailed request body if available
            if (detailedEndpoint.getRequestBody() != null) {
                JsonObject requestBody = convertMapToJsonObject(detailedEndpoint.getRequestBody());
                operation.add("requestBody", requestBody);
            }

            // Add detailed responses if available
            if (detailedEndpoint.getResponses() != null && !detailedEndpoint.getResponses().isEmpty()) {
                JsonObject responses = convertMapToJsonObject(detailedEndpoint.getResponses());
                operation.add("responses", responses);
            } else {
                JsonObject responses = new JsonObject();
                addProxyResponses(responses);
                operation.add("responses", responses);
            }

            if (detailedEndpoint.getParameters() != null && !detailedEndpoint.getParameters().isEmpty()) {
                JsonArray parameters = convertParametersToJsonArray(detailedEndpoint.getParameters());
                if (!parameters.isEmpty()) {
                    operation.add("parameters", parameters);
                }
            }

        } else {
            addGenericProxyDocumentation(operation, route);
        }
    }

    private JsonObject convertMapToJsonObject(Map<String, Object> map) {
        return CatWalkMain.instance.getGson().fromJson(CatWalkMain.instance.getGson().toJson(map), JsonObject.class);
    }

    private JsonArray convertParametersToJsonArray(Map<String, Object> parameters) {
        JsonArray allParams = new JsonArray();

        if (parameters.containsKey("query")) {
            List<Map<String, Object>> queryParams = (List<Map<String, Object>>) parameters.get("query");
            for (Map<String, Object> param : queryParams) {
                allParams.add(convertMapToJsonObject(param));
            }
        }

        if (parameters.containsKey("path")) {
            List<Map<String, Object>> pathParams = (List<Map<String, Object>>) parameters.get("path");
            for (Map<String, Object> param : pathParams) {
                allParams.add(convertMapToJsonObject(param));
            }
        }

        return allParams;
    }

    private void addGenericProxyDocumentation(JsonObject operation, RouteInfo route) {
        // Add path parameters for proxy routes
        if (route.getPath().contains("{")) {
            JsonArray parameters = new JsonArray();

            // Extract path parameters
            String[] pathParts = route.getPath().split("/");
            for (String part : pathParts) {
                if (part.startsWith("{") && part.endsWith("}")) {
                    String paramName = part.substring(1, part.length() - 1);

                    JsonObject param = new JsonObject();
                    param.addProperty("name", paramName);
                    param.addProperty("in", "path");
                    param.addProperty("required", true);
                    param.addProperty("description", "Path parameter: " + paramName);

                    JsonObject schema = new JsonObject();
                    schema.addProperty("type", "string");
                    param.add("schema", schema);

                    parameters.add(param);
                }
            }

            if (!parameters.isEmpty()) {
                operation.add("parameters", parameters);
            }
        }

        // Default responses for proxy routes
        JsonObject responses = new JsonObject();
        addProxyResponses(responses);
        operation.add("responses", responses);
    }

    private void generateAnnotatedOperation(JsonObject operation, RouteInfo route) {
        OpenApi annotation = route.getOpenApiAnnotation();
        if (annotation != null) {
            // Basic operation info
            if (!annotation.summary().isEmpty()) {
                operation.addProperty("summary", annotation.summary());
            }

            if (!annotation.description().isEmpty()) {
                operation.addProperty("description", annotation.description());
            }

            // Tags
            if (annotation.tags().length > 0) {
                JsonArray tags = new JsonArray();
                for (String tag : annotation.tags()) {
                    tags.add(tag);
                }
                operation.add("tags", tags);
            }

            // Operation ID
            if (!annotation.operationId().isEmpty()) {
                operation.addProperty("operationId", annotation.operationId());
            } else {
                String operationId = route.getMethod().toString().toLowerCase() +
                        route.getPath().replaceAll("[^a-zA-Z0-9]", "");
                operation.addProperty("operationId", operationId);
            }

            // Parameters (query params, path params)
            JsonArray parameters = new JsonArray();

            // Add query parameters from annotation
            for (var queryParam : annotation.queryParams()) {
                JsonObject param = new JsonObject();
                param.addProperty("name", queryParam.name());
                param.addProperty("in", "query");
                param.addProperty("required", queryParam.required());
                if (!queryParam.description().isEmpty()) {
                    param.addProperty("description", queryParam.description());
                }

                JsonObject schema = new JsonObject();
                schema.addProperty("type", getOpenApiType(queryParam.type()));
                param.add("schema", schema);

                parameters.add(param);
            }

            // Add path parameters from annotation
            for (var pathParam : annotation.pathParams()) {
                JsonObject param = new JsonObject();
                param.addProperty("name", pathParam.name());
                param.addProperty("in", "path");
                param.addProperty("required", true);
                if (!pathParam.description().isEmpty()) {
                    param.addProperty("description", pathParam.description());
                }

                JsonObject schema = new JsonObject();
                schema.addProperty("type", getOpenApiType(pathParam.type()));
                param.add("schema", schema);

                parameters.add(param);
            }

            if (!parameters.isEmpty()) {
                operation.add("parameters", parameters);
            }

            // Request body with detailed schema generation
            if (annotation.requestBody() != null && annotation.requestBody().content().length > 0) {
                JsonObject requestBody = new JsonObject();

                if (!annotation.requestBody().description().isEmpty()) {
                    requestBody.addProperty("description", annotation.requestBody().description());
                }

                requestBody.addProperty("required", annotation.requestBody().required());

                JsonObject content = new JsonObject();

                for (OpenApiContent contentItem : annotation.requestBody().content()) {
                    JsonObject mediaType = new JsonObject();

                    // Get the actual class from the 'from' attribute
                    Class<?> typeClass = null;
                    String mimeType = "application/json"; // Default mime type

                    try {
                        // Try to get the class from the 'from' attribute first
                        if (contentItem.from() != Object.class) {
                            typeClass = contentItem.from();
                        } else if (!contentItem.type().equals("AUTODETECT - Will be replaced later")) {
                            // Fallback to type() if it's not the auto-detect string
                            typeClass = Class.forName(contentItem.type());
                        }

                        // Get mime type - use default if it's the auto-detect string
                        if (!contentItem.mimeType().equals("AUTODETECT - Will be replaced later")) {
                            mimeType = contentItem.mimeType();
                        }

                        if (typeClass != null) {
                            JsonObject schema = generateDetailedSchema(typeClass);
                            mediaType.add("schema", schema);
                        } else {
                            log.warn("Could not determine class for content item");
                            JsonObject schema = new JsonObject();
                            schema.addProperty("type", "object");
                            mediaType.add("schema", schema);
                        }

                    } catch (ClassNotFoundException e) {
                        log.warn("Could not find class for detailed schema generation: {}", contentItem.type());
                        JsonObject schema = new JsonObject();
                        schema.addProperty("type", "object");
                        mediaType.add("schema", schema);
                    }

                    // Only add example if it's not the null placeholder
                    if (!contentItem.example().isEmpty() &&
                            !contentItem.example().equals("-- This string represents a null value and shouldn't be used --")) {
                        try {
                            com.google.gson.JsonElement exampleJson = com.google.gson.JsonParser.parseString(contentItem.example());
                            mediaType.add("example", exampleJson);
                        } catch (Exception e) {
                            mediaType.addProperty("example", contentItem.example());
                        }
                    }

                    content.add(mimeType, mediaType);
                }

                requestBody.add("content", content);
                operation.add("requestBody", requestBody);
            }

            // Responses with detailed schema generation
            JsonObject responses = new JsonObject();

            if (annotation.responses().length > 0) {
                for (OpenApiResponse response : annotation.responses()) {
                    JsonObject responseObj = new JsonObject();
                    responseObj.addProperty("description", response.description());

                    if (response.content().length > 0) {
                        JsonObject content = new JsonObject();
                        for (OpenApiContent contentItem : response.content()) {
                            JsonObject mediaType = new JsonObject();

                            // Get the actual class from the 'from' attribute
                            Class<?> typeClass = null;
                            String mimeType = "application/json"; // Default mime type

                            try {
                                // Try to get the class from the 'from' attribute first
                                if (contentItem.from() != Object.class) {
                                    typeClass = contentItem.from();
                                } else if (!contentItem.type().equals("AUTODETECT - Will be replaced later")) {
                                    // Fallback to type() if it's not the auto-detect string
                                    typeClass = Class.forName(contentItem.type());
                                }

                                // Get mime type - use default if it's the auto-detect string
                                if (!contentItem.mimeType().equals("AUTODETECT - Will be replaced later")) {
                                    mimeType = contentItem.mimeType();
                                }

                                if (typeClass != null) {
                                    JsonObject schema = generateDetailedSchema(typeClass);
                                    mediaType.add("schema", schema);
                                } else {
                                    log.warn("Could not determine response class for content item");
                                    JsonObject schema = new JsonObject();
                                    schema.addProperty("type", "object");
                                    mediaType.add("schema", schema);
                                }

                            } catch (ClassNotFoundException e) {
                                log.warn("Could not find response class: {}", contentItem.type());
                                JsonObject schema = new JsonObject();
                                schema.addProperty("type", "object");
                                mediaType.add("schema", schema);
                            }

                            if (!contentItem.example().isEmpty() &&
                                    !contentItem.example().equals("-- This string represents a null value and shouldn't be used --")) {
                                try {
                                    com.google.gson.JsonElement exampleJson = com.google.gson.JsonParser.parseString(contentItem.example());
                                    mediaType.add("example", exampleJson);
                                } catch (Exception e) {
                                    mediaType.addProperty("example", contentItem.example());
                                }
                            }

                            content.add(mimeType, mediaType);
                        }
                        responseObj.add("content", content);
                    }

                    responses.add(response.status(), responseObj);
                }
            } else {
                JsonObject defaultResponse = new JsonObject();
                defaultResponse.addProperty("description", "Successful operation");

                JsonObject content = new JsonObject();
                JsonObject jsonContent = new JsonObject();
                JsonObject schema = new JsonObject();
                schema.addProperty("type", "object");
                jsonContent.add("schema", schema);
                content.add("application/json", jsonContent);
                defaultResponse.add("content", content);

                responses.add("200", defaultResponse);
            }

            operation.add("responses", responses);
        }
    }

    private void generateStaticOperation(JsonObject operation, RouteInfo route) {
        if (route.getStaticSummary() != null) {
            operation.addProperty("summary", route.getStaticSummary());
        }

        if (route.getStaticDescription() != null) {
            operation.addProperty("description", route.getStaticDescription());
        }

        if (route.getStaticTags() != null) {
            JsonArray tags = new JsonArray();
            for (String tag : route.getStaticTags()) {
                tags.add(tag);
            }
            operation.add("tags", tags);
        }

        String operationId = route.getMethod().toString().toLowerCase() +
                route.getPath().replaceAll("[^a-zA-Z0-9]", "");
        operation.addProperty("operationId", operationId);

        // Basic responses for static routes
        JsonObject responses = new JsonObject();
        JsonObject okResponse = new JsonObject();
        okResponse.addProperty("description", "Successful operation");

        JsonObject content = new JsonObject();
        JsonObject jsonContent = new JsonObject();
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        jsonContent.add("schema", schema);
        content.add("application/json", jsonContent);
        okResponse.add("content", content);

        responses.add("200", okResponse);
        operation.add("responses", responses);
    }

    /**
     * Enhanced schema generation method that handles @ApiSchema annotations
     */
    private JsonObject generateDetailedSchema(Class<?> clazz) {
        JsonObject schema = new JsonObject();


        // Check for @ApiSchema annotation first
        if (clazz.isAnnotationPresent(ApiSchema.class)) {
            ApiSchema apiSchema = clazz.getAnnotation(ApiSchema.class);

            schema.addProperty("type", "object");

            if (!apiSchema.description().isEmpty()) {
                schema.addProperty("description", apiSchema.description());
            }

            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();

            for (ApiProperty property : apiSchema.properties()) {
                JsonObject propSchema = new JsonObject();
                propSchema.addProperty("type", property.type());

                if (!property.description().isEmpty()) {
                    propSchema.addProperty("description", property.description());
                }

                if (!property.example().isEmpty()) {
                    propSchema.addProperty("example", property.example());
                }

                properties.add(property.name(), propSchema);

                if (property.required()) {
                    required.add(property.name());
                }
            }

            if (properties.size() > 0) {
                schema.add("properties", properties);
            }

            if (required.size() > 0) {
                schema.add("required", required);
            }

            return schema;
        }

        // Fallback to reflection-based generation for classes without @ApiSchema
        return generateSchemaFromReflection(clazz);
    }

    /**
     * Fallback schema generation using reflection
     */
    private JsonObject generateSchemaFromReflection(Class<?> clazz) {
        JsonObject schema = new JsonObject();


        // Handle primitives and basic types
        if (isPrimitiveOrWrapper(clazz) || clazz == String.class) {
            schema.addProperty("type", getOpenApiType(clazz));
            return schema;
        }

        if (clazz.isArray()) {
            schema.addProperty("type", "array");
            JsonObject items = generateDetailedSchema(clazz.getComponentType());
            schema.add("items", items);
            return schema;
        }

        if (Collection.class.isAssignableFrom(clazz)) {
            schema.addProperty("type", "array");
            JsonObject items = new JsonObject();
            items.addProperty("type", "object");
            schema.add("items", items);
            return schema;
        }

        // For complex objects, use reflection to introspect fields
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();

        try {
            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();

            for (java.lang.reflect.Field field : fields) {
                // Skip static, transient, and synthetic fields
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                        java.lang.reflect.Modifier.isTransient(field.getModifiers()) ||
                        field.isSynthetic()) {
                    continue;
                }

                // Skip fields marked with @JsonIgnore
                if (field.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                    continue;
                }

                String fieldName = getJsonPropertyName(field);
                JsonObject fieldSchema = new JsonObject();
                fieldSchema.addProperty("type", getOpenApiType(field.getType()));

                // Add description based on field name
                String description = generateFieldDescription(field.getName(), field.getType());
                if (description != null && !description.isEmpty()) {
                    fieldSchema.addProperty("description", description);
                }

                properties.add(fieldName, fieldSchema);

                // Mark primitive types as required by default
                if (field.getType().isPrimitive()) {
                    required.add(fieldName);
                }
            }


        } catch (Exception e) {
            log.warn("Error generating schema for class {}: {}", clazz.getName(), e.getMessage());
        }

        if (properties.size() > 0) {
            schema.add("properties", properties);
        }

        if (required.size() > 0) {
            schema.add("required", required);
        }

        return schema;
    }

    /**
     * Get the JSON property name for a field, checking for @JsonProperty annotation
     */
    private String getJsonPropertyName(java.lang.reflect.Field field) {
        com.fasterxml.jackson.annotation.JsonProperty jsonProperty =
                field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);

        if (jsonProperty != null && !jsonProperty.value().isEmpty()) {
            return jsonProperty.value();
        }

        return field.getName();
    }

    /**
     * Generate field descriptions based on common naming patterns
     */
    private String generateFieldDescription(String fieldName, Class<?> fieldType) {
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
     * Check if a type is primitive or a wrapper class
     */
    private boolean isPrimitiveOrWrapper(Class<?> type) {
        return type.isPrimitive() ||
                type.equals(Integer.class) ||
                type.equals(Long.class) ||
                type.equals(Double.class) ||
                type.equals(Float.class) ||
                type.equals(Boolean.class) ||
                type.equals(Character.class) ||
                type.equals(Byte.class) ||
                type.equals(Short.class) ||
                type.equals(String.class);
    }

    private void addProxyResponses(JsonObject responses) {
        // Success response
        JsonObject okResponse = new JsonObject();
        okResponse.addProperty("description", "Successful proxy response from backend server");

        JsonObject content = new JsonObject();
        JsonObject jsonContent = new JsonObject();
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        jsonContent.add("schema", schema);
        content.add("application/json", jsonContent);
        okResponse.add("content", content);
        responses.add("200", okResponse);

        // Error responses
        JsonObject unauthorizedResponse = new JsonObject();
        unauthorizedResponse.addProperty("description", "Authentication required");
        responses.add("401", unauthorizedResponse);

        JsonObject notFoundResponse = new JsonObject();
        notFoundResponse.addProperty("description", "Endpoint not found");
        responses.add("404", notFoundResponse);

        JsonObject serviceUnavailableResponse = new JsonObject();
        serviceUnavailableResponse.addProperty("description", "Target server unavailable");
        responses.add("503", serviceUnavailableResponse);

        JsonObject timeoutResponse = new JsonObject();
        timeoutResponse.addProperty("description", "Request to target server timed out");
        responses.add("504", timeoutResponse);
    }

    // Helper methods for static route documentation
    private String generateStaticSummary(HandlerType method, String path) {
        if (path.equals("/")) return "CatWalk API Root";
        if (path.equals("/health")) return "Health Check";
        if (path.startsWith("/openapi")) return "OpenAPI Specification";
        if (path.startsWith("/swagger")) return "Swagger UI";
        if (path.startsWith("/redoc")) return "ReDoc Documentation";

        return method.toString() + " " + path;
    }

    private String generateStaticDescription(HandlerType method, String path) {
        if (path.equals("/")) return "Root endpoint showing CatWalk API information and available documentation";
        if (path.equals("/health")) return "Simple health check endpoint for monitoring";
        if (path.startsWith("/openapi")) return "OpenAPI 3.0 specification in JSON format";
        if (path.startsWith("/swagger")) return "Interactive Swagger UI for API exploration";
        if (path.startsWith("/redoc")) return "Clean ReDoc documentation interface";

        return "Static endpoint: " + path;
    }

    private String[] generateStaticTags(String path) {
        if (path.equals("/") || path.equals("/health")) return new String[]{"Core"};
        if (path.startsWith("/openapi") || path.startsWith("/swagger") || path.startsWith("/redoc"))
            return new String[]{"Documentation"};
        if (path.startsWith("/v1/network")) return new String[]{"Network"};
        if (path.startsWith("/v1/stats")) return new String[]{"Stats"};

        return new String[]{"Core"};
    }

    private boolean isInternalPath(String path) {
        return path.startsWith("/_") ||
                path.equals("/openapi.json") ||
                path.equals("/openapi") ||
                path.equals("/swagger") ||
                path.equals("/redoc");
    }

    private String getOpenApiType(Class<?> javaType) {
        if (javaType == String.class) return "string";
        if (javaType == Integer.class || javaType == int.class) return "integer";
        if (javaType == Long.class || javaType == long.class) return "integer";
        if (javaType == Double.class || javaType == double.class ||
                javaType == Float.class || javaType == float.class) return "number";
        if (javaType == Boolean.class || javaType == boolean.class) return "boolean";
        if (javaType.isArray() || Collection.class.isAssignableFrom(javaType)) return "array";
        return "object";
    }

    private HandlerType convertHttpMethodToHandlerType(HttpMethod httpMethod) {
        return switch (httpMethod) {
            case GET -> HandlerType.GET;
            case POST -> HandlerType.POST;
            case PUT -> HandlerType.PUT;
            case DELETE -> HandlerType.DELETE;
            case PATCH -> HandlerType.PATCH;
            case HEAD -> HandlerType.HEAD;
            case OPTIONS -> HandlerType.OPTIONS;
            default -> throw new IllegalStateException("Unexpected value: " + httpMethod);
        };
    }

    /**
     * Clear all registered routes (useful for testing or reloading)
     */
    public void clearRoutes() {
        registeredRoutes.clear();
        log.info("[CustomOpenApiGenerator] Cleared all registered routes");
    }

    /**
     * Get count of registered routes
     */
    public int getRouteCount() {
        return registeredRoutes.size();
    }

    // Enums and inner classes
    public enum RouteType {
        STATIC,      // Manually registered routes
        ANNOTATED,   // Routes from handler instances with @OpenApi
        PROXY        // Dynamic proxy routes from backend servers
    }

    /**
     * Enhanced information about a registered route
     */
    @Getter
    @Setter
    public static class RouteInfo {
        // Getters and setters
        private final HandlerType method;
        private final String path;
        private final Handler handler;
        private final OpenApi openApiAnnotation;
        private EndpointDefinition detailedEndpoint;

        // Common fields
        private RouteType routeType;
        private String pluginName;
        private Object handlerInstance;
        private Method handlerMethod;

        // Proxy route fields
        private String proxySummary;
        private String proxyDescription;
        private String[] proxyTags;

        // Static route fields
        private String staticSummary;
        private String staticDescription;
        private String[] staticTags;

        public RouteInfo(HandlerType method, String path, Handler handler, OpenApi openApiAnnotation) {
            this.method = method;
            this.path = path;
            this.handler = handler;
            this.openApiAnnotation = openApiAnnotation;
        }
    }
}