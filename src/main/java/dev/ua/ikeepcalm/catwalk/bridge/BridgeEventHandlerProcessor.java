package dev.ua.ikeepcalm.catwalk.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgeEventHandler;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgePathParam;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgeQueryParam;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgeRequestBody;
import dev.ua.uaproject.catwalk.bridge.annotations.*;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonSingleton;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Enhanced processor for handling bridge events from third-party plugins.
 * Supports all HTTP methods, return values, error handling, authentication,
 * and the new BridgeQueryParam and BridgeRequestBody annotations.
 */
public class BridgeEventHandlerProcessor {

    private final ObjectMapper objectMapper;

    public BridgeEventHandlerProcessor() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Registers all methods in the handler instance that have the OpenApi annotation.
     */
    public void registerHandler(Object handlerInstance, String plugin) {
        Class<?> clazz = handlerInstance.getClass();
        WebServer webServer = CatWalkMain.instance.getWebServer();

        CatWalkLogger.debug("Registering handler for plugin: %s", plugin);

        int registeredEndpoints = 0;

        for (Method method : clazz.getDeclaredMethods()) {
            OpenApi openApiAnnotation = method.getAnnotation(OpenApi.class);
            if (openApiAnnotation == null) continue;

            BridgeEventHandler bridgeAnnotation = method.getAnnotation(BridgeEventHandler.class);
            boolean requiresAuth = bridgeAnnotation == null || bridgeAnnotation.requiresAuth();

            CatWalkLogger.debug("Registering endpoint: %s %s",
                    openApiAnnotation.path(),
                    Arrays.toString(openApiAnnotation.methods()));

            HttpMethod[] methods = openApiAnnotation.methods().length > 0 ?
                    openApiAnnotation.methods() :
                    new HttpMethod[]{HttpMethod.GET};

            for (HttpMethod httpMethod : methods) {
                try {
                    registerEndpoint(webServer, httpMethod, openApiAnnotation.path(), method, handlerInstance, requiresAuth);
                    registeredEndpoints++;
                } catch (Exception e) {
                    CatWalkLogger.error("Failed to register endpoint %s %s: %s",
                            e, httpMethod, openApiAnnotation.path(), e.getMessage());
                }
            }
        }

        CatWalkLogger.success("Registered %d endpoints for plugin '%s'",
                registeredEndpoints, plugin);
    }

    /**
     * Registers an endpoint with the web server based on the HTTP method.
     */
    private void registerEndpoint(WebServer webServer, HttpMethod httpMethod, String path,
                                  Method method, Object handlerInstance, boolean requiresAuth) {

        CatWalkLogger.debug("Registering %s %s (auth: %s)", httpMethod, path, requiresAuth);

        switch (httpMethod) {
            case GET ->
                    webServer.get(path, context -> handleRequest(context, method, handlerInstance, requiresAuth));
            case POST ->
                    webServer.post(path, context -> handleRequest(context, method, handlerInstance, requiresAuth));
            case PUT ->
                    webServer.put(path, context -> handleRequest(context, method, handlerInstance, requiresAuth));
            case DELETE ->
                    webServer.delete(path, context -> handleRequest(context, method, handlerInstance, requiresAuth));
            case PATCH ->
                    webServer.addRoute(io.javalin.http.HandlerType.PATCH, path, context -> handleRequest(context, method, handlerInstance, requiresAuth));
            default -> CatWalkLogger.warn("Unsupported HTTP method: %s", httpMethod);
        }
    }

    /**
     * Handles the actual request processing, invoking the method and handling the response.
     */
    private void handleRequest(Context context, Method method, Object handlerInstance, boolean requiresAuth) {
        try {
            CatWalkLogger.debug("Handling request: %s %s", context.method(), context.path());

            if (requiresAuth) {
                String authHeader = context.header("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    CatWalkLogger.debug("Unauthorized request to %s - missing Bearer header", context.path());
                    context.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Authentication required"));
                    return;
                }
                
                String token = authHeader.substring(7);
                String configuredKey = CatWalkMain.instance.getWebServer().getAuthKey();
                if (!token.equals(configuredKey)) {
                    CatWalkLogger.debug("Unauthorized request to %s - invalid Bearer token", context.path());
                    context.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Invalid authentication token"));
                    return;
                }
            }

            // Validate required parameters
            if (!validateRequiredParameters(context, method)) {
                return; // Response already set by validation
            }

            // Prepare method arguments
            Object[] args = prepareMethodArguments(method, context);

            // Invoke the method
            Object result = method.invoke(handlerInstance, args);

            // Handle the result
            handleMethodResult(context, result);

            CatWalkLogger.debug("Successfully handled request: %s %s", context.method(), context.path());
        } catch (Exception e) {
            CatWalkLogger.error("Failed to invoke handler method %s: %s", e, method.getName(), e.getMessage());

            if (!context.res().isCommitted()) {
                context.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of(
                        "error", "Internal server error",
                        "message", e.getMessage(),
                        "endpoint", context.path()
                ));
            }
        }
    }

    /**
     * Validates required parameters before method invocation.
     */
    private boolean validateRequiredParameters(Context context, Method method) {
        for (Parameter parameter : method.getParameters()) {
            // Check required query parameters
            if (parameter.isAnnotationPresent(BridgeQueryParam.class)) {
                BridgeQueryParam queryParam = parameter.getAnnotation(BridgeQueryParam.class);
                if (queryParam.required()) {
                    String value = context.queryParam(queryParam.value());
                    if (value == null || value.trim().isEmpty()) {
                        context.status(HttpStatus.BAD_REQUEST).json(Map.of(
                                "error", "Missing required query parameter: " + queryParam.value()
                        ));
                        return false;
                    }
                }
            }

            // Check required request body
            if (parameter.isAnnotationPresent(BridgeRequestBody.class)) {
                BridgeRequestBody requestBody = parameter.getAnnotation(BridgeRequestBody.class);
                if (requestBody.required()) {
                    String body = context.body();
                    if (body == null || body.trim().isEmpty()) {
                        context.status(HttpStatus.BAD_REQUEST).json(Map.of(
                                "error", "Request body is required"
                        ));
                        return false;
                    }

                    // Validate content type if specified
                    if (!requestBody.contentType().isEmpty()) {
                        String contentType = context.contentType();
                        if (contentType == null || !contentType.contains(requestBody.contentType())) {
                            context.status(HttpStatus.BAD_REQUEST).json(Map.of(
                                    "error", "Invalid content type. Expected: " + requestBody.contentType()
                            ));
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Prepares an array of arguments to be passed to the handler method.
     */
    private Object[] prepareMethodArguments(Method method, Context context) throws Exception {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];

            if (parameter.isAnnotationPresent(BridgePathParam.class)) {
                BridgePathParam pathParam = parameter.getAnnotation(BridgePathParam.class);
                String value = context.pathParam(pathParam.value());
                args[i] = convertStringToType(value, parameter.getType());
            }
            // Handle legacy OpenApiParam path parameters
            else if (parameter.isAnnotationPresent(io.javalin.openapi.OpenApiParam.class)) {
                io.javalin.openapi.OpenApiParam pathParam = parameter.getAnnotation(io.javalin.openapi.OpenApiParam.class);
                String value = context.pathParam(pathParam.name());
                args[i] = convertStringToType(value, parameter.getType());
            }
            // Handle query parameters
            else if (parameter.isAnnotationPresent(BridgeQueryParam.class)) {
                BridgeQueryParam queryParam = parameter.getAnnotation(BridgeQueryParam.class);
                String value = context.queryParam(queryParam.value());

                // Use default value if parameter is missing and default is specified
                if (value == null && !queryParam.defaultValue().isEmpty()) {
                    value = queryParam.defaultValue();
                }

                args[i] = convertStringToType(value, parameter.getType());
            }
            // Handle request body
            else if (parameter.isAnnotationPresent(BridgeRequestBody.class)) {
                args[i] = deserializeRequestBody(context, parameter.getType());
            }
            // Handle context parameter
            else if (parameter.getType().equals(Context.class)) {
                args[i] = context;
            }
            // Handle parameters without annotations (legacy support)
            else if (!isAnnotatedParameter(parameter)) {
                // For backward compatibility, treat non-annotated complex types as request body
                if (!isPrimitiveOrWrapper(parameter.getType()) && !parameter.getType().equals(String.class)) {
                    args[i] = deserializeRequestBody(context, parameter.getType());
                } else {
                    args[i] = null;
                }
            }
            else {
                args[i] = null;
            }
        }

        return args;
    }

    /**
     * Checks if a parameter has any bridge annotation.
     */
    private boolean isAnnotatedParameter(Parameter parameter) {
        return parameter.isAnnotationPresent(BridgePathParam.class) ||
                parameter.isAnnotationPresent(BridgeQueryParam.class) ||
                parameter.isAnnotationPresent(BridgeRequestBody.class) ||
                parameter.isAnnotationPresent(io.javalin.openapi.OpenApiParam.class);
    }

    /**
     * Checks if a type is a primitive or primitive wrapper.
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
                type.equals(Short.class);
    }

    /**
     * Deserializes the request body to the specified type.
     */
    private Object deserializeRequestBody(Context context, Class<?> type) throws Exception {
        String contentType = context.contentType();
        String body = context.body();

        if (body == null || body.trim().isEmpty()) {
            return null;
        }

        if (type.equals(String.class)) {
            return body;
        }

        if (contentType != null && contentType.contains("application/json")) {
            return objectMapper.readValue(body, type);
        } else {
            // Try JSON as fallback
            try {
                return objectMapper.readValue(body, type);
            } catch (Exception e) {
                // If JSON parsing fails, try Gson
                return GsonSingleton.getInstance().fromJson(body, type);
            }
        }
    }

    /**
     * Converts a string value to the specified type.
     */
    private Object convertStringToType(String value, Class<?> type) {
        if (value == null) {
            return null;
        }

        if (type.equals(String.class)) {
            return value;
        } else if (type.equals(Integer.class) || type.equals(int.class)) {
            return Integer.parseInt(value);
        } else if (type.equals(Long.class) || type.equals(long.class)) {
            return Long.parseLong(value);
        } else if (type.equals(Double.class) || type.equals(double.class)) {
            return Double.parseDouble(value);
        } else if (type.equals(Float.class) || type.equals(float.class)) {
            return Float.parseFloat(value);
        } else if (type.equals(Boolean.class) || type.equals(boolean.class)) {
            return Boolean.parseBoolean(value);
        } else {
            // For complex types, try to deserialize from JSON
            try {
                return GsonSingleton.getInstance().fromJson(value, type);
            } catch (Exception e) {
                return value; // Return as string if deserialization fails
            }
        }
    }

    /**
     * Handles the result of the method invocation.
     */
    private void handleMethodResult(Context context, Object result) {
        switch (result) {
            case null -> {
                if (!context.res().isCommitted()) {
                    context.status(HttpStatus.NO_CONTENT);
                }
                return;
            }

            case CompletableFuture<?> future -> {
                context.future(() -> future.thenAccept(value -> {
                    if (value == null) {
                        context.status(HttpStatus.NO_CONTENT);
                    } else {
                        handleBridgeApiResponse(context, value);
                    }
                }).exceptionally(e -> {
                    Throwable cause = e instanceof CompletionException ? e.getCause() : e;
                    CatWalkLogger.error("Async operation failed", cause);
                    if (!context.res().isCommitted()) {
                        context.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", cause.getMessage()));
                    }
                    return null;
                }));
                return;
            }

            case String s -> {
                if (!context.res().isCommitted()) {
                    context.result(s);
                }
            }
            case byte[] bytes -> {
                if (!context.res().isCommitted()) {
                    context.result(bytes);
                }
            }
            default -> {
                if (!context.res().isCommitted()) {
                    handleBridgeApiResponse(context, result);
                }
            }
        }
    }

    /**
     * Handles BridgeApiResponse objects properly by setting the correct HTTP status code.
     */
    private void handleBridgeApiResponse(Context context, Object result) {
        if (result.getClass().getSimpleName().equals("BridgeApiResponse")) {
            try {
                var successField = result.getClass().getField("success");
                boolean success = (Boolean) successField.get(result);

                if (!success) {
                    // Try to get the HTTP status from the response
                    try {
                        var statusField = result.getClass().getField("httpStatus");
                        HttpStatus status = (HttpStatus) statusField.get(result);
                        context.status(status);
                    } catch (Exception e) {
                        // Default to BAD_REQUEST if no status field found
                        context.status(HttpStatus.BAD_REQUEST);
                    }
                } else {
                    context.status(HttpStatus.OK);
                }

                context.json(result);
            } catch (Exception e) {
                CatWalkLogger.error("Failed to handle BridgeApiResponse status: %s", e, e.getMessage());
                context.json(result);
            }
        } else {
            context.json(result);
        }
    }
}
