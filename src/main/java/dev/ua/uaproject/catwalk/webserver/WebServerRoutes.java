package dev.ua.uaproject.catwalk.webserver;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.api.ApiV1Initializer;
import dev.ua.uaproject.catwalk.utils.LagDetector;
import io.javalin.http.Handler;
import io.javalin.websocket.WsConfig;

import java.util.function.Consumer;
import java.util.logging.Logger;

import static dev.ua.uaproject.catwalk.utils.Constants.API_V1;

public final class WebServerRoutes {

    private WebServerRoutes() {
    }

    public static void addV1Routes(CatWalkMain main, Logger log, LagDetector lagDetector, WebServer webServer) {
        PrefixedRouteBuilder pr = new PrefixedRouteBuilder(API_V1, webServer);

        ApiV1Initializer api = new ApiV1Initializer(main, log, lagDetector, main.getStatsManager());

        pr.get("stats/summary", api.getStatsApi()::getStatsSummary);
        pr.get("stats/online", api.getStatsApi()::getOnlinePlayersData);
        pr.get("stats/topplayers", api.getStatsApi()::getTopPlayers);
        pr.get("stats/hourly", api.getStatsApi()::getHourlyDistribution);
    }

    private static class PrefixedRouteBuilder {
        private final String prefix;
        private final WebServer webServer;

        public PrefixedRouteBuilder(String prefix, WebServer webServer) {
            this.prefix = prefix;
            this.webServer = webServer;
        }

        public void get(String route, Handler handler) {
            webServer.get(prefix + "/" + route, handler);
        }

        public void post(String route, Handler handler) {
            webServer.post(prefix + "/" + route, handler);
        }

        public void put(String route, Handler handler) {
            webServer.put(prefix + "/" + route, handler);
        }

        public void delete(String route, Handler handler) {
            webServer.delete(prefix + "/" + route, handler);
        }

        public void ws(String route, Consumer<WsConfig> wsConfig) {
            webServer.ws(prefix + "/" + route, wsConfig);
        }
    }
}
