package dev.ua.uaproject.catwalk;

import com.google.gson.Gson;
import dev.ua.uaproject.catwalk.common.commands.CatWalkCommand;
import dev.ua.uaproject.catwalk.common.utils.LagDetector;
import dev.ua.uaproject.catwalk.hub.api.ApiV1Initializer;
import dev.ua.uaproject.catwalk.hub.api.stats.StatsListener;
import dev.ua.uaproject.catwalk.hub.api.stats.StatsManager;
import dev.ua.uaproject.catwalk.hub.network.AddonRegistry;
import dev.ua.uaproject.catwalk.hub.network.NetworkGateway;
import dev.ua.uaproject.catwalk.hub.network.ServerDiscovery;
import dev.ua.uaproject.catwalk.hub.webserver.WebServer;
import dev.ua.uaproject.catwalk.hub.webserver.WebServerRoutes;
import dev.ua.uaproject.catwalk.hub.webserver.services.CatWalkWebserverService;
import dev.ua.uaproject.catwalk.hub.webserver.services.CatWalkWebserverServiceImpl;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.papermc.paper.plugin.configuration.PluginMeta;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public class CatWalkMain extends JavaPlugin {

    private static java.util.logging.Logger log;

    @Getter
    private int maxConsoleBufferSize = 1000;
    public static CatWalkMain instance;
    private final LagDetector lagDetector;

    @Getter
    private StatsManager statsManager;
    private final Server server;
    private WebServer app;

    @Getter
    private Gson gson;

    @Getter
    private boolean isHubMode = false;

    @Getter
    private String serverId;

    @Getter
    private NetworkGateway networkGateway;
    @Getter
    private ServerDiscovery serverDiscovery;

    @Getter
    private AddonRegistry addonRegistry;

    public CatWalkMain() {
        super();
        instance = this;
        server = getServer();
        lagDetector = new LagDetector();
    }

    @Override
    public void onEnable() {
        try {
            log = getLogger();
            Class.forName("io.javalin.Javalin");
            log.info("[CatWalk] Custom loader successfully provided dependencies!");

            this.statsManager = new StatsManager(this);
            Bukkit.getScheduler().runTaskTimer(this, lagDetector, 100, 1);

            saveDefaultConfig();
            FileConfiguration bukkitConfig = getConfig();
            maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");

            new CatWalkCommand(this);
            server.getPluginManager().registerEvents(new StatsListener(statsManager), this);

            // Initialize addon registry to track all network addons
            this.addonRegistry = new AddonRegistry(this);

            // IMPORTANT: Register webserver service FIRST
            CatWalkWebserverService webserverService = new CatWalkWebserverServiceImpl(this);
            server.getServicesManager().register(CatWalkWebserverService.class, webserverService, this, ServicePriority.Normal);

            loadHubConfiguration(bukkitConfig);
            setupWebServer(bukkitConfig);

            // FIXED: Register StatsApi through bridge system so OpenAPI annotations are detected
            ApiV1Initializer api = new ApiV1Initializer(this, log, lagDetector, statsManager);
            webserverService.registerHandlers(api.getStatsApi());

            // Add basic routes
            WebServerRoutes.addBasicRoutes(this, log, app);

            if (isHubMode) {
                initializeHubComponents();
            } else {
                initializeServerComponents();
            }

            log.info("[CatWalk] Plugin enabled successfully!");

        } catch (ClassNotFoundException e) {
            log.severe("[CatWalk] Custom loader failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        } catch (Exception e) {
            log.severe("[CatWalk] Failed to initialize: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    // NEW - Load hub configuration
    private void loadHubConfiguration(FileConfiguration config) {
        this.isHubMode = config.getBoolean("hub.enabled", false);
        this.serverId = config.getString("hub.server-id", "unknown");

        log.info("Mode: " + (isHubMode ? "Hub Gateway" : "Regular Server"));
        log.info("Server ID: " + serverId);
    }

    // NEW - Initialize hub gateway components
    private void initializeHubComponents() {
        log.info("Initializing Hub Gateway components...");

        // Initialize server discovery for network topology
        this.serverDiscovery = new ServerDiscovery(this);

        // Initialize network gateway for proxying requests
        this.networkGateway = new NetworkGateway(this, app);

        // Register network routes with the web server
        WebServerRoutes.addHubRoutes(this, log, networkGateway, app);

        log.info("Hub Gateway initialized successfully");
    }

    // NEW - Initialize regular server components
    private void initializeServerComponents() {
        log.info("Initializing as regular server...");

        // Initialize addon registry for local addons only
        this.addonRegistry = new AddonRegistry(this);

        // If this server is part of a network, announce our addons to the hub
        if (getConfig().getBoolean("hub.announce-to-network", false)) {
            this.serverDiscovery = new ServerDiscovery(this);
            // Announce this server's capabilities to the network
            Bukkit.getScheduler().runTaskLater(this, () -> {
                serverDiscovery.announceServerToNetwork();
            }, 100L); // 5 second delay to ensure everything is loaded
        }

        log.info("Regular server initialized");
    }

    private void setupWebServer(FileConfiguration bukkitConfig) {
        app = new WebServer(this, bukkitConfig, log);
        app.start(bukkitConfig.getInt("port", 4567));
    }

    public void reload() {
        if (app != null) {
            app.stop();
        }
        log.info("CatWalk reloading...");
        reloadConfig();
        FileConfiguration bukkitConfig = getConfig();
        maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");

        // NEW - Reload hub configuration
        loadHubConfiguration(bukkitConfig);

        setupWebServer(bukkitConfig);

        // NEW - Reinitialize components based on mode
        if (isHubMode) {
            initializeHubComponents();
        } else {
            initializeServerComponents();
        }

        log.info("CatWalk reloaded successfully!");
    }

    @Override
    public void onDisable() {
        PluginMeta pluginMeta = getPluginMeta();

        log.info(String.format("[%s] Disabled Version %s", pluginMeta.getDescription(), pluginMeta.getVersion()));

        // NEW - Cleanup hub components
        if (networkGateway != null) {
            networkGateway.shutdown();
        }
        if (serverDiscovery != null) {
            serverDiscovery.shutdown();
        }

        if (app != null) {
            app.stop();
        }
    }

    public WebServer getWebServer() {
        return this.app;
    }

    public OpenApiPlugin getOpenApiPlugin() {
        return this.app.getOpenApiPlugin();
    }

}