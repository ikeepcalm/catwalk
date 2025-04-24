package dev.ua.uaproject.catwalk;

import dev.ua.uaproject.catwalk.api.v1.models.ConsoleLine;
import dev.ua.uaproject.catwalk.api.v1.stats.StatsListener;
import dev.ua.uaproject.catwalk.api.v1.stats.StatsManager;
import dev.ua.uaproject.catwalk.commands.CatWalkCommand;
import dev.ua.uaproject.catwalk.metrics.Metrics;
import dev.ua.uaproject.catwalk.utils.ConsoleListener;
import dev.ua.uaproject.catwalk.utils.LagDetector;
import dev.ua.uaproject.catwalk.webhooks.WebhookEventListener;
import dev.ua.uaproject.catwalk.webserver.WebServer;
import dev.ua.uaproject.catwalk.webserver.WebServerRoutes;
import dev.ua.uaproject.catwalk.webserver.catwalk.CatWalkWebserverService;
import dev.ua.uaproject.catwalk.webserver.catwalk.impl.CatWalkWebserverServiceImpl;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class CatWalkMain extends JavaPlugin {

    private static final java.util.logging.Logger log = Bukkit.getLogger();
    private final Logger rootLogger = (Logger) LogManager.getRootLogger();
    private final List<ConsoleLine> consoleBuffer = new ArrayList<>();
    private WebhookEventListener webhookEventListener;
    private int maxConsoleBufferSize = 1000;
    private ConsoleListener consoleListener;
    public static CatWalkMain instance;
    private final LagDetector lagDetector;
    private StatsManager statsManager;
    private final Server server;
    private WebServer app;

    public CatWalkMain() {
        super();
        instance = this;
        server = getServer();
        lagDetector = new LagDetector();
    }

    @Override
    public void onEnable() {
        new Metrics(this, 9492);

        this.statsManager = new StatsManager(this);

        Bukkit.getScheduler().runTaskTimer(this, lagDetector, 100, 1);

        saveDefaultConfig();

        FileConfiguration bukkitConfig = getConfig();
        maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");
        consoleListener = new ConsoleListener(this);
        rootLogger.addFilter(consoleListener);

        setupWebServer(bukkitConfig);

        new CatWalkCommand(this);

        webhookEventListener = new WebhookEventListener(this, bukkitConfig, log);
        server.getPluginManager().registerEvents(webhookEventListener, this);
        server.getPluginManager().registerEvents(new StatsListener(statsManager), this);
        server.getServicesManager().register(CatWalkWebserverService.class, new CatWalkWebserverServiceImpl(this), this, ServicePriority.Normal);
    }

    private void setupWebServer(FileConfiguration bukkitConfig) {
        app = new WebServer(this, bukkitConfig, log);
        app.start(bukkitConfig.getInt("port", 4567));
        WebServerRoutes.addV1Routes(this, log, lagDetector, app, consoleListener);
    }

    public void reload() {
        if (app != null) {
            app.stop();
        }
        log.info("[CatWalk] CatWalk reloading...");
        reloadConfig();
        FileConfiguration bukkitConfig = getConfig();
        maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");

        consoleListener.resetListeners();

        setupWebServer(bukkitConfig);

        webhookEventListener.loadWebhooksFromConfig(bukkitConfig);
        log.info("[CatWalk] CatWalk reloaded successfully!");
    }

    @Override
    public void onDisable() {
        PluginMeta pluginMeta = getPluginMeta();

        log.info(String.format("[%s] Disabled Version %s", pluginMeta.getDescription(), pluginMeta.getVersion()));
        if (app != null) {
            app.stop();
        }
    }

    public int getMaxConsoleBufferSize() {
        return this.maxConsoleBufferSize;
    }

    public List<ConsoleLine> getConsoleBuffer() {
        return this.consoleBuffer;
    }

    public WebServer getWebServer() {
        return this.app;
    }

    public OpenApiPlugin getOpenApiPlugin() {
        return this.app.getOpenApiPlugin();
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }
}
