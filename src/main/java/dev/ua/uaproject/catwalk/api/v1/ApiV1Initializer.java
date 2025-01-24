package dev.ua.uaproject.catwalk.api.v1;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.utils.ConsoleListener;
import dev.ua.uaproject.catwalk.api.v1.websockets.WebsocketHandler;
import dev.ua.uaproject.catwalk.utils.LagDetector;
import dev.ua.uaproject.catwalk.utils.pluginwrappers.ExternalPluginWrapperRepo;

import java.util.logging.Logger;

public class ApiV1Initializer {
    private final WebsocketHandler websocketHandler;
    private final AdvancementsApi advancementsApi;
    private final EconomyApi economyApi;
    private final PluginApi pluginApi;
    private final ServerApi serverApi;
    private final PlayerApi playerApi;
    private final WorldApi worldApi;
    private final PAPIApi papiApi;

    public ApiV1Initializer(CatWalkMain main, Logger log, LagDetector lagDetector, ConsoleListener consoleListener,
                            ExternalPluginWrapperRepo externalPluginWrapperRepo) {
        this.websocketHandler = new WebsocketHandler(main, log, consoleListener);
        this.advancementsApi = new AdvancementsApi();
        this.economyApi = new EconomyApi(externalPluginWrapperRepo.getEconomyWrapper());
        this.pluginApi = new PluginApi(main, log);
        this.serverApi = new ServerApi(main, log, lagDetector, externalPluginWrapperRepo.getEconomyWrapper());
        this.playerApi = new PlayerApi(log, externalPluginWrapperRepo.getEconomyWrapper());
        this.worldApi = new WorldApi(main, log);
        this.papiApi = new PAPIApi();
    }

    public WebsocketHandler getWebsocketHandler() {
        return websocketHandler;
    }

    public AdvancementsApi getAdvancementsApi() {
        return advancementsApi;
    }

    public EconomyApi getEconomyApi() {
        return economyApi;
    }

    public PluginApi getPluginApi() {
        return pluginApi;
    }

    public ServerApi getServerApi() {
        return serverApi;
    }

    public PlayerApi getPlayerApi() {
        return playerApi;
    }

    public WorldApi getWorldApi() {
        return worldApi;
    }

    public PAPIApi getPapiApi() {
        return papiApi;
    }
}
