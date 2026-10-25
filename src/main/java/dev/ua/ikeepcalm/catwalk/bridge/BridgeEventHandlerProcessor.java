package dev.ua.ikeepcalm.catwalk.bridge;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgeEventHandler;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgePathParam;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgeQueryParam;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgeRequestBody;
import dev.ua.ikeepcalm.catwalk.bridge.source.BridgeApiResponse;
import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.OpenApiSchemas;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonSingleton;
import dev.ua.ikeepcalm.catwalk.hub.webserver.CustomOpenApiGenerator.RouteType;
import dev.ua.ikeepcalm.catwalk.hub.webserver.RouteAccess;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public class BridgeEventHandlerProcessor {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Plugin plugin;
    private final Supplier<WebServer> webServer;

    public BridgeEventHandlerProcessor(Plugin plugin, Supplier<WebServer> webServer) {
        this.plugin = plugin;
        this.webServer = webServer;
    }

    public void registerHandler(Object handlerInstance, String pluginName) {
        int registered = 0;
        for (Method method : handlerInstance.getClass().getDeclaredMethods()) {
            BridgeEventHandler options = method.getAnnotation(BridgeEventHandler.class);
            boolean mainThread = options != null && options.mainThread();
            for (OpenApi api : method.getAnnotationsByType(OpenApi.class)) {
                method.setAccessible(true);
                EndpointDefinition endpoint = OpenApiSchemas.endpoint(api, options);
                for (HttpMethod httpMethod : api.methods()) {
                    webServer.get().addRoute(WebServer.toHandlerType(httpMethod), api.path(),
                            ctx -> handleRequest(ctx, method, handlerInstance, mainThread), RouteAccess.of(endpoint));
                    webServer.get().describeRoute(httpMethod, api.path(), RouteType.ANNOTATED, pluginName, endpoint);
                    registered++;
                }
            }
        }
        CatWalkLogger.info("Registered %d endpoints for plugin '%s'", registered, pluginName);
    }

    private void handleRequest(Context ctx, Method method, Object handlerInstance, boolean mainThread) {
        String validationError = validate(ctx, method);
        if (validationError != null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", validationError));
            return;
        }

        Object[] args;
        try {
            args = arguments(method, ctx);
        } catch (RuntimeException e) {
            CatWalkLogger.debug("Rejected arguments for %s: %s", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid request parameters"));
            return;
        }

        if (!mainThread) {
            try {
                writeResult(ctx, method.invoke(handlerInstance, args));
            } catch (InvocationTargetException | IllegalAccessException e) {
                fail(ctx, e instanceof InvocationTargetException ite ? ite.getCause() : e);
            }
            return;
        }

        CompletableFuture<Object> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                result.complete(method.invoke(handlerInstance, args));
            } catch (InvocationTargetException e) {
                result.completeExceptionally(e.getCause());
            } catch (IllegalAccessException | RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        writeResult(ctx, result);
    }

    private void fail(Context ctx, Throwable error) {
        CatWalkLogger.error("Handler for %s failed", error, ctx.path());
        ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Internal server error"));
    }

    private String validate(Context ctx, Method method) {
        for (Parameter parameter : method.getParameters()) {
            BridgeQueryParam queryParam = parameter.getAnnotation(BridgeQueryParam.class);
            if (queryParam != null && queryParam.required() && isBlank(ctx.queryParam(queryParam.value()))) {
                return "Missing required query parameter: " + queryParam.value();
            }

            BridgeRequestBody requestBody = parameter.getAnnotation(BridgeRequestBody.class);
            if (requestBody != null && requestBody.required()) {
                if (isBlank(ctx.body())) {
                    return "Request body is required";
                }
                String contentType = ctx.contentType();
                if (!requestBody.contentType().isEmpty() && (contentType == null || !contentType.contains(requestBody.contentType()))) {
                    return "Invalid content type. Expected: " + requestBody.contentType();
                }
            }
        }
        return null;
    }

    private Object[] arguments(Method method, Context ctx) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            args[i] = argument(parameters[i], ctx);
        }
        return args;
    }

    private Object argument(Parameter parameter, Context ctx) {
        Class<?> type = parameter.getType();
        BridgePathParam pathParam = parameter.getAnnotation(BridgePathParam.class);
        if (pathParam != null) {
            return convert(ctx.pathParam(pathParam.value()), type);
        }
        BridgeQueryParam queryParam = parameter.getAnnotation(BridgeQueryParam.class);
        if (queryParam != null) {
            String value = ctx.queryParam(queryParam.value());
            return convert(value == null && !queryParam.defaultValue().isEmpty() ? queryParam.defaultValue() : value, type);
        }
        if (parameter.isAnnotationPresent(BridgeRequestBody.class)) {
            return deserializeBody(ctx, type);
        }
        if (type == Context.class) {
            return ctx;
        }
        if (type.isPrimitive() || type == String.class || OpenApiSchemas.openApiType(type).matches("integer|number|boolean")) {
            return null;
        }
        return deserializeBody(ctx, type);
    }

    private Object deserializeBody(Context ctx, Class<?> type) {
        String body = ctx.body();
        if (isBlank(body)) {
            return null;
        }
        if (type == String.class) {
            return body;
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (JacksonException e) {
            return GsonSingleton.getInstance().fromJson(body, type);
        }
    }

    private Object convert(String value, Class<?> type) {
        if (value == null || type == String.class) {
            return value;
        }
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == long.class || type == Long.class) return Long.parseLong(value);
        if (type == double.class || type == Double.class) return Double.parseDouble(value);
        if (type == float.class || type == Float.class) return Float.parseFloat(value);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        return GsonSingleton.getInstance().fromJson(value, type);
    }

    private void writeResult(Context ctx, Object result) {
        switch (result) {
            case null -> ctx.status(HttpStatus.NO_CONTENT);
            case CompletableFuture<?> future -> ctx.future(() -> unwrap(future).handle((value, error) -> {
                if (error != null) {
                    fail(ctx, error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
                } else if (value == null) {
                    ctx.status(HttpStatus.NO_CONTENT);
                } else {
                    writeResult(ctx, value);
                }
                return null;
            }));
            case String text -> ctx.result(text);
            case byte[] bytes -> ctx.result(bytes);
            case BridgeApiResponse<?> response -> ctx.status(response.getHttpStatus() != null ? response.getHttpStatus()
                    : response.success ? HttpStatus.OK : HttpStatus.BAD_REQUEST).json(response);
            default -> ctx.json(result);
        }
    }

    private static CompletableFuture<Object> unwrap(CompletableFuture<?> future) {
        return future.thenCompose(value -> value instanceof CompletableFuture<?> inner ? unwrap(inner) : CompletableFuture.completedFuture(value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
