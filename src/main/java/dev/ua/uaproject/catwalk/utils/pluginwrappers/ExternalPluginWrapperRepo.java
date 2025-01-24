package dev.ua.uaproject.catwalk.utils.pluginwrappers;

import dev.ua.uaproject.catwalk.CatWalkMain;

import java.util.logging.Logger;

public class ExternalPluginWrapperRepo {

    private final EconomyWrapper economyWrapper;

    public ExternalPluginWrapperRepo(CatWalkMain main, Logger logger) {
        this.economyWrapper = new EconomyWrapper(main, logger);
    }

    public EconomyWrapper getEconomyWrapper() {
        return this.economyWrapper;
    }
}
