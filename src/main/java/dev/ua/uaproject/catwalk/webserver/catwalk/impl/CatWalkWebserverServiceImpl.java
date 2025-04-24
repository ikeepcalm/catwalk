package dev.ua.uaproject.catwalk.webserver.catwalk.impl;

import dev.ua.uaproject.catwalk.webserver.WebServer;
import dev.ua.uaproject.catwalk.webserver.catwalk.CatWalkWebserverService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import io.javalin.websocket.WsConfig;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.bridge.BridgeEventHandlerProcessor;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Enhanced implementation of the CatWalkWebserverService interface.
 * Provides additional methods for handling responses and errors.
 */
public class CatWalkWebserverServiceImpl implements CatWalkWebserverService {

    private final WebServer webServer;
    private final BridgeEventHandlerProcessor bridgeProcessor;

    public CatWalkWebserverServiceImpl(CatWalkMain main) {
        this.webServer = main.getWebServer();
        this.bridgeProcessor = new BridgeEventHandlerProcessor(LoggerFactory.getLogger(CatWalkWebserverServiceImpl.class));
    }

    @Override
    public Javalin getWebserver() {
        return webServer.getJavalin();
    }

    @Override
    public void get(String path, Handler handler) {
        webServer.get(path, handler);
    }

    @Override
    public void post(String path, Handler handler) {
        webServer.post(path, handler);
    }

    @Override
    public void put(String path, Handler handler) {
        webServer.put(path, handler);
    }

    @Override
    public void delete(String path, Handler handler) {
        webServer.delete(path, handler);
    }

    @Override
    public void websocket(String path, Consumer<WsConfig> handler) {
        webServer.ws(path, handler);
    }

    /**
     * Registers all methods with OpenApi annotations in the given handler instance.
     * This automatically maps methods to HTTP endpoints.
     *
     * @param handlerInstance The object containing handler methods
     */
    @Override
    public void registerHandlers(Object handlerInstance) {
        bridgeProcessor.registerHandler(handlerInstance);
    }

    /**
     * Registers a GET endpoint that returns the result of a function.
     * Handles errors and sets appropriate status codes.
     *
     * @param path The path to handle
     * @param responseFunction A function that handles the request and returns a response
     * @param <T> The type of the response
     */
    @Override
    public <T> void getWithResponse(String path, Function<Context, T> responseFunction) {
        webServer.get(path, ctx -> handleResponse(ctx, responseFunction));
    }

    /**
     * Registers a POST endpoint that returns the result of a function.
     * Handles errors and sets appropriate status codes.
     *
     * @param path The path to handle
     * @param responseFunction A function that handles the request and returns a response
     * @param <T> The type of the response
     */
    @Override
    public <T> void postWithResponse(String path, Function<Context, T> responseFunction) {
        webServer.post(path, ctx -> handleResponse(ctx, responseFunction));
    }

    /**
     * Registers a PUT endpoint that returns the result of a function.
     * Handles errors and sets appropriate status codes.
     *
     * @param path The path to handle
     * @param responseFunction A function that handles the request and returns a response
     * @param <T> The type of the response
     */
    @Override
    public <T> void putWithResponse(String path, Function<Context, T> responseFunction) {
        webServer.put(path, ctx -> handleResponse(ctx, responseFunction));
    }

    /**
     * Registers a DELETE endpoint that returns the result of a function.
     * Handles errors and sets appropriate status codes.
     *
     * @param path The path to handle
     * @param responseFunction A function that handles the request and returns a response
     * @param <T> The type of the response
     */
    @Override
    public <T> void deleteWithResponse(String path, Function<Context, T> responseFunction) {
        webServer.delete(path, ctx -> handleResponse(ctx, responseFunction));
    }

    /**
     * Handles the response from a function, setting appropriate status codes and content.
     */
    private <T> void handleResponse(Context ctx, Function<Context, T> responseFunction) {
        try {
            T result = responseFunction.apply(ctx);

            // If result is null, return 204 No Content
            if (result == null) {
                if (!ctx.res().isCommitted()) {
                    ctx.status(HttpStatus.NO_CONTENT);
                }
                return;
            }

            // If status is already set (e.g., by the function), just set the result
            if (ctx.res().isCommitted() || ctx.status() != HttpStatus.OK) {
                if (result instanceof String) {
                    ctx.result((String) result);
                } else if (result instanceof byte[]) {
                    ctx.result((byte[]) result);
                } else {
                    ctx.json(result);
                }
                return;
            }

            // Otherwise, return 200 OK with the result
            if (result instanceof String) {
                ctx.status(HttpStatus.OK).result((String) result);
            } else if (result instanceof byte[]) {
                ctx.status(HttpStatus.OK).result((byte[]) result);
            } else {
                ctx.status(HttpStatus.OK).json(result);
            }
        } catch (Exception e) {
            // Log the error
            LoggerFactory.getLogger(CatWalkWebserverServiceImpl.class)
                    .error("Error processing request: {}", e.getMessage(), e);

            // Only set error response if response hasn't been committed yet
            if (!ctx.res().isCommitted()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(new ErrorResponse("Internal server error", e.getMessage()));
            }
        }
    }

    /**
     * Simple error response class for standardized error handling.
     */
    private static class ErrorResponse {
        private final String error;
        private final String message;

        public ErrorResponse(String error, String message) {
            this.error = error;
            this.message = message;
        }

        public String getError() {
            return error;
        }

        public String getMessage() {
            return message;
        }
    }
}