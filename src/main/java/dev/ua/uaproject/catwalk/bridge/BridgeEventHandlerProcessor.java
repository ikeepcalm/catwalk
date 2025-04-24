package dev.ua.uaproject.catwalk.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.bridge.annotations.BridgeEventHandler;
import dev.ua.uaproject.catwalk.utils.GsonSingleton;
import dev.ua.uaproject.catwalk.webserver.WebServer;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Enhanced processor for handling bridge events from third-party plugins.
 * Supports all HTTP methods, return values, error handling, and authentication.
 */
public class BridgeEventHandlerProcessor {

    private final Logger logger;
    private final ObjectMapper objectMapper;

    public BridgeEventHandlerProcessor(Logger logger) {
        this.logger = logger;
        this.objectMapper = new ObjectMapper();
    }

    public void registerDocumentations(String plugin) {
        JavalinConfig config = CatWalkMain.instance.getWebServer().getJavalin().unsafeConfig();

        config.registerPlugin(new OpenApiPlugin(configuration -> {
            configuration.withDocumentationPath("/" + plugin + "/openapi");
            configuration.withPrettyOutput(true);
            configuration.withDefinitionConfiguration((version, openApiDefinition) -> {
                openApiDefinition.withInfo(openApiInfo -> {
                    openApiInfo.description("Catwalk API Extension for " + plugin);
                    openApiInfo.version("1.0.0");
                    openApiInfo.title("Catwalk");
                    openApiInfo.contact("@ikeepcalm");
                });
            });
        }));

        // Reregister Swagger and Redoc plugin
        config.registerPlugin(new SwaggerPlugin(configuration -> {
            configuration.setUiPath("/" + plugin + "/swagger");
            configuration.setDocumentationPath("/" + plugin + "/openapi");
        }));

        config.registerPlugin(new ReDocPlugin(configuration -> {
            configuration.setUiPath("/" + plugin + "/redoc");
            configuration.setDocumentationPath("/" + plugin + "/openapi");
        }));
    }

    /**
     * Registers all methods in the handler instance that have the OpenApi annotation.
     *
     * @param handlerInstance The instance containing handler methods
     */
    public void registerHandler(Object handlerInstance) {
        Class<?> clazz = handlerInstance.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            OpenApi openApiAnnotation = method.getAnnotation(OpenApi.class);
            if (openApiAnnotation == null) continue;

            BridgeEventHandler bridgeAnnotation = method.getAnnotation(BridgeEventHandler.class);
            boolean requiresAuth = bridgeAnnotation == null || bridgeAnnotation.requiresAuth();

            WebServer webServer = CatWalkMain.instance.getWebServer();
            logger.info("Registering handler method: {}; Path: {}", method.getName(), openApiAnnotation.path());

            // If no HTTP methods are specified, default to GET
            HttpMethod[] methods = openApiAnnotation.methods().length > 0 ? openApiAnnotation.methods() : new HttpMethod[]{HttpMethod.GET};

            for (HttpMethod httpMethod : methods) {
                registerEndpoint(webServer, httpMethod, openApiAnnotation.path(), method, handlerInstance, requiresAuth);
            }
        }

        logger.info("Registered {} handler methods for {}", clazz.getDeclaredMethods().length, handlerInstance.getClass().getSimpleName().toLowerCase());
//        registerDocumentations(handlerInstance.getClass().getSimpleName().toLowerCase());
    }

    /**
     * Registers an endpoint with the web server based on the HTTP method.
     */
    private void registerEndpoint(WebServer webServer, HttpMethod httpMethod, String path, Method method, Object handlerInstance, boolean requiresAuth) {
        switch (httpMethod) {
            case GET ->
                    webServer.get(path, context -> handleRequest(context, method, handlerInstance, null, requiresAuth));
            case POST ->
                    webServer.post(path, context -> handleRequest(context, method, handlerInstance, getMethodParamType(method), requiresAuth));
            case PUT ->
                    webServer.put(path, context -> handleRequest(context, method, handlerInstance, getMethodParamType(method), requiresAuth));
            case DELETE ->
                    webServer.delete(path, context -> handleRequest(context, method, handlerInstance, getMethodParamType(method), requiresAuth));
            case PATCH ->
                    webServer.addRoute(io.javalin.http.HandlerType.PATCH, path, context -> handleRequest(context, method, handlerInstance, getMethodParamType(method), requiresAuth));
            // Add other HTTP methods as needed
            default -> logger.warn("Unsupported HTTP method: {}", httpMethod);
        }
    }

    /**
     * Handles the actual request processing, invoking the method and handling the response.
     */
    private void handleRequest(Context context, Method method, Object handlerInstance, Class<?> paramType, boolean requiresAuth) {
        try {
            // Check authentication if required
            if (requiresAuth) {
                String authHeader = context.header("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    context.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Authentication required"));
                    return;
                }
            }

            // Extract path parameters if any
            Map<String, String> pathParams = new HashMap<>();
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(io.javalin.openapi.OpenApiParam.class)) {
                    io.javalin.openapi.OpenApiParam paramAnnotation = parameter.getAnnotation(io.javalin.openapi.OpenApiParam.class);
                    String paramName = paramAnnotation.name();
                    String paramValue = context.pathParam(paramName);
                    pathParams.put(paramName, paramValue);
                }
            }

            // Parse request body if needed
            Object requestBody = null;
            if (paramType != null && !paramType.equals(Void.class)) {
                requestBody = deserializeRequestBody(context, paramType);
            }

            // Invoke the method and handle the result
            Object result;
            if (paramType != null && !paramType.equals(Void.class)) {
                result = method.invoke(handlerInstance, requestBody);
            } else {
                result = method.invoke(handlerInstance);
            }

            // Handle the result based on its type
            handleMethodResult(context, result);

        } catch (Exception e) {
            logger.error("Failed to invoke handler method: {}", method.getName(), e);
            context.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Internal server error", "message", e.getMessage(), "endpoint", context.path()));
        }
    }

    /**
     * Handles the result of the method invocation, setting the appropriate response.
     */
    private void handleMethodResult(Context context, Object result) {
        switch (result) {
            case null -> {
                context.status(HttpStatus.NO_CONTENT);
                return;
            }

            case CompletableFuture<?> future -> {
                context.future(() -> future.thenAccept(value -> {
                    if (value == null) {
                        context.status(HttpStatus.NO_CONTENT);
                    } else {
                        context.json(value);
                    }
                }).exceptionally(e -> {
                    Throwable cause = e instanceof CompletionException ? e.getCause() : e;
                    logger.error("Async operation failed", cause);
                    context.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", cause.getMessage()));
                    return null;
                }));
                return;
            }

            case String s -> context.result(s);
            case byte[] bytes -> context.result(bytes);
            default -> context.json(result);
        }

    }

    /**
     * Gets the type of the method parameter, or null if it has no parameters.
     */
    private Class<?> getMethodParamType(Method method) {
        if (method.getParameterCount() == 0) {
            return null;
        }
        if (method.getParameterCount() > 1) {
            throw new IllegalArgumentException("Method must have at most one parameter: " + method.getName());
        }
        return method.getParameterTypes()[0];
    }

    /**
     * Deserializes the request body to the specified parameter type.
     */
    private Object deserializeRequestBody(Context context, Class<?> paramType) throws Exception {
        // Choose deserialization method based on content type
        String contentType = context.contentType();
        if (contentType != null && contentType.contains("application/json")) {
            return objectMapper.readValue(context.body(), paramType);
        } else if (contentType != null && contentType.contains("application/x-www-form-urlencoded")) {
            // For form data, create an instance and populate fields based on form params
            Object instance = paramType.getDeclaredConstructor().newInstance();
            for (java.lang.reflect.Field field : paramType.getDeclaredFields()) {
                field.setAccessible(true);
                String fieldName = field.getName();
                String formValue = context.formParam(fieldName);
                if (formValue != null) {
                    // Convert the string value to the field's type
                    Object convertedValue = convertStringToType(formValue, field.getType());
                    field.set(instance, convertedValue);
                }
            }
            return instance;
        } else {
            // Default to JSON
            return objectMapper.readValue(context.body(), paramType);
        }
    }

    /**
     * Converts a string value to the specified type.
     */
    private Object convertStringToType(String value, Class<?> type) {
        if (type.equals(String.class)) {
            return value;
        } else if (type.equals(Integer.class) || type.equals(int.class)) {
            return Integer.parseInt(value);
        } else if (type.equals(Long.class) || type.equals(long.class)) {
            return Long.parseLong(value);
        } else if (type.equals(Double.class) || type.equals(double.class)) {
            return Double.parseDouble(value);
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
}