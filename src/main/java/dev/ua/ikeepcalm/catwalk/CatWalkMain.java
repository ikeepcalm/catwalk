package dev.ua.ikeepcalm.catwalk;

import com.google.gson.Gson;
import dev.ua.ikeepcalm.catwalk.common.audit.CatWalkAudit;
import dev.ua.ikeepcalm.catwalk.common.commands.CatWalkCommand;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseConfig;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseManager;
import dev.ua.ikeepcalm.catwalk.common.database.model.RequestProcessor;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.LagDetector;
import dev.ua.ikeepcalm.catwalk.common.utils.RequestLogger;
import dev.ua.ikeepcalm.catwalk.hub.network.NetworkGateway;
import dev.ua.ikeepcalm.catwalk.hub.network.NetworkRegistry;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import dev.ua.ikeepcalm.catwalk.hub.webserver.audit.PeerRowLimiter;
import dev.ua.ikeepcalm.catwalk.hub.webserver.audit.RequestSummary;
import dev.ua.ikeepcalm.catwalk.hub.webserver.audit.RouteAudit;
import dev.ua.ikeepcalm.catwalk.hub.webserver.services.CatWalkWebserverService;
import dev.ua.ikeepcalm.catwalk.hub.webserver.services.CatWalkWebserverServiceImpl;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import io.javalin.Javalin;
import io.papermc.paper.plugin.configuration.PluginMeta;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;

public class CatWalkMain extends JavaPlugin {

    public static CatWalkMain instance;
    private static java.util.logging.Logger log;
    private final LagDetector lagDetector;
    private final Server server;
    @Getter
    private int maxConsoleBufferSize = 1000;
    @Getter
    private Gson gson;
    private WebServer app;

    // Kept after a stop or failed restart so getWebserver() never returns null.
    @Getter
    private Javalin lastJavalin;

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

    private CatWalkWebserverServiceImpl webserverServiceImpl;

    /** Shared audit ledger producer; a no-op instance until onEnable creates the real one. */
    @Getter
    private CatWalkAudit audit = CatWalkAudit.disabled();

    /** Route ownership and api.route_registered rows; outlives reloads like the service does. */
    @Getter
    private RouteAudit routeAudit = new RouteAudit(audit);

    /** Hourly read-request counts; kept across reloads so a reload does not drop a window. */
    @Getter
    private RequestSummary requestSummary = new RequestSummary(audit);

    /** Per-peer cap on noisy api.request rows; kept across reloads like the summary. */
    @Getter
    private PeerRowLimiter requestLimiter = new PeerRowLimiter(audit, PeerRowLimiter.DEFAULT_LIMIT);

    /** The 30 s audit timer; cancelled before the final flush so it cannot race it. */
    private BukkitTask auditTicker;

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

            RequestLogger.initialize(getDataFolder(), bukkitConfig.getBoolean("request-logging.enabled", true));
            setupAudit(bukkitConfig);

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
            webserverServiceImpl = new CatWalkWebserverServiceImpl(this);
            server.getServicesManager().register(CatWalkWebserverService.class, webserverServiceImpl, this, ServicePriority.Normal);

            setupWebServer(bukkitConfig, "enable");
            webserverServiceImpl.replayRegistrations();

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

    /** Read once per enable; /catwalk reload recreates neither the client nor its windows. */
    private void setupAudit(FileConfiguration config) {
        audit = CatWalkAudit.create(this, config.getBoolean("audit.enabled", true));
        routeAudit = new RouteAudit(audit);
        requestSummary = new RequestSummary(audit);
        requestLimiter = new PeerRowLimiter(audit, config.getInt("audit.per-peer-row-limit", PeerRowLimiter.DEFAULT_LIMIT));
        if (audit.isEnabled()) {
            // Rolls and emits the hourly summary and the per-minute suppression rows off the main
            // thread; both tick() methods swallow their own failures.
            auditTicker = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                requestSummary.tick();
                requestLimiter.tick();
            }, 600L, 600L);
        }
    }

    private void initializeDatabase(FileConfiguration config) {
        try {
            DatabaseConfig dbConfig = DatabaseConfig.fromBukkitConfig(config);
            CatWalkLogger.info("Initializing database connection to %s:%d/%s",
                    dbConfig.getHost(), dbConfig.getPort(), dbConfig.getDatabase());

            this.databaseManager = new DatabaseManager(dbConfig);
            CatWalkLogger.success("Database connection established and schema initialized successfully");

        } catch (DatabaseManager.DatabaseException e) {
            CatWalkLogger.error("Database initialization failed with SQL error [%s]: %s",
                    e.getSqlState(), e.getMessage());
            if (e.isRecoverable()) {
                CatWalkLogger.warn("This appears to be a recoverable error. Check your database connection and try restarting.");
            } else {
                CatWalkLogger.error("This appears to be a critical database configuration error. Check your database settings.");
            }
            throw new RuntimeException("Database initialization failed", e);
        } catch (Exception e) {
            CatWalkLogger.error("Failed to initialize database connection: %s", e.getMessage());
            CatWalkLogger.error("Please check your database configuration in config.yml");
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
        if (isHubMode && !isStandaloneMode) {
            hubGateway.registerNetworkManagementRoutes();
        }
    }

    // Publish the server only once it is listening, so a failed start leaves app null.
    private void setupWebServer(FileConfiguration bukkitConfig, String trigger) {
        WebServer candidate = new WebServer(this, bukkitConfig, log);
        int port = bukkitConfig.getInt("port", 4567);
        try {
            candidate.start(port);
        } catch (RuntimeException e) {
            auditWebServer("catwalk.webserver_started", AuditOutcome.FAILED, candidate, port, trigger, e);
            throw e;
        }
        app = candidate;
        lastJavalin = candidate.getJavalin();
        auditWebServer("catwalk.webserver_started", AuditOutcome.COMMITTED, candidate, port, trigger, null);
    }

    private void stopWebServer(String trigger) {
        WebServer stopped = app;
        int port = getConfig().getInt("port", 4567);
        try {
            stopped.stop();
        } catch (RuntimeException e) {
            auditWebServer("catwalk.webserver_stopped", AuditOutcome.FAILED, stopped, port, trigger, e);
            throw e;
        }
        app = null;
        auditWebServer("catwalk.webserver_stopped", AuditOutcome.COMMITTED, stopped, port, trigger, null);
    }

    private void auditWebServer(String eventType, AuditOutcome outcome, WebServer server, int port,
                                String trigger, Throwable error) {
        audit.emit(() -> {
            Map<String, Object> metadata = new LinkedHashMap<>(server.auditDescription());
            metadata.put("port", port);
            metadata.put("mode", getModeName());
            metadata.put("server_id", audit.serverId());
            metadata.put("catwalk_server_id", serverId);
            metadata.put("trigger", trigger);
            if (error != null) {
                metadata.put("error_class", error.getClass().getName());
            }
            return new CatWalkAudit.AuditRow(eventType, outcome,
                    outcome == AuditOutcome.FAILED ? AuditRisk.HIGH : AuditRisk.NORMAL, AuditPrivacy.INTERNAL,
                    null, serverId + ":" + port, null, null, metadata);
        });
    }

    public String getModeName() {
        return isStandaloneMode ? "standalone" : (isHubMode ? "hub" : "backend");
    }

    public void reload() {
        if (app != null) {
            stopWebServer("reload");
        }

        CatWalkLogger.info("CatWalk reloading...");
        reloadConfig();
        FileConfiguration bukkitConfig = getConfig();
        maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");

        RequestLogger.initialize(getDataFolder(), bukkitConfig.getBoolean("request-logging.enabled", true));

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

        setupWebServer(bukkitConfig, "reload");

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

        // Reload tore down and rebuilt the WebServer, so addon endpoints registered via
        // CatWalkWebserverService need to be re-applied to the new instance.
        if (webserverServiceImpl != null) {
            webserverServiceImpl.replayRegistrations();
        }

        CatWalkLogger.success("CatWalk reloaded successfully!");
    }

    @Override
    public void onDisable() {
        try {
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
                stopWebServer("disable");
            }

            RequestLogger.shutdown();
        } finally {
            try {
                if (auditTicker != null) {
                    auditTicker.cancel();
                }
                requestSummary.flushAll();
                requestLimiter.flushAll();
            } finally {
                audit.close();
            }
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