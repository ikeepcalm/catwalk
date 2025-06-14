package dev.ua.uaproject.catwalk.webserver.services;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.websocket.WsConfig;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Enhanced API for the CatWalk Webserver.
 * Provides methods for registering endpoints and handling responses.
 */
public interface CatWalkWebserverService {

    /**
     * Get the Javalin Webserver Instance.
     * Use with caution, as this gives full access to the Webserver and might break CatWalks functionality.
     *
     * @return the Javalin Webserver Instance
     */
    Javalin getWebserver();

    /**
     * Adds a GET Request Handler for the specified Path to the Webserver.
     *
     * @param path The Path to handle
     * @param handler The Handler
     */
    void get(String path, Handler handler);

    /**
     * Adds a POST Request Handler for the specified Path to the Webserver.
     *
     * @param path The Path to handle
     * @param handler The Handler
     */
    void post(String path, Handler handler);

    /**
     * Adds a PUT Request Handler for the specified Path to the Webserver.
     *
     * @param path The Path to handle
     * @param handler The Handler
     */
    void put(String path, Handler handler);

    /**
     * Adds a DELETE Request Handler for the specified Path to the Webserver.
     *
     * @param path The Path to handle
     * @param handler The Handler
     */
    void delete(String path, Handler handler);

    /**
     * Adds a WebSocket Handler on the specified Path to the Webserver.
     *
     * @param path The Path to handle
     * @param socket The Websocket Configuration
     */
    void websocket(String path, Consumer<WsConfig> socket);

    /**
     * Registers all handler methods with OpenApi annotations in the given object.
     * This automatically maps methods to HTTP endpoints.
     *
     * @param handlerInstance The object containing handler methods
     */
    void registerHandlers(Object handlerInstance);

    /**
     * Registers a GET endpoint that returns the result of a function.
     * This method handles error responses and appropriate status codes.
     *
     * @param path The path to handle
     * @param responseFunction A function that processes the request and returns a response
     * @param <T> The type of the response
     */
    <T> void getWithResponse(String path, Function<Context, T> responseFunction);

    /**
     * Registers a POST endpoint that returns the result of a function.
     * This method handles error responses and appropriate status codes.
     *
     * @param path The path to handle
     * @param responseFunction A function that processes the request and returns a response
     * @param <T> The type of the response
     */
    <T> void postWithResponse(String path, Function<Context, T> responseFunction);

    /**
     * Registers a PUT endpoint that returns the result of a function.
     * This method handles error responses and appropriate status codes.
     *
     * @param path The path to handle
     * @param responseFunction A function that processes the request and returns a response
     * @param <T> The type of the response
     */
    <T> void putWithResponse(String path, Function<Context, T> responseFunction);

    /**
     * Registers a DELETE endpoint that returns the result of a function.
     * This method handles error responses and appropriate status codes.
     *
     * @param path The path to handle
     * @param responseFunction A function that processes the request and returns a response
     * @param <T> The type of the response
     */
    <T> void deleteWithResponse(String path, Function<Context, T> responseFunction);
}