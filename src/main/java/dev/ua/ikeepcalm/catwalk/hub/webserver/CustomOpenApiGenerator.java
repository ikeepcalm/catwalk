package dev.ua.ikeepcalm.catwalk.hub.webserver;

import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import dev.ua.ikeepcalm.catwalk.common.utils.OpenApiSchemas;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonSingleton;
import io.javalin.http.HandlerType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class CustomOpenApiGenerator {

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^/}]+)}");
    private static final Map<String, Object> PROXY_ERRORS = Map.of(
            "401", Map.of("description", "Authentication required"),
            "503", Map.of("description", "Target server unavailable"),
            "504", Map.of("description", "Request to target server timed out")
    );

    private final CatWalkMain plugin;
    private final Map<String, RouteInfo> registeredRoutes = new ConcurrentHashMap<>();

    public void registerStaticRoute(HandlerType method, String path) {
        registeredRoutes.putIfAbsent(key(method, path), new RouteInfo(method, path, RouteType.STATIC));
    }

    public void describeRoute(HandlerType method, String path, RouteType routeType, String pluginName,
                              boolean requiresAuth, EndpointDefinition endpoint) {
        RouteInfo route = new RouteInfo(method, path, routeType);
        route.setPluginName(pluginName);
        route.setRequiresAuth(requiresAuth);
        route.setEndpoint(endpoint);
        registeredRoutes.put(key(method, path), route);
    }

    public String generateOpenApiSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of(
                "title", "CatWalk API",
                "description", "CatWalk REST API for Minecraft server management with dynamic addon support",
                "version", plugin.getPluginMeta().getVersion()
        ));
        spec.put("servers", List.of(Map.of(
                "url", plugin.getWebServer().getServerUrl(),
                "description", "CatWalk API Server (" + plugin.getModeName() + ")"
        )));

        if (plugin.getWebServer().isAuthEnabled()) {
            spec.put("components", Map.of("securitySchemes", Map.of("bearerAuth", Map.of(
                    "type", "http", "scheme", "bearer", "bearerFormat", "API Key"))));
            spec.put("security", List.of(Map.of("bearerAuth", List.of())));
        }

        Map<String, Map<String, Object>> paths = new TreeMap<>();
        TreeSet<String> tags = new TreeSet<>();
        for (RouteInfo route : registeredRoutes.values()) {
            Map<String, Object> operation = operation(route);
            paths.computeIfAbsent(route.getPath(), p -> new TreeMap<>())
                    .put(route.getMethod().name().toLowerCase(), operation);
            if (operation.get("tags") instanceof List<?> operationTags) {
                operationTags.forEach(tag -> tags.add(String.valueOf(tag)));
            }
        }
        spec.put("paths", paths);
        spec.put("tags", tags.stream().map(tag -> Map.of("name", tag)).toList());

        return GsonSingleton.getInstance().toJson(spec);
    }

    private Map<String, Object> operation(RouteInfo route) {
        Map<String, Object> operation = route.getEndpoint() != null
                ? OpenApiSchemas.operation(route.getEndpoint())
                : staticOperation(route.getPath());
        operation.putIfAbsent("operationId", route.getMethod().name().toLowerCase() + route.getPath().replaceAll("[^a-zA-Z0-9]", ""));

        if (route.getRouteType() == RouteType.PROXY) {
            @SuppressWarnings("unchecked")
            Map<String, Object> responses = new LinkedHashMap<>((Map<String, Object>) operation.get("responses"));
            PROXY_ERRORS.forEach(responses::putIfAbsent);
            operation.put("responses", responses);
        }
        return operation;
    }

    private Map<String, Object> staticOperation(String path) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("summary", path);
        operation.put("tags", List.of(path.startsWith("/v1/network") ? "Network" : "Core"));

        List<Map<String, Object>> parameters = new ArrayList<>();
        Matcher matcher = PATH_PARAM.matcher(path);
        while (matcher.find()) {
            parameters.add(Map.of("name", matcher.group(1), "in", "path", "required", true, "schema", Map.of("type", "string")));
        }
        if (!parameters.isEmpty()) {
            operation.put("parameters", parameters);
        }
        operation.put("responses", Map.of("200", Map.of("description", "Successful operation")));
        return operation;
    }

    public int getRouteCount() {
        return registeredRoutes.size();
    }

    public List<RouteInfo> getRegisteredRoutes() {
        return List.copyOf(registeredRoutes.values());
    }

    private static String key(HandlerType method, String path) {
        return method.name() + ":" + path;
    }

    public enum RouteType {
        STATIC,
        ANNOTATED,
        PROXY
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class RouteInfo {
        private final HandlerType method;
        private final String path;
        private final RouteType routeType;
        private String pluginName;
        private Boolean requiresAuth;
        private EndpointDefinition endpoint;
    }
}
