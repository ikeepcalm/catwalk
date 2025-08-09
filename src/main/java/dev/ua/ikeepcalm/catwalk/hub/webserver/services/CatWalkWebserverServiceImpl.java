package dev.ua.ikeepcalm.catwalk.hub.webserver.services;

import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.bridge.BridgeEventHandlerProcessor;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.hub.network.NetworkRegistry;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import io.javalin.websocket.WsConfig;

import java.util.function.Consumer;
import java.util.function.Function;

public class CatWalkWebserverServiceImpl implements CatWalkWebserverService {

    private final WebServer webServer;
    private final BridgeEventHandlerProcessor bridgeProcessor;
    private final NetworkRegistry networkRegistry;
    private final CatWalkMain plugin;

    public CatWalkWebserverServiceImpl(CatWalkMain main) {
        this.plugin = main;
        this.webServer = main.getWebServer();
        this.networkRegistry = main.getNetworkRegistry();
        this.bridgeProcessor = new BridgeEventHandlerProcessor();
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

    @Override
    public void registerHandlers(Object handlerInstance) {
        String pluginName = extractPluginName(handlerInstance);

        if (plugin.isHubMode()) {
            networkRegistry.registerAddonFromHandler(plugin.getServerId(), pluginName, handlerInstance);
            CatWalkLogger.debug("Registered addon '%s' for hub server '%s' (proxy routes only)", pluginName, plugin.getServerId());
        } else {
            bridgeProcessor.registerHandler(handlerInstance, pluginName);
            networkRegistry.registerAddonFromHandler(plugin.getServerId(), pluginName, handlerInstance);
            CatWalkLogger.success("Registered addon '%s' for backend server '%s'", pluginName, plugin.getServerId());
        }
    }

    private String extractPluginName(Object handlerInstance) {
        String className = handlerInstance.getClass().getSimpleName();

        if (className.endsWith("Catwalk")) {
            return className.substring(0, className.length() - 7).toLowerCase();
        }

        String packageName = handlerInstance.getClass().getPackage().getName();
        String[] parts = packageName.split("\\.");
        if (parts.length > 0) {
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i];
                if (!part.equals("catwalk") && !part.equals("bridge") && !part.equals("api") && !part.equals("handlers")) {
                    return part.toLowerCase();
                }
            }
        }

        return className.toLowerCase();
    }

    @Override
    public <T> void getWithResponse(String path, Function<Context, T> responseFunction) {
        webServer.get(path, ctx -> handleResponse(ctx, responseFunction));
    }

    @Override
    public <T> void postWithResponse(String path, Function<Context, T> responseFunction) {
        webServer.post(path, ctx -> handleResponse(ctx, responseFunction));
    }

    @Override
    public <T> void putWithResponse(String path, Function<Context, T> responseFunction) {
        webServer.put(path, ctx -> handleResponse(ctx, responseFunction));
    }

    @Override
    public <T> void deleteWithResponse(String path, Function<Context, T> responseFunction) {
        webServer.delete(path, ctx -> handleResponse(ctx, responseFunction));
    }

    private <T> void handleResponse(Context ctx, Function<Context, T> responseFunction) {
        try {
            T result = responseFunction.apply(ctx);

            if (result == null) {
                if (!ctx.res().isCommitted()) {
                    ctx.status(HttpStatus.NO_CONTENT);
                }
                return;
            }

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

            if (result instanceof String) {
                ctx.status(HttpStatus.OK).result((String) result);
            } else if (result instanceof byte[]) {
                ctx.status(HttpStatus.OK).result((byte[]) result);
            } else {
                ctx.status(HttpStatus.OK).json(result);
            }
        } catch (Exception e) {
            CatWalkLogger.error("Error processing request: %s", e, e.getMessage());

            if (!ctx.res().isCommitted()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new ErrorResponse("Internal server error", e.getMessage()));
            }
        }
    }

    private record ErrorResponse(String error, String message) {

    }
}