package dev.ua.ikeepcalm.catwalk.hub.webserver.services;

import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.bridge.BridgeEventHandlerProcessor;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import io.javalin.websocket.WsConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

public class CatWalkWebserverService {

    private static final Set<String> GENERIC_PACKAGE_PARTS = Set.of("catwalk", "bridge", "api", "handlers");

    private final CatWalkMain plugin;
    private final BridgeEventHandlerProcessor bridgeProcessor;
    // Addons register once at their onEnable, so registrations are replayed onto the new WebServer after /catwalk reload
    private final List<Runnable> registrations = new CopyOnWriteArrayList<>();

    public CatWalkWebserverService(CatWalkMain plugin) {
        this.plugin = plugin;
        this.bridgeProcessor = new BridgeEventHandlerProcessor(plugin, plugin::getWebServer);
    }

    public Javalin getWebserver() {
        return webServer().getJavalin();
    }

    public String getAuthKey() {
        return webServer().getAuthKey();
    }

    public void get(String path, Handler handler) {
        register(() -> webServer().get(path, handler));
    }

    public void post(String path, Handler handler) {
        register(() -> webServer().post(path, handler));
    }

    public void put(String path, Handler handler) {
        register(() -> webServer().put(path, handler));
    }

    public void delete(String path, Handler handler) {
        register(() -> webServer().delete(path, handler));
    }

    public void websocket(String path, Consumer<WsConfig> socket) {
        register(() -> webServer().ws(path, socket));
    }

    public <T> void getWithResponse(String path, Function<Context, T> responseFunction) {
        get(path, ctx -> respond(ctx, responseFunction));
    }

    public <T> void postWithResponse(String path, Function<Context, T> responseFunction) {
        post(path, ctx -> respond(ctx, responseFunction));
    }

    public <T> void putWithResponse(String path, Function<Context, T> responseFunction) {
        put(path, ctx -> respond(ctx, responseFunction));
    }

    public <T> void deleteWithResponse(String path, Function<Context, T> responseFunction) {
        delete(path, ctx -> respond(ctx, responseFunction));
    }

    public void registerHandlers(Object handlerInstance) {
        register(() -> registerHandlersNow(handlerInstance));
    }

    public void replayRegistrations() {
        registrations.forEach(Runnable::run);
    }

    private void registerHandlersNow(Object handlerInstance) {
        String pluginName = pluginName(handlerInstance);
        if (!plugin.isHubMode()) {
            bridgeProcessor.registerHandler(handlerInstance, pluginName);
        }
        if (!plugin.isStandaloneMode()) {
            plugin.getNetworkRegistry().registerAddonFromHandler(plugin.getServerId(), pluginName, handlerInstance);
        }
    }

    private void register(Runnable registration) {
        registration.run();
        registrations.add(registration);
    }

    private WebServer webServer() {
        return plugin.getWebServer();
    }

    private <T> void respond(Context ctx, Function<Context, T> responseFunction) {
        T result;
        try {
            result = responseFunction.apply(ctx);
        } catch (RuntimeException e) {
            CatWalkLogger.error("Handler for %s failed", e, ctx.path());
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Internal server error"));
            return;
        }

        switch (result) {
            case null -> ctx.status(HttpStatus.NO_CONTENT);
            case String text -> ctx.result(text);
            case byte[] bytes -> ctx.result(bytes);
            default -> ctx.json(result);
        }
    }

    private String pluginName(Object handlerInstance) {
        String className = handlerInstance.getClass().getSimpleName();
        if (className.endsWith("Catwalk")) {
            return className.substring(0, className.length() - "Catwalk".length()).toLowerCase();
        }
        String[] packageParts = handlerInstance.getClass().getPackageName().split("\\.");
        for (int i = packageParts.length - 1; i >= 0; i--) {
            if (!GENERIC_PACKAGE_PARTS.contains(packageParts[i])) {
                return packageParts[i].toLowerCase();
            }
        }
        return className.toLowerCase();
    }
}
