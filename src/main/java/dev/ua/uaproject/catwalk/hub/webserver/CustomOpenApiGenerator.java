package dev.ua.uaproject.catwalk.hub.webserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ua.uaproject.catwalk.CatWalkMain;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
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

        log.debug("[CustomOpenApiGenerator] Registered static route: {} {}", method, path);
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

                log.debug("[CustomOpenApiGenerator] Registered annotated route: {} {} from plugin '{}'",
                        handlerType, openApiAnnotation.path(), pluginName);
            }
        }
    }

    /**
     * Register a proxy route with custom OpenAPI documentation
     */
    public void registerProxyRoute(HandlerType method, String path, String summary, String description, String[] tags) {
        String key = method + ":" + path;

        RouteInfo routeInfo = new RouteInfo(method, path, null, null);
        routeInfo.setRouteType(RouteType.PROXY);
        routeInfo.setProxySummary(summary);
        routeInfo.setProxyDescription(description);
        routeInfo.setProxyTags(tags);

        registeredRoutes.put(key, routeInfo);

        log.debug("[CustomOpenApiGenerator] Registered proxy route: {} {} with custom docs", method, path);
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
        server.addProperty("url", "http://localhost:" + plugin.getConfig().getInt("port", 4567));
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
            generateOperationFromAnnotation(operation, annotation, route);
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

    private void generateOperationFromAnnotation(JsonObject operation, OpenApi annotation, RouteInfo route) {
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

        // Request body
        if (annotation.requestBody() != null && annotation.requestBody().content().length > 0) {
            JsonObject requestBody = new JsonObject();
            
            // Add description if provided
            if (!annotation.requestBody().description().isEmpty()) {
                requestBody.addProperty("description", annotation.requestBody().description());
            }
            
            // Add required flag
            requestBody.addProperty("required", annotation.requestBody().required());
            
            JsonObject content = new JsonObject();

            for (OpenApiContent contentItem : annotation.requestBody().content()) {
                JsonObject mediaType = new JsonObject();
                JsonObject schema = new JsonObject();

                // Handle schema type
                try {
                    Class<?> typeClass = Class.forName(contentItem.type());
                    if (!typeClass.equals(Object.class)) {
                        schema.addProperty("type", getOpenApiType(typeClass));
                    } else {
                        schema.addProperty("type", "object");
                    }
                } catch (ClassNotFoundException e) {
                    schema.addProperty("type", "object");
                }

                mediaType.add("schema", schema);
                
                // Add example if provided
                if (!contentItem.example().isEmpty()) {
                    try {
                        // Parse the example as JSON to ensure it's valid
                        com.google.gson.JsonElement exampleJson = com.google.gson.JsonParser.parseString(contentItem.example());
                        mediaType.add("example", exampleJson);
                    } catch (Exception e) {
                        // If parsing fails, add as string
                        mediaType.addProperty("example", contentItem.example());
                    }
                }
                
                content.add(contentItem.mimeType(), mediaType);
            }

            requestBody.add("content", content);
            operation.add("requestBody", requestBody);
        }

        // Responses
        JsonObject responses = new JsonObject();

        if (annotation.responses().length > 0) {
            for (OpenApiResponse response : annotation.responses()) {
                JsonObject responseObj = new JsonObject();
                responseObj.addProperty("description", response.description());

                if (response.content().length > 0) {
                    JsonObject content = new JsonObject();
                    for (OpenApiContent contentItem : response.content()) {
                        JsonObject mediaType = new JsonObject();
                        JsonObject schema = new JsonObject();

                        try {
                            Class<?> typeClass = Class.forName(contentItem.type());
                            if (!typeClass.equals(Object.class)) {
                                schema.addProperty("type", getOpenApiType(typeClass));
                            } else {
                                schema.addProperty("type", "object");
                            }
                        } catch (ClassNotFoundException e) {
                            schema.addProperty("type", "object");
                        }

                        mediaType.add("schema", schema);
                        content.add(contentItem.mimeType(), mediaType);
                    }
                    responseObj.add("content", content);
                }

                responses.add(response.status(), responseObj);
            }
        } else {
            // Default response
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
    public static class RouteInfo {
        private final HandlerType method;
        private final String path;
        private final Handler handler;
        private final OpenApi openApiAnnotation;

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

        // Getters and setters
        public HandlerType getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public Handler getHandler() {
            return handler;
        }

        public OpenApi getOpenApiAnnotation() {
            return openApiAnnotation;
        }

        public RouteType getRouteType() {
            return routeType;
        }

        public void setRouteType(RouteType routeType) {
            this.routeType = routeType;
        }

        public String getPluginName() {
            return pluginName;
        }

        public void setPluginName(String pluginName) {
            this.pluginName = pluginName;
        }

        public Object getHandlerInstance() {
            return handlerInstance;
        }

        public void setHandlerInstance(Object handlerInstance) {
            this.handlerInstance = handlerInstance;
        }

        public Method getHandlerMethod() {
            return handlerMethod;
        }

        public void setHandlerMethod(Method handlerMethod) {
            this.handlerMethod = handlerMethod;
        }

        public String getProxySummary() {
            return proxySummary;
        }

        public void setProxySummary(String proxySummary) {
            this.proxySummary = proxySummary;
        }

        public String getProxyDescription() {
            return proxyDescription;
        }

        public void setProxyDescription(String proxyDescription) {
            this.proxyDescription = proxyDescription;
        }

        public String[] getProxyTags() {
            return proxyTags;
        }

        public void setProxyTags(String[] proxyTags) {
            this.proxyTags = proxyTags;
        }

        public String getStaticSummary() {
            return staticSummary;
        }

        public void setStaticSummary(String staticSummary) {
            this.staticSummary = staticSummary;
        }

        public String getStaticDescription() {
            return staticDescription;
        }

        public void setStaticDescription(String staticDescription) {
            this.staticDescription = staticDescription;
        }

        public String[] getStaticTags() {
            return staticTags;
        }

        public void setStaticTags(String[] staticTags) {
            this.staticTags = staticTags;
        }
    }
}