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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Addons obtain this service once (via the Bukkit ServicesManager) and may keep calling
 * its registration methods for the lifetime of the server, so every registration is
 * recorded as a replay action and re-applied to the freshly created WebServer whenever
 * CatWalk reloads, instead of being silently dropped.
 */
public class CatWalkWebserverServiceImpl implements CatWalkWebserverService {

    private final BridgeEventHandlerProcessor bridgeProcessor;
    private final NetworkRegistry networkRegistry;
    private final CatWalkMain plugin;

    private final List<Runnable> replayActions = new CopyOnWriteArrayList<>();

    public CatWalkWebserverServiceImpl(CatWalkMain main) {
        this.plugin = main;
        this.networkRegistry = main.getNetworkRegistry();
        this.bridgeProcessor = new BridgeEventHandlerProcessor();
    }

    private WebServer webServer() {
        return plugin.getWebServer();
    }

    @Override
    public Javalin getWebserver() {
        WebServer server = webServer();
        return server != null ? server.getJavalin() : plugin.getLastJavalin();
    }

    @Override
    public void get(String path, Handler handler) {
        register(() -> webServer().get(path, handler));
    }

    @Override
    public void post(String path, Handler handler) {
        register(() -> webServer().post(path, handler));
    }

    @Override
    public void put(String path, Handler handler) {
        register(() -> webServer().put(path, handler));
    }

    @Override
    public void delete(String path, Handler handler) {
        register(() -> webServer().delete(path, handler));
    }

    @Override
    public void websocket(String path, Consumer<WsConfig> handler) {
        register(() -> webServer().ws(path, handler));
    }

    @Override
    public void registerHandlers(Object handlerInstance) {
        register(() -> applyRegisterHandlers(handlerInstance));
    }

    private void applyRegisterHandlers(Object handlerInstance) {
        String pluginName = extractPluginName(handlerInstance);

        if (plugin.isStandaloneMode()) {
            // In standalone mode, register handlers directly without database interaction
            bridgeProcessor.registerHandler(handlerInstance, pluginName);
            CatWalkLogger.success("Registered addon '%s' for standalone server '%s'", pluginName, plugin.getServerId());
        } else if (plugin.isHubMode()) {
            if (networkRegistry != null) {
                networkRegistry.registerAddonFromHandler(plugin.getServerId(), pluginName, handlerInstance);
            }
            CatWalkLogger.debug("Registered addon '%s' for hub server '%s' (proxy routes only)", pluginName, plugin.getServerId());
        } else {
            bridgeProcessor.registerHandler(handlerInstance, pluginName);
            if (networkRegistry != null) {
                networkRegistry.registerAddonFromHandler(plugin.getServerId(), pluginName, handlerInstance);
            }
            CatWalkLogger.success("Registered addon '%s' for backend server '%s'", pluginName, plugin.getServerId());
        }
    }

    /**
     * Runs a registration action immediately and remembers it so it can be replayed
     * against a new WebServer instance after {@link CatWalkMain#reload()}.
     */
    private void register(Runnable action) {
        // With no server (failed restart) the action is only queued for the next replay.
        if (webServer() != null) {
            action.run();
        }
        replayActions.add(action);
    }

    /**
     * Re-applies every addon registration made so far to the current WebServer.
     * Called by {@link CatWalkMain} right after it builds a fresh WebServer on reload,
     * since addons typically register once at their own onEnable() and never again.
     */
    public void replayRegistrations() {
        if (replayActions.isEmpty()) {
            return;
        }

        CatWalkLogger.info("Re-registering %d addon endpoint(s) after reload...", replayActions.size());
        for (Runnable action : replayActions) {
            try {
                action.run();
            } catch (Exception e) {
                CatWalkLogger.error("Failed to re-register an addon endpoint after reload: %s", e, e.getMessage());
            }
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
        register(() -> webServer().get(path, ctx -> handleResponse(ctx, responseFunction)));
    }

    @Override
    public <T> void postWithResponse(String path, Function<Context, T> responseFunction) {
        register(() -> webServer().post(path, ctx -> handleResponse(ctx, responseFunction)));
    }

    @Override
    public <T> void putWithResponse(String path, Function<Context, T> responseFunction) {
        register(() -> webServer().put(path, ctx -> handleResponse(ctx, responseFunction)));
    }

    @Override
    public <T> void deleteWithResponse(String path, Function<Context, T> responseFunction) {
        register(() -> webServer().delete(path, ctx -> handleResponse(ctx, responseFunction)));
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

    @Override
    public String getAuthKey() {
        WebServer server = webServer();
        return server != null ? server.getAuthKey() : plugin.getConfig().getString("key", "change_me");
    }

    private record ErrorResponse(String error, String message) {

    }
}