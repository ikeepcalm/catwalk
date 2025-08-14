package dev.ua.ikeepcalm.catwalk;

import com.google.gson.Gson;
import dev.ua.ikeepcalm.catwalk.common.commands.CatWalkCommand;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseConfig;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseManager;
import dev.ua.ikeepcalm.catwalk.common.database.model.RequestProcessor;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.LagDetector;
import dev.ua.ikeepcalm.catwalk.hub.network.NetworkGateway;
import dev.ua.ikeepcalm.catwalk.hub.network.NetworkRegistry;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServerRoutes;
import dev.ua.ikeepcalm.catwalk.hub.webserver.services.CatWalkWebserverService;
import dev.ua.ikeepcalm.catwalk.hub.webserver.services.CatWalkWebserverServiceImpl;
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
    private Gson gson;

    private final Server server;
    private WebServer app;

    @Getter
    private boolean isHubMode = false;

    @Getter
    private boolean isStandaloneMode = false;

    @Getter
    private String serverId;

    // NEW - Database-based components
    @Getter
    private DatabaseManager databaseManager;

    @Getter
    private NetworkRegistry networkRegistry;

    @Getter
    private NetworkGateway hubGateway;

    @Getter
    private RequestProcessor requestProcessor;

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
            CatWalkLogger.initialize(this);
            gson = new Gson();
            Class.forName("io.javalin.Javalin");
            CatWalkLogger.success("Custom loader successfully provided dependencies!");

            Bukkit.getScheduler().runTaskTimer(this, lagDetector, 100, 1);

            saveDefaultConfig();
            FileConfiguration bukkitConfig = getConfig();
            maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");

            new CatWalkCommand(this);

            // Load configuration
            loadHubConfiguration(bukkitConfig);

            // Initialize database connection (skip in standalone mode)
            if (!isStandaloneMode) {
                initializeDatabase(bukkitConfig);
                
                // Initialize network registry
                this.networkRegistry = new NetworkRegistry(databaseManager, serverId, isHubMode);
            } else {
                CatWalkLogger.info("Skipping database initialization in standalone mode");
            }

            // IMPORTANT: Register webserver service FIRST
            CatWalkWebserverService webserverService = new CatWalkWebserverServiceImpl(this);
            server.getServicesManager().register(CatWalkWebserverService.class, webserverService, this, ServicePriority.Normal);

            setupWebServer(bukkitConfig);

            // Initialize based on server mode
            if (isStandaloneMode) {
                initializeStandaloneComponents();
            } else if (isHubMode) {
                initializeHubComponents();
            } else {
                initializeBackendComponents();
            }

            // Register core API routes
            registerCoreApiRoutes();

            CatWalkLogger.success("Plugin enabled successfully!");
            String mode = isStandaloneMode ? "Standalone" : (isHubMode ? "Hub Gateway" : "Backend Server");
            CatWalkLogger.info("Mode: " + mode + " | Server ID: " + serverId);
            CatWalkLogger.info("OpenAPI documentation available at /openapi.json");
            CatWalkLogger.info("Swagger UI available at /swagger");

        } catch (ClassNotFoundException e) {
            log.severe("Custom loader failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        } catch (Exception e) {
            log.severe("Failed to initialize: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void initializeDatabase(FileConfiguration config) {
        try {
            DatabaseConfig dbConfig = DatabaseConfig.fromBukkitConfig(config);
            this.databaseManager = new DatabaseManager(dbConfig);
            CatWalkLogger.success("Database connection established to %s:%d/%s",
                    dbConfig.getHost(), dbConfig.getPort(), dbConfig.getDatabase());

        } catch (Exception e) {
            log.severe("Failed to initialize database connection: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private void loadHubConfiguration(FileConfiguration config) {
        Object hubEnabled = config.get("hub.enabled", false);
        
        if (hubEnabled instanceof String && "standalone".equalsIgnoreCase((String) hubEnabled)) {
            this.isStandaloneMode = true;
            this.isHubMode = false;
        } else {
            this.isHubMode = config.getBoolean("hub.enabled", false);
            this.isStandaloneMode = false;
        }
        
        this.serverId = config.getString("hub.server-id", "unknown");

        String mode = isStandaloneMode ? "Standalone" : (isHubMode ? "Hub Gateway" : "Backend Server");
        CatWalkLogger.info("Mode: " + mode);
        CatWalkLogger.info("Server ID: " + serverId);
    }

    private void initializeHubComponents() {
        CatWalkLogger.info("Initializing Hub Gateway components...");
        this.hubGateway = new NetworkGateway(databaseManager, networkRegistry, app, this);

        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            hubGateway.registerNetworkRoutes();
            CatWalkLogger.success("Hub Gateway proxy routes registered");
        }, 100L); // 5 second delay to allow backend servers to register

        CatWalkLogger.success("Hub Gateway initialized successfully");
    }

    private void initializeBackendComponents() {
        CatWalkLogger.info("Initializing Backend Server components...");

        int localPort = getConfig().getInt("port", 4567);
        this.requestProcessor = new RequestProcessor(databaseManager, serverId, this, localPort);

        CatWalkLogger.success("Backend Server initialized successfully");
    }

    private void initializeStandaloneComponents() {
        CatWalkLogger.info("Initializing Standalone Server components...");
        
        // In standalone mode, we don't need database-based components
        // All addon endpoints are registered directly on the local webserver
        // No request processing or network gateway needed
        
        CatWalkLogger.success("Standalone Server initialized successfully");
        CatWalkLogger.info("All addon endpoints will be registered directly on this server");
    }

    private void registerCoreApiRoutes() {
        WebServerRoutes.addBasicRoutes(this, log, app);
        if (isHubMode && !isStandaloneMode) {
            hubGateway.registerNetworkManagementRoutes();
        }
    }

    private void setupWebServer(FileConfiguration bukkitConfig) {
        app = new WebServer(this, bukkitConfig, log);
        app.start(bukkitConfig.getInt("port", 4567));
    }

    public void reload() {
        if (app != null) {
            app.stop();
        }

        CatWalkLogger.info("CatWalk reloading...");
        reloadConfig();
        FileConfiguration bukkitConfig = getConfig();
        maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");

        // Reload hub configuration
        loadHubConfiguration(bukkitConfig);

        // Shutdown existing components
        if (hubGateway != null) {
            hubGateway.shutdown();
            hubGateway = null;
        }
        if (requestProcessor != null) {
            requestProcessor.shutdown();
            requestProcessor = null;
        }

        setupWebServer(bukkitConfig);

        // Reinitialize components based on mode
        if (isStandaloneMode) {
            initializeStandaloneComponents();
        } else if (isHubMode) {
            initializeHubComponents();
        } else {
            initializeBackendComponents();
        }

        // Re-register APIs
        registerCoreApiRoutes();

        CatWalkLogger.success("CatWalk reloaded successfully!");
    }

    @Override
    public void onDisable() {
        PluginMeta pluginMeta = getPluginMeta();

        log.info(String.format("[%s] Disabled Version %s", pluginMeta.getDescription(), pluginMeta.getVersion()));

        // Cleanup components
        if (hubGateway != null) {
            hubGateway.shutdown();
        }
        if (requestProcessor != null) {
            requestProcessor.shutdown();
        }
        if (networkRegistry != null) {
            networkRegistry.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        if (app != null) {
            app.stop();
        }
    }

    public WebServer getWebServer() {
        return this.app;
    }

    // Legacy getter for compatibility - returns NetworkRegistry instead
    @Deprecated
    public NetworkRegistry getAddonRegistry() {
        return networkRegistry;
    }
}