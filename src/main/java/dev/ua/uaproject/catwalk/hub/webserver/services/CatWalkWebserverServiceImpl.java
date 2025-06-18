package dev.ua.uaproject.catwalk.hub.webserver.services;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.bridge.BridgeEventHandlerProcessor;
import dev.ua.uaproject.catwalk.hub.network.RequestHandler;
import dev.ua.uaproject.catwalk.hub.webserver.WebServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import io.javalin.websocket.WsConfig;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Function;

public class CatWalkWebserverServiceImpl implements CatWalkWebserverService {

    private final WebServer webServer;
    private final BridgeEventHandlerProcessor bridgeProcessor;
    private final RequestHandler requestHandler;  // NEW

    public CatWalkWebserverServiceImpl(CatWalkMain main) {
        this.webServer = main.getWebServer();
        this.bridgeProcessor = new BridgeEventHandlerProcessor(LoggerFactory.getLogger(CatWalkWebserverServiceImpl.class));

        // NEW - Initialize request handler for non-hub servers
        this.requestHandler = main.isHubMode() ? null : new RequestHandler(main);
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
        // Get the plugin name from the handler's package or class
        String pluginName = extractPluginName(handlerInstance);

        // Register with bridge processor
        bridgeProcessor.registerHandler(handlerInstance, pluginName, null);

        // NEW - Register with addon registry for network discovery
        CatWalkMain.instance.getAddonRegistry().registerLocalAddon(pluginName, handlerInstance);
    }

    // NEW - Extract plugin name from handler instance
    private String extractPluginName(Object handlerInstance) {
        String className = handlerInstance.getClass().getSimpleName();

        // Try to extract plugin name from class name
        // e.g., "GraylistCatwalk" -> "graylist"
        if (className.endsWith("Catwalk")) {
            return className.substring(0, className.length() - 7).toLowerCase();
        }

        // Try to extract from package name
        String packageName = handlerInstance.getClass().getPackage().getName();
        String[] parts = packageName.split("\\.");
        if (parts.length > 0) {
            return parts[parts.length - 1].toLowerCase();
        }

        // Fallback to class name
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
            LoggerFactory.getLogger(CatWalkWebserverServiceImpl.class)
                    .error("Error processing request: {}", e.getMessage(), e);

            if (!ctx.res().isCommitted()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(new ErrorResponse("Internal server error", e.getMessage()));
            }
        }
    }

    private record ErrorResponse(String error, String message) {

    }
}