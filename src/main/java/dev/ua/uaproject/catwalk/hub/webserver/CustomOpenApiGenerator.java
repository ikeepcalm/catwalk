package dev.ua.uaproject.catwalk.hub.webserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ua.uaproject.catwalk.CatWalkMain;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom OpenAPI specification generator that dynamically scans registered routes
 * and builds the OpenAPI spec on-demand. This works around Javalin 6.0's limitation
 * of not supporting dynamic plugin registration after startup.
 */
@Slf4j
public class CustomOpenApiGenerator {
    
    private final CatWalkMain plugin;
    private final Map<String, RouteInfo> registeredRoutes = new ConcurrentHashMap<>();
    
    public CustomOpenApiGenerator(CatWalkMain plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Register a route with OpenAPI information for documentation generation
     */
    public void registerRoute(HandlerType method, String path, Handler handler, OpenApi openApiAnnotation) {
        String key = method + ":" + path;
        RouteInfo routeInfo = new RouteInfo(method, path, handler, openApiAnnotation);
        registeredRoutes.put(key, routeInfo);
        
        log.debug("[CustomOpenApiGenerator] Registered route: {} {}", method, path);
    }
    
    /**
     * Register a route handler instance for scanning OpenApi annotations
     */
    public void registerHandlerInstance(Object handlerInstance, String pluginName) {
        Class<?> clazz = handlerInstance.getClass();
        
        for (Method method : clazz.getDeclaredMethods()) {
            OpenApi openApiAnnotation = method.getAnnotation(OpenApi.class);
            if (openApiAnnotation == null) continue;
            
            // Convert OpenApi annotation to route registrations
            HttpMethod[] httpMethods = openApiAnnotation.methods().length > 0 ? 
                                     openApiAnnotation.methods() : 
                                     new HttpMethod[]{HttpMethod.GET};
            
            for (HttpMethod httpMethod : httpMethods) {
                HandlerType handlerType = convertHttpMethodToHandlerType(httpMethod);
                String key = handlerType + ":" + openApiAnnotation.path();
                
                RouteInfo routeInfo = new RouteInfo(
                    handlerType, 
                    openApiAnnotation.path(), 
                    null, // Handler instance not needed for spec generation
                    openApiAnnotation
                );
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
     * Generate the complete OpenAPI specification as JSON string
     */
    public String generateOpenApiSpec() {
        JsonObject spec = new JsonObject();
        
        // Basic OpenAPI info
        spec.addProperty("openapi", "3.0.0");
        
        // Info section
        JsonObject info = new JsonObject();
        info.addProperty("title", "CatWalk API");
        info.addProperty("description", "CatWalk REST API for Minecraft server management");
        info.addProperty("version", plugin.getPluginMeta().getVersion());
        
        JsonObject contact = new JsonObject();
        contact.addProperty("name", "CatWalk Support");
        contact.addProperty("url", "https://github.com/your-repo/catwalk");
        info.add("contact", contact);
        
        spec.add("info", info);
        
        // Servers section
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", "http://localhost:" + plugin.getConfig().getInt("port", 4567));
        server.addProperty("description", "CatWalk API Server");
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
            if (route.getOpenApiAnnotation() != null) {
                for (String tag : route.getOpenApiAnnotation().tags()) {
                    tagNames.add(tag);
                }
            }
        }
        
        for (String tagName : tagNames) {
            JsonObject tag = new JsonObject();
            tag.addProperty("name", tagName);
            tag.addProperty("description", tagName + " related endpoints");
            tags.add(tag);
        }
        
        if (!tags.isEmpty()) {
            spec.add("tags", tags);
        }
        
        return spec.toString();
    }
    
    private JsonObject generateOperationObject(RouteInfo route) {
        JsonObject operation = new JsonObject();
        
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
                // Generate operation ID from method and path
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
                JsonObject content = new JsonObject();
                
                for (OpenApiContent contentItem : annotation.requestBody().content()) {
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
        } else {
            // Default operation info for routes without OpenApi annotation
            operation.addProperty("summary", "Endpoint: " + route.getPath());
            operation.addProperty("operationId", route.getMethod().toString().toLowerCase() + 
                                                route.getPath().replaceAll("[^a-zA-Z0-9]", ""));
            
            JsonObject responses = new JsonObject();
            JsonObject defaultResponse = new JsonObject();
            defaultResponse.addProperty("description", "Successful operation");
            responses.add("200", defaultResponse);
            operation.add("responses", responses);
        }
        
        return operation;
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
    
    /**
     * Information about a registered route
     */
    public static class RouteInfo {
        private final HandlerType method;
        private final String path;
        private final Handler handler;
        private final OpenApi openApiAnnotation;
        private String pluginName;
        private Object handlerInstance;
        private Method handlerMethod;
        
        public RouteInfo(HandlerType method, String path, Handler handler, OpenApi openApiAnnotation) {
            this.method = method;
            this.path = path;
            this.handler = handler;
            this.openApiAnnotation = openApiAnnotation;
        }
        
        // Getters and setters
        public HandlerType getMethod() { return method; }
        public String getPath() { return path; }
        public Handler getHandler() { return handler; }
        public OpenApi getOpenApiAnnotation() { return openApiAnnotation; }
        public String getPluginName() { return pluginName; }
        public void setPluginName(String pluginName) { this.pluginName = pluginName; }
        public Object getHandlerInstance() { return handlerInstance; }
        public void setHandlerInstance(Object handlerInstance) { this.handlerInstance = handlerInstance; }
        public Method getHandlerMethod() { return handlerMethod; }
        public void setHandlerMethod(Method handlerMethod) { this.handlerMethod = handlerMethod; }
    }
}