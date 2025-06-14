package dev.ua.uaproject.catwalk.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.bridge.annotations.BridgeEventHandler;
import dev.ua.uaproject.catwalk.bridge.annotations.BridgePathParam;
import dev.ua.uaproject.catwalk.utils.json.GsonSingleton;
import dev.ua.uaproject.catwalk.webserver.WebServer;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
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

    /**
     * Registers all methods in the handler instance that have the OpenApi annotation.
     *
     * @param handlerInstance The instance containing handler methods
     */
    public void registerHandler(Object handlerInstance, String plugin, @Nullable String docs) {
        Class<?> clazz = handlerInstance.getClass();
        WebServer webServer = CatWalkMain.instance.getWebServer();
        for (Method method : clazz.getDeclaredMethods()) {
            OpenApi openApiAnnotation = method.getAnnotation(OpenApi.class);
            if (openApiAnnotation == null) continue;

            BridgeEventHandler bridgeAnnotation = method.getAnnotation(BridgeEventHandler.class);
            boolean requiresAuth = bridgeAnnotation == null || bridgeAnnotation.requiresAuth();

            logger.info("Registering handler method: {}; Path: {}", method.getName(), openApiAnnotation.path());

            // If no HTTP methods are specified, default to GET
            HttpMethod[] methods = openApiAnnotation.methods().length > 0 ? openApiAnnotation.methods() : new HttpMethod[]{HttpMethod.GET};

            for (HttpMethod httpMethod : methods) {
                registerEndpoint(webServer, httpMethod, openApiAnnotation.path(), method, handlerInstance, requiresAuth);
            }
        }

        if (docs != null) {
            webServer.get("/plugins/" + plugin + "/openapi.json", ctx -> {
                ctx.contentType("application/json").result(docs);
            });
        }
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
                // Handle OpenApiParam annotation (for backward compatibility)
                if (parameter.isAnnotationPresent(io.javalin.openapi.OpenApiParam.class)) {
                    io.javalin.openapi.OpenApiParam paramAnnotation = parameter.getAnnotation(io.javalin.openapi.OpenApiParam.class);
                    String paramName = paramAnnotation.name();
                    String paramValue = context.pathParam(paramName);
                    pathParams.put(paramName, paramValue);
                }
                // Handle our custom PathParam annotation
                else if (parameter.isAnnotationPresent(BridgePathParam.class)) {
                    BridgePathParam paramAnnotation =
                        parameter.getAnnotation(BridgePathParam.class);
                    String paramName = paramAnnotation.value();
                    String paramValue = context.pathParam(paramName);
                    pathParams.put(paramName, paramValue);
                }
            }
    
            // Check if the method has path parameter annotations
            boolean hasPathParameters = method.getParameters().length > 0 && 
                                        Arrays.stream(method.getParameters())
                                              .anyMatch(p -> p.isAnnotationPresent(io.javalin.openapi.OpenApiParam.class) || 
                                                          p.isAnnotationPresent(BridgePathParam.class));
    
            // Parse request body if needed
            Object requestBody = null;
            if (paramType != null && !paramType.equals(Void.class)) {
                requestBody = deserializeRequestBody(context, paramType, pathParams);
            }
    
            // Invoke the method and handle the result
            Object result;
            
            if (hasPathParameters) {
                // Handle methods with path parameters
                Object[] args = prepareMethodArguments(method, context, requestBody, pathParams);
                result = method.invoke(handlerInstance, args);
            } else if (paramType != null && !paramType.equals(Void.class)) {
                // Standard method with request body
                result = method.invoke(handlerInstance, requestBody);
            } else {
                // Method with no parameters
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
     * Gets the type of the method parameter that will receive the request body, 
     * or null if it has no parameters or only has path parameters.
     */
    private Class<?> getMethodParamType(Method method) {
        if (method.getParameterCount() == 0) {
            return null;
        }
        
        // If there are multiple parameters, we need to check for path parameters
        if (method.getParameterCount() > 1) {
            // Check if all parameters except one are annotated with OpenApiParam or PathParam
            Parameter[] parameters = method.getParameters();
            Parameter nonPathParam = null;
            
            for (Parameter param : parameters) {
                if (!isPathParameter(param) && !param.getType().equals(Context.class)) {
                    if (nonPathParam != null) {
                        // Found more than one non-path parameter
                        throw new IllegalArgumentException("Method must have at most one non-path parameter: " + method.getName());
                    }
                    nonPathParam = param;
                }
            }
            
            // If we found exactly one non-path parameter, return its type
            if (nonPathParam != null) {
                return nonPathParam.getType();
            }
            
            // All parameters are path parameters
            return null;
        }
        
        // Only one parameter - check if it's a path parameter
        Parameter param = method.getParameters()[0];
        if (isPathParameter(param)) {
            return null; // It's a path parameter, no request body needed
        }
        
        return method.getParameterTypes()[0];
    }
    
    /**
     * Checks if a parameter is a path parameter (has either OpenApiParam or PathParam annotation)
     */
    private boolean isPathParameter(Parameter parameter) {
        return parameter.isAnnotationPresent(io.javalin.openapi.OpenApiParam.class) ||
               parameter.isAnnotationPresent(BridgePathParam.class);
    }

    /**
     * Deserializes the request body to the specified parameter type.
     * Also handles path parameters and populates them into the object if field names match.
     *
     * @param context The Javalin context containing the request
     * @param paramType The type of the parameter to deserialize to
     * @param pathParams Map of path parameters extracted from the URL
     * @return The deserialized object with fields populated from the request body and path parameters
     */
    private Object deserializeRequestBody(Context context, Class<?> paramType, Map<String, String> pathParams) throws Exception {
        // Choose deserialization method based on content type
        String contentType = context.contentType();
        Object instance;
        
        if (contentType != null && contentType.contains("application/json")) {
            // Create from JSON body
            instance = objectMapper.readValue(context.body(), paramType);
        } else if (contentType != null && contentType.contains("application/x-www-form-urlencoded")) {
            // For form data, create an instance and populate fields based on form params
            instance = paramType.getDeclaredConstructor().newInstance();
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
        } else {
            // Default to JSON if content type is not specified
            try {
                instance = objectMapper.readValue(context.body(), paramType);
            } catch (Exception e) {
                // If JSON parsing fails, try creating an empty instance
                instance = paramType.getDeclaredConstructor().newInstance();
            }
        }
        
        // Now populate any path parameters that match field names
        if (!pathParams.isEmpty()) {
            for (java.lang.reflect.Field field : paramType.getDeclaredFields()) {
                field.setAccessible(true);
                String fieldName = field.getName();
                String pathValue = pathParams.get(fieldName);
                
                if (pathValue != null) {
                    // Convert the string value to the field's type
                    Object convertedValue = convertStringToType(pathValue, field.getType());
                    field.set(instance, convertedValue);
                }
            }
        }
        
        return instance;
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
    
    /**
     * Prepares an array of arguments to be passed to the handler method.
     * Supports path parameters and request body.
     *
     * @param method The method to invoke
     * @param context The Javalin context
     * @param requestBody The deserialized request body (if any)
     * @param pathParams Map of path parameters extracted from the URL
     * @return Array of arguments to pass to the method
     */
    private Object[] prepareMethodArguments(Method method, Context context, Object requestBody, Map<String, String> pathParams) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            
            // Handle OpenApiParam path parameters (backward compatibility)
            if (parameter.isAnnotationPresent(io.javalin.openapi.OpenApiParam.class)) {
                io.javalin.openapi.OpenApiParam paramAnnotation = parameter.getAnnotation(io.javalin.openapi.OpenApiParam.class);
                String paramName = paramAnnotation.name();
                String paramValue = context.pathParam(paramName);
                
                // Convert the string value to the parameter's type
                args[i] = convertStringToType(paramValue, parameter.getType());
            } 
            // Handle our custom PathParam annotation
            else if (parameter.isAnnotationPresent(BridgePathParam.class)) {
                BridgePathParam paramAnnotation = parameter.getAnnotation(BridgePathParam.class);
                String paramName = paramAnnotation.value();
                String paramValue = context.pathParam(paramName);
                
                // Convert the string value to the parameter's type
                args[i] = convertStringToType(paramValue, parameter.getType());
            }
            // Handle request body parameter
            else if (requestBody != null && parameter.getType().isAssignableFrom(requestBody.getClass())) {
                args[i] = requestBody;
            }
            // Handle context parameter
            else if (parameter.getType().equals(Context.class)) {
                args[i] = context;
            }
            // Unrecognized parameter type
            else {
                args[i] = null;
            }
        }
        
        return args;
    }
}