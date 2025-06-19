package dev.ua.uaproject.catwalk.common.database.model;

import com.google.gson.Gson;
import dev.ua.uaproject.catwalk.common.database.DatabaseManager;
import dev.ua.uaproject.catwalk.common.utils.CatWalkLogger;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RequestProcessor {

    private final DatabaseManager databaseManager;
    private final String serverId;
    private final Plugin plugin;
    private final HttpClient httpClient;
    private final Gson gson;
    private final int localPort;

    private BukkitTask processingTask;

    public RequestProcessor(DatabaseManager databaseManager, String serverId, Plugin plugin, int localPort) {
        this.databaseManager = databaseManager;
        this.serverId = serverId;
        this.plugin = plugin;
        this.localPort = localPort;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        startProcessing();
    }

    private void startProcessing() {
        processingTask = new BukkitRunnable() {
            @Override
            public void run() {
                processIncomingRequests();
            }
        }.runTaskTimerAsynchronously(plugin, 20L, 40L); // Every 2 seconds

        CatWalkLogger.debug("Started request processing for server '%s' - polling every 2 seconds", serverId);
    }

    private void processIncomingRequests() {
        try {

            // Get pending requests for this server
            String sql = """
                    SELECT * FROM request_queue 
                    WHERE target_server_id = ? 
                    AND status = 'pending' 
                    AND (created_at > DATE_SUB(NOW(), INTERVAL timeout_seconds SECOND))
                    ORDER BY priority DESC, created_at ASC 
                    LIMIT 10
                    """;

            List<NetworkRequest> requests = databaseManager.executeQuery(sql,
                    stmt -> stmt.setString(1, serverId),
                    rs -> {
                        List<NetworkRequest> list = new java.util.ArrayList<>();
                        while (rs.next()) {
                            list.add(mapResultSetToRequest(rs));
                        }
                        return list;
                    });

            if (!requests.isEmpty()) {
                CatWalkLogger.debug("Found %d pending requests for server %s", requests.size(), serverId);
            }

            for (NetworkRequest request : requests) {
                processRequest(request);
            }

        } catch (Exception e) {
            CatWalkLogger.error("Error processing requests", e);
        }
    }

    private void processRequest(NetworkRequest request) {
        CatWalkLogger.debug("Processing request %s for endpoint %s %s on server %s",
                request.getRequestId(), request.getHttpMethod(), request.getEndpointPath(), serverId);

        // Mark request as processing
        updateRequestStatus(request.getRequestId(), NetworkRequest.RequestStatus.PROCESSING);

        try {
            long startTime = System.currentTimeMillis();

            // Execute the request locally
            NetworkResponse response = executeLocalRequest(request);

            long endTime = System.currentTimeMillis();
            response.setProcessedTimeMs((int) (endTime - startTime));

            CatWalkLogger.debug("Request %s completed with status %d in %dms",
                    request.getRequestId(), response.getStatusCode(), response.getProcessedTimeMs());

            // Store the response
            storeResponse(response);

            // Mark request as completed
            updateRequestStatus(request.getRequestId(), NetworkRequest.RequestStatus.COMPLETED);

        } catch (Exception e) {
            CatWalkLogger.error("Failed to process request %s", e, request.getRequestId());

            // Create error response
            NetworkResponse errorResponse = NetworkResponse.builder()
                    .requestId(request.getRequestId())
                    .serverId(serverId)
                    .statusCode(500)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body("{\"error\":\"Internal server error\",\"message\":\"" + e.getMessage() + "\"}")
                    .contentType("application/json")
                    .build();

            storeResponse(errorResponse);
            updateRequestStatus(request.getRequestId(), NetworkRequest.RequestStatus.FAILED);
        }
    }

    private NetworkResponse executeLocalRequest(NetworkRequest request) throws Exception {
        // Build the local request URL
        String baseUrl = "http://localhost:" + localPort;
        String fullUrl = baseUrl + request.getEndpointPath();

        // Add query parameters
        if (request.getQueryParams() != null && !request.getQueryParams().isEmpty()) {
            StringBuilder queryBuilder = new StringBuilder("?");
            request.getQueryParams().forEach((key, value) -> {
                if (queryBuilder.length() > 1) queryBuilder.append("&");
                queryBuilder.append(key).append("=").append(value);
            });
            fullUrl += queryBuilder.toString();
        }

        // Build HTTP request
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(request.getTimeoutSeconds()));

        // Add headers
        if (request.getHeaders() != null) {
            request.getHeaders().forEach((key, value) -> {
                if (!key.toLowerCase().equals("host") &&
                        !key.toLowerCase().equals("content-length")) {
                    requestBuilder.header(key, value);
                }
            });
        }

        // Set method and body
        switch (request.getHttpMethod()) {
            case GET -> requestBuilder.GET();
            case POST -> {
                requestBuilder.header("Content-Type", "application/json");
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(
                        request.getBody() != null ? request.getBody() : ""));
            }
            case PUT -> {
                requestBuilder.header("Content-Type", "application/json");
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(
                        request.getBody() != null ? request.getBody() : ""));
            }
            case DELETE -> requestBuilder.DELETE();
            case PATCH -> {
                requestBuilder.header("Content-Type", "application/json");
                requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(
                        request.getBody() != null ? request.getBody() : ""));
            }
        }

        // Execute request
        CatWalkLogger.debug("Sending HTTP request: %s %s", request.getHttpMethod(), fullUrl);

        HttpResponse<String> httpResponse = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        CatWalkLogger.debug("HTTP response received: %s %s -> Status: %d",
                request.getHttpMethod(), fullUrl, httpResponse.statusCode());

        if (httpResponse.statusCode() >= 400) {
            CatWalkLogger.warn("HTTP error response: Status %d, Body: %s",
                    httpResponse.statusCode(), httpResponse.body());
        }

        // Build response
        Map<String, String> responseHeaders = new HashMap<>();
        httpResponse.headers().map().forEach((key, values) -> {
            if (!values.isEmpty()) {
                responseHeaders.put(key, values.getFirst());
            }
        });

        String contentType = httpResponse.headers().firstValue("content-type")
                .orElse("application/json");

        return NetworkResponse.builder()
                .requestId(request.getRequestId())
                .serverId(serverId)
                .statusCode(httpResponse.statusCode())
                .headers(responseHeaders)
                .body(httpResponse.body())
                .contentType(contentType)
                .build();
    }

    private void updateRequestStatus(String requestId, NetworkRequest.RequestStatus status) {
        String sql = """
                UPDATE request_queue 
                SET status = ?, processed_at = CURRENT_TIMESTAMP 
                WHERE request_id = ?
                """;

        databaseManager.executeUpdateAsync(sql, stmt -> {
            stmt.setString(1, status.name().toLowerCase());
            stmt.setString(2, requestId);
        });
    }

    private void storeResponse(NetworkResponse response) {
        String sql = """
                INSERT INTO response_queue (request_id, server_id, status_code, headers, body, content_type, processed_time_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try {
            // First verify the table exists
            String tableCheckQuery = "SHOW TABLES LIKE 'response_queue'";
            String tableExists = databaseManager.executeQuery(tableCheckQuery, null, rs ->
                    rs.next() ? rs.getString(1) : null);

            if (tableExists == null) {
                CatWalkLogger.error("response_queue table does not exist!");
                return;
            }

            CatWalkLogger.debug("Inserting response for request %s into response_queue table", response.getRequestId());

            int rowsAffected = databaseManager.executeUpdate(sql, stmt -> {
                stmt.setString(1, response.getRequestId());
                stmt.setString(2, response.getServerId());
                stmt.setInt(3, response.getStatusCode());
                stmt.setString(4, gson.toJson(response.getHeaders()));
                stmt.setString(5, response.getBody());
                stmt.setString(6, response.getContentType());
                stmt.setObject(7, response.getProcessedTimeMs());
            });

            if (rowsAffected > 0) {
                CatWalkLogger.debug("Successfully stored response for request %s with status %d",
                        response.getRequestId(), response.getStatusCode());
            } else {
                CatWalkLogger.error("Failed to store response for request %s - no rows affected",
                        response.getRequestId());
            }
        } catch (Exception e) {
            CatWalkLogger.error("Exception storing response for request %s: %s",
                    e, response.getRequestId(), e.getMessage());
        }
    }

    private NetworkRequest mapResultSetToRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, String> headers = null;
        Map<String, String> queryParams = null;

        try {
            String headersJson = rs.getString("headers");
            if (headersJson != null) {
                headers = gson.fromJson(headersJson, Map.class);
            }

            String queryParamsJson = rs.getString("query_params");
            if (queryParamsJson != null) {
                queryParams = gson.fromJson(queryParamsJson, Map.class);
            }
        } catch (Exception e) {
            CatWalkLogger.warn("Failed to parse request data for %s", rs.getString("request_id"));
        }

        return NetworkRequest.builder()
                .requestId(rs.getString("request_id"))
                .targetServerId(rs.getString("target_server_id"))
                .endpointPath(rs.getString("endpoint_path"))
                .httpMethod(NetworkRequest.HttpMethod.valueOf(rs.getString("http_method")))
                .headers(headers)
                .queryParams(queryParams)
                .body(rs.getString("body"))
                .createdAt(rs.getTimestamp("created_at"))
                .processedAt(rs.getTimestamp("processed_at"))
                .status(NetworkRequest.RequestStatus.valueOf(rs.getString("status").toUpperCase()))
                .priority(rs.getInt("priority"))
                .timeoutSeconds(rs.getInt("timeout_seconds"))
                .retryCount(rs.getInt("retry_count"))
                .maxRetries(rs.getInt("max_retries"))
                .build();
    }

    public void shutdown() {
        if (processingTask != null) {
            processingTask.cancel();
        }
        CatWalkLogger.debug("Shut down request processor");
    }
}