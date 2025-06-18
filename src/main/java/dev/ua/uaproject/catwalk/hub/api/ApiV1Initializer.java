package dev.ua.uaproject.catwalk.hub.api;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.hub.api.v1.StatsApi;
import dev.ua.uaproject.catwalk.hub.api.stats.StatsManager;
import dev.ua.uaproject.catwalk.common.utils.LagDetector;
import lombok.Getter;

import java.util.logging.Logger;

@Getter
public class ApiV1Initializer {
    private final StatsApi statsApi;

    public ApiV1Initializer(CatWalkMain main, Logger log, LagDetector lagDetector, StatsManager statsManager) {
        this.statsApi = new StatsApi(log, lagDetector, statsManager);
    }

}
