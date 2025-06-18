package dev.ua.uaproject.catwalk.hub.api.v1;

import dev.ua.uaproject.catwalk.hub.api.stats.StatsManager;
import dev.ua.uaproject.catwalk.common.utils.LagDetector;
import io.javalin.http.Context;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class StatsApi {

    private final LagDetector lagDetector;
    private final StatsManager statsManager;

    public StatsApi(Logger log, LagDetector lagDetector, StatsManager statsManager) {
        this.lagDetector = lagDetector;
        this.statsManager = statsManager;
    }

    @OpenApi(
            path = "/v1/stats/summary",
            summary = "Get server statistics summary",
            tags = {"Stats"},
            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(type = "application/json"))
            }
    )
    public void getStatsSummary(Context ctx) {
        Map<String, Object> summary = statsManager.getStatsSummary();

        summary.put("tps", lagDetector.getTPSString());

        ctx.json(summary);
    }

    @OpenApi(
            path = "/v1/stats/online",
            summary = "Get online players data for the past week",
            tags = {"Stats"},
            queryParams = {
                    @OpenApiParam(name = "days", type = Integer.class, description = "Number of days of data to return (max 14, default 7)")
            },
            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(type = "application/json"))
            }
    )
    public void getOnlinePlayersData(Context ctx) {
        int days = 7;
        String daysParam = ctx.queryParam("days");
        if (daysParam != null) {
            try {
                days = Integer.parseInt(daysParam);
                if (days > 14) days = 14;
                if (days < 1) days = 1;
            } catch (NumberFormatException ignored) {
                // Use default
            }
        }

        Map<String, Object> response = new HashMap<>();

        List<Map<String, Object>> playersData = statsManager.getOnlinePlayersData(days);
        response.put("players", playersData);

        Map<String, Integer> hourlyDistribution = statsManager.getCurrentHourlyDistribution();
        response.put("hourlyDistribution", hourlyDistribution);

        ctx.json(response);
    }

    @OpenApi(
            path = "/v1/stats/topplayers",
            summary = "Get most active players by playtime",
            tags = {"Stats"},

            queryParams = {
                    @OpenApiParam(name = "limit", type = Integer.class, description = "Number of players to return (default 10)")
            },
            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(type = "application/json"))
            }
    )
    public void getTopPlayers(Context ctx) {
        int limit = 10;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
                if (limit > 100) limit = 100;
                if (limit < 1) limit = 1;
            } catch (NumberFormatException ignored) {
                // Use default
            }
        }

        List<Map<String, Object>> result = statsManager.getTopPlayers(limit);
        ctx.json(result);
    }

    @OpenApi(
            path = "/v1/stats/hourly",
            summary = "Get current hourly player distribution",
            tags = {"Stats"},

            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(type = "application/json"))
            }
    )
    public void getHourlyDistribution(Context ctx) {
        Map<String, Integer> hourlyDistribution = statsManager.getCurrentHourlyDistribution();
        ctx.json(hourlyDistribution);
    }

    @OpenApi(
            path = "/v1/stats/test",
            methods = {io.javalin.openapi.HttpMethod.POST},
            summary = "Test endpoint for POST requests with body",
            tags = {"Stats"},
            requestBody = @io.javalin.openapi.OpenApiRequestBody(
                    description = "Test data to process",
                    required = true,
                    content = @OpenApiContent(
                            type = "application/json",
                            example = """
                                    {
                                        "message": "Hello from client",
                                        "data": {
                                            "key": "value",
                                            "number": 42
                                        }
                                    }
                                    """
                    )
            ),
            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(type = "application/json")),
                    @OpenApiResponse(status = "400", description = "Invalid request body")
            }
    )
    public void testPostEndpoint(Context ctx) {
        try {
            // Parse the JSON body
            String requestBody = ctx.body();
            
            // Create response
            Map<String, Object> response = new HashMap<>();
            response.put("received", requestBody);
            response.put("timestamp", System.currentTimeMillis());
            response.put("message", "Successfully processed POST request");
            
            ctx.json(response);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body: " + e.getMessage()));
        }
    }
}