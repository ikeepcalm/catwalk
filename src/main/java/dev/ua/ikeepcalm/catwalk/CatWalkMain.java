package dev.ua.ikeepcalm.catwalk;

import dev.ua.ikeepcalm.catwalk.common.commands.CatWalkCommand;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseConfig;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseManager;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.RequestLogger;
import dev.ua.ikeepcalm.catwalk.hub.network.NetworkGateway;
import dev.ua.ikeepcalm.catwalk.hub.network.NetworkRegistry;
import dev.ua.ikeepcalm.catwalk.hub.network.RequestProcessor;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import dev.ua.ikeepcalm.catwalk.hub.webserver.services.CatWalkWebserverService;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.Base64;

@Getter
public class CatWalkMain extends JavaPlugin {

    private static final String DEFAULT_KEY = "change_me_to_secure_random_string";

    @Getter
    private static CatWalkMain instance;

    private boolean hubMode;
    private boolean standaloneMode;
    private String serverId;
    private WebServer webServer;
    private DatabaseManager databaseManager;
    private NetworkRegistry networkRegistry;
    private NetworkGateway hubGateway;
    private RequestProcessor requestProcessor;
    private CatWalkWebserverService webserverService;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadSettings();

        try {
            if (!standaloneMode) {
                databaseManager = new DatabaseManager(DatabaseConfig.fromBukkitConfig(getConfig()));
                networkRegistry = new NetworkRegistry(databaseManager, serverId, hubMode);
            }
            webserverService = new CatWalkWebserverService(this);
            getServer().getServicesManager().register(CatWalkWebserverService.class, webserverService, this, ServicePriority.Normal);
            startWebServer();
        } catch (RuntimeException e) {
            CatWalkLogger.error("Failed to enable CatWalk, disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        new CatWalkCommand(this);
        CatWalkLogger.info("Running as %s '%s' on port %d", getModeName(), serverId, getConfig().getInt("port"));
    }

    @Override
    public void onDisable() {
        stopWebServer();
        if (networkRegistry != null) {
            networkRegistry.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        RequestLogger.shutdown();
    }

    public void reload() {
        boolean wasStandalone = standaloneMode;
        boolean wasHub = hubMode;
        String previousServerId = serverId;

        stopWebServer();
        reloadConfig();
        loadSettings();

        if (wasStandalone != standaloneMode || wasHub != hubMode || !previousServerId.equals(serverId)) {
            CatWalkLogger.warn("Server mode or server-id changed, restart the server to apply it");
            standaloneMode = wasStandalone;
            hubMode = wasHub;
            serverId = previousServerId;
        }

        startWebServer();
        webserverService.replayRegistrations();
    }

    public String getModeName() {
        if (standaloneMode) {
            return "Standalone";
        }
        return hubMode ? "Hub Gateway" : "Backend Server";
    }

    private void loadSettings() {
        FileConfiguration config = getConfig();
        CatWalkLogger.setDebug(config.getBoolean("debug"));
        RequestLogger.initialize(getDataFolder(), config.getBoolean("request-logging.enabled"));

        ensureApiKey(config);

        Object hubEnabled = config.get("hub.enabled");
        standaloneMode = "standalone".equalsIgnoreCase(String.valueOf(hubEnabled));
        hubMode = !standaloneMode && config.getBoolean("hub.enabled");
        serverId = config.getString("hub.server-id");
    }

    private void ensureApiKey(FileConfiguration config) {
        String key = config.getString("key");
        if (key != null && key.length() >= WebServer.MIN_KEY_LENGTH && !key.equals(DEFAULT_KEY)) {
            return;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        config.set("key", Base64.getUrlEncoder().withoutPadding().encodeToString(random));
        saveConfig();
        CatWalkLogger.warn("Generated a new API key in config.yml. Every server in a CatWalk network must use the same key.");
    }

    private void startWebServer() {
        webServer = new WebServer(this, getConfig());
        if (hubMode) {
            hubGateway = new NetworkGateway(databaseManager, networkRegistry, webServer, this);
            hubGateway.registerNetworkManagementRoutes();
        } else if (!standaloneMode) {
            requestProcessor = new RequestProcessor(databaseManager, serverId, this, getConfig().getInt("port"), webServer.getAuthKey());
        }
        webServer.start();
    }

    private void stopWebServer() {
        if (hubGateway != null) {
            hubGateway.shutdown();
            hubGateway = null;
        }
        if (requestProcessor != null) {
            requestProcessor.shutdown();
            requestProcessor = null;
        }
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
    }
}
