package dev.ua.ikeepcalm.catwalk.hub.network;

import com.google.gson.reflect.TypeToken;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseManager;
import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkRequest;
import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkResponse;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.QueueSignature;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonSingleton;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RequestProcessor {

    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Pattern SAFE_PATH = Pattern.compile("/(?!/)[A-Za-z0-9\\-._~%!$&'()*+,;=:@/]*");
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_CLOCK_SKEW_MILLIS = 60_000;
    private static final Set<String> DROPPED_REQUEST_HEADERS = Set.of(
            "connection", "content-length", "date", "expect", "from", "host", "upgrade", "via", "warning",
            "authorization", "cookie", "proxy-authorization", "proxy-authenticate", "proxy-connection", "www-authenticate",
            "transfer-encoding", "trailer", "te", "x-real-ip", "accept-encoding", "x-http-method-override"
    );

    private final DatabaseManager databaseManager;
    private final String serverId;
    private final int localPort;
    private final String queueKey;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final BukkitTask processingTask;

    public RequestProcessor(DatabaseManager databaseManager, String serverId, Plugin plugin, int localPort, String queueKey) {
        this.databaseManager = databaseManager;
        this.serverId = serverId;
        this.localPort = localPort;
        this.queueKey = queueKey;
        this.processingTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 20L, 20L);
    }

    public static String requestSignature(String key, NetworkRequest request, String timestamp) {
        return QueueSignature.sign(key, requestSignatureParts(request, timestamp));
    }

    public static boolean hasValidSignature(String key, NetworkResponse response) {
        String signature = response.getHeaders() != null ? response.getHeaders().get(QueueSignature.SIGNATURE_HEADER) : null;
        return QueueSignature.verify(key, signature, responseParts(response));
    }

    private static String[] responseParts(NetworkResponse response) {
        return new String[]{response.getRequestId(), response.getServerId(), String.valueOf(response.getStatusCode()),
                response.getContentType(), response.getBody()};
    }

    private static String canonicalQuery(Map<String, String> queryParams) {
        return queryParams == null ? "" : new TreeMap<>(queryParams).toString();
    }

    private void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            expireStaleRequests();
            pendingRequests().stream().filter(this::claim).forEach(this::process);
        } catch (RuntimeException e) {
            CatWalkLogger.error("Failed to poll the request queue", e);
        } finally {
            polling.set(false);
        }
    }

    private void expireStaleRequests() {
        databaseManager.executeUpdate("""
                UPDATE request_queue SET status = 'timeout', processed_at = CURRENT_TIMESTAMP
                WHERE target_server_id = ? AND status = 'pending'
                AND created_at <= DATE_SUB(NOW(), INTERVAL timeout_seconds SECOND)
                """, stmt -> stmt.setString(1, serverId));
    }

    private List<NetworkRequest> pendingRequests() {
        return databaseManager.executeQuery("""
                SELECT * FROM request_queue
                WHERE target_server_id = ? AND status = 'pending'
                ORDER BY priority DESC, created_at ASC
                LIMIT 10
                """, stmt -> stmt.setString(1, serverId), rs -> {
            List<NetworkRequest> requests = new ArrayList<>();
            while (rs.next()) {
                requests.add(mapRequest(rs));
            }
            return requests;
        });
    }

    private boolean claim(NetworkRequest request) {
        return databaseManager.executeUpdate(
                "UPDATE request_queue SET status = 'processing' WHERE request_id = ? AND status = 'pending'",
                stmt -> stmt.setString(1, request.getRequestId())) == 1;
    }

    private void process(NetworkRequest request) {
        String rejection = rejectionReason(request);
        if (rejection != null) {
            CatWalkLogger.error("Rejected queued request %s: %s", request.getRequestId(), rejection);
            complete(request, errorResponse(request, 403, "Request rejected by the backend server"), NetworkRequest.RequestStatus.FAILED);
            return;
        }

        HttpRequest localRequest;
        try {
            localRequest = buildLocalRequest(request);
        } catch (IllegalArgumentException e) {
            CatWalkLogger.error("Rejected queued request %s: %s", request.getRequestId(), e.getMessage());
            complete(request, errorResponse(request, 400, "Invalid proxied request"), NetworkRequest.RequestStatus.FAILED);
            return;
        }
        long startTime = System.currentTimeMillis();
        httpClient.sendAsync(localRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> complete(request, toNetworkResponse(request, response, startTime), NetworkRequest.RequestStatus.COMPLETED))
                .exceptionally(e -> {
                    CatWalkLogger.error("Failed to process proxied request %s", e, request.getRequestId());
                    complete(request, errorResponse(request, 502, "Backend server failed to process the request"), NetworkRequest.RequestStatus.FAILED);
                    return null;
                });
    }

    private String rejectionReason(NetworkRequest request) {
        Map<String, String> headers = request.getHeaders() != null ? request.getHeaders() : Map.of();
        String timestamp = headers.get(QueueSignature.TIMESTAMP_HEADER);
        if (!QueueSignature.verify(queueKey, headers.get(QueueSignature.SIGNATURE_HEADER), requestSignatureParts(request, timestamp))) {
            return "bad signature (every server must use the same key)";
        }
        long age = System.currentTimeMillis() - Long.parseLong(timestamp);
        if (age < -MAX_CLOCK_SKEW_MILLIS || age > request.getTimeoutSeconds() * 1000L + MAX_CLOCK_SKEW_MILLIS) {
            return "request is too old";
        }
        if (request.getEndpointPath() == null || !SAFE_PATH.matcher(request.getEndpointPath()).matches()) {
            return "invalid endpoint path";
        }
        return null;
    }

    private static String[] requestSignatureParts(NetworkRequest request, String timestamp) {
        return new String[]{request.getRequestId(), request.getTargetServerId(), request.getHttpMethod().name(),
                request.getEndpointPath(), canonicalQuery(request.getQueryParams()), request.getBody(), timestamp};
    }

    private HttpRequest buildLocalRequest(NetworkRequest request) {
        URI uri = URI.create("http://localhost:" + localPort + request.getEndpointPath() + queryString(request.getQueryParams()));
        if (!"localhost".equals(uri.getHost()) || uri.getPort() != localPort) {
            throw new IllegalArgumentException("Refusing to call " + uri.getHost());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(request.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + queueKey);
        if (request.getHeaders() != null) {
            request.getHeaders().forEach((key, value) -> {
                String lowerKey = key.toLowerCase();
                if (!DROPPED_REQUEST_HEADERS.contains(lowerKey) && !lowerKey.startsWith("sec-")
                        && !lowerKey.startsWith("x-forwarded-") && !lowerKey.startsWith("x-catwalk-")) {
                    builder.header(key, value);
                }
            });
        }

        String body = request.getBody() != null ? request.getBody() : "";
        return switch (request.getHttpMethod()) {
            case GET -> builder.GET().build();
            case DELETE -> builder.DELETE().build();
            case POST, PUT, PATCH -> builder.method(request.getHttpMethod().name(), HttpRequest.BodyPublishers.ofString(body)).build();
        };
    }

    private static String queryString(Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        return queryParams.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&", "?", ""));
    }

    private NetworkResponse toNetworkResponse(NetworkRequest request, HttpResponse<InputStream> response, long startTime) {
        byte[] body;
        try (InputStream stream = response.body()) {
            body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            return errorResponse(request, 502, "Backend response is too large");
        }

        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((key, values) -> {
            if (!values.isEmpty() && !key.equalsIgnoreCase("content-encoding")) {
                headers.put(key, values.getFirst());
            }
        });

        return NetworkResponse.builder()
                .requestId(request.getRequestId())
                .serverId(serverId)
                .statusCode(response.statusCode())
                .headers(headers)
                .body(new String(body, StandardCharsets.UTF_8))
                .contentType(response.headers().firstValue("content-type").orElse("application/json"))
                .processedTimeMs((int) (System.currentTimeMillis() - startTime))
                .build();
    }

    private NetworkResponse errorResponse(NetworkRequest request, int status, String message) {
        return NetworkResponse.builder()
                .requestId(request.getRequestId())
                .serverId(serverId)
                .statusCode(status)
                .headers(new HashMap<>())
                .body(GsonSingleton.getInstance().toJson(Map.of("error", message)))
                .contentType("application/json")
                .build();
    }

    private void complete(NetworkRequest request, NetworkResponse response, NetworkRequest.RequestStatus status) {
        Map<String, String> headers = new HashMap<>(response.getHeaders());
        headers.put(QueueSignature.SIGNATURE_HEADER, QueueSignature.sign(queueKey, responseParts(response)));
        try {
            databaseManager.executeUpdate("""
                    INSERT INTO response_queue (request_id, server_id, status_code, headers, body, content_type, processed_time_ms, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                    """, stmt -> {
                stmt.setString(1, response.getRequestId());
                stmt.setString(2, response.getServerId());
                stmt.setInt(3, response.getStatusCode());
                stmt.setString(4, GsonSingleton.getInstance().toJson(headers));
                stmt.setString(5, response.getBody());
                stmt.setString(6, response.getContentType());
                stmt.setObject(7, response.getProcessedTimeMs());
            });
            databaseManager.executeUpdate("UPDATE request_queue SET status = ?, processed_at = CURRENT_TIMESTAMP WHERE request_id = ?", stmt -> {
                stmt.setString(1, status.name().toLowerCase());
                stmt.setString(2, request.getRequestId());
            });
        } catch (DatabaseManager.DatabaseException e) {
            CatWalkLogger.error("Failed to store the response for request %s", e, request.getRequestId());
        }
    }

    private NetworkRequest mapRequest(ResultSet rs) throws SQLException {
        return NetworkRequest.builder()
                .requestId(rs.getString("request_id"))
                .targetServerId(rs.getString("target_server_id"))
                .endpointPath(rs.getString("endpoint_path"))
                .httpMethod(NetworkRequest.HttpMethod.valueOf(rs.getString("http_method")))
                .headers(parseStringMap(rs.getString("headers")))
                .queryParams(parseStringMap(rs.getString("query_params")))
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

    private static Map<String, String> parseStringMap(String json) {
        return json != null ? GsonSingleton.getInstance().fromJson(json, STRING_MAP_TYPE) : null;
    }

    public void shutdown() {
        processingTask.cancel();
    }
}
