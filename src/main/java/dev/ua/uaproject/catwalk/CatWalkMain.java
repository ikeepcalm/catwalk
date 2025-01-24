package dev.ua.uaproject.catwalk;

import io.papermc.paper.plugin.configuration.PluginMeta;
import dev.ua.uaproject.catwalk.api.v1.models.ConsoleLine;
import dev.ua.uaproject.catwalk.commands.ServerTapCommand;
import dev.ua.uaproject.catwalk.metrics.Metrics;
import dev.ua.uaproject.catwalk.plugin.api.ServerTapWebserverService;
import dev.ua.uaproject.catwalk.plugin.api.ServerTapWebserverServiceImpl;
import dev.ua.uaproject.catwalk.utils.ConsoleListener;
import dev.ua.uaproject.catwalk.utils.LagDetector;
import dev.ua.uaproject.catwalk.utils.pluginwrappers.ExternalPluginWrapperRepo;
import dev.ua.uaproject.catwalk.webhooks.WebhookEventListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class ServerTapMain extends JavaPlugin {

    private static final java.util.logging.Logger log = Bukkit.getLogger();
    private final Logger rootLogger = (Logger) LogManager.getRootLogger();
    private final List<ConsoleLine> consoleBuffer = new ArrayList<>();
    private ExternalPluginWrapperRepo externalPluginWrapperRepo;
    private WebhookEventListener webhookEventListener;
    private int maxConsoleBufferSize = 1000;
    private ConsoleListener consoleListener;
    public static ServerTapMain instance;
    private final LagDetector lagDetector;
    private final Server server;
    private WebServer app;

    public ServerTapMain() {
        super();
        instance = this;
        server = getServer();
        lagDetector = new LagDetector();
    }

    @Override
    public void onEnable() {
        // Tell bStats what plugin this is
        new Metrics(this, 9492);

        // Initialize any external plugin integrations
        externalPluginWrapperRepo = new ExternalPluginWrapperRepo(this, log);

        // Start the TPS Counter with a 100 tick Delay every 1 tick
        Bukkit.getScheduler().runTaskTimer(this, lagDetector, 100, 1);

        // Initialize config file + set defaults
        saveDefaultConfig();

        FileConfiguration bukkitConfig = getConfig();
        maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");
        consoleListener = new ConsoleListener(this);
        rootLogger.addFilter(consoleListener);

        setupWebServer(bukkitConfig);

        new ServerTapCommand(this);

        webhookEventListener = new WebhookEventListener(this, bukkitConfig, log, externalPluginWrapperRepo.getEconomyWrapper());
        server.getPluginManager().registerEvents(webhookEventListener, this);

        server.getServicesManager().register(ServerTapWebserverService.class, new ServerTapWebserverServiceImpl(this), this, ServicePriority.Normal);
    }

    private void setupWebServer(FileConfiguration bukkitConfig) {
        app = new WebServer(this, bukkitConfig, log);
        app.start(bukkitConfig.getInt("port", 4567));
        WebServerRoutes.addV1Routes(this, log, lagDetector, app, consoleListener, externalPluginWrapperRepo);
    }

    public void reload() {
        if (app != null) {
            app.stop();
        }
        log.info("[ServerTap] ServerTap reloading...");
        reloadConfig();
        FileConfiguration bukkitConfig = getConfig();
        maxConsoleBufferSize = bukkitConfig.getInt("websocketConsoleBuffer");

        externalPluginWrapperRepo = new ExternalPluginWrapperRepo(this, log);
        consoleListener.resetListeners();

        setupWebServer(bukkitConfig);

        webhookEventListener.loadWebhooksFromConfig(bukkitConfig);
        log.info("[ServerTap] ServerTap reloaded successfully!");
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
}
