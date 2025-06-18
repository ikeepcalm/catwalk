package dev.ua.uaproject.catwalk.hub.network;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.common.utils.Constants;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class RequestHandler implements PluginMessageListener {

    private final CatWalkMain plugin;
    private final HttpClient httpClient;

    public RequestHandler(CatWalkMain plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Register plugin messaging for receiving proxy requests
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Constants.PLUGIN_CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Constants.PLUGIN_CHANNEL);

        log.info("[RequestHandler] Initialized for server '{}'", plugin.getServerId());
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        log.info("[RequestHandler] Received channel '{}' from '{}'", channel, player.getName());
        if (!Constants.PLUGIN_CHANNEL.equals(channel)) return;

        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String messageType = in.readUTF();

            if ("proxy_request".equals(messageType)) {
                handleProxyRequest(in);
            }

        } catch (Exception e) {
            log.error("[RequestHandler] Error processing proxy request", e);
        }
    }

    private void handleProxyRequest(ByteArrayDataInput in) {
        String requestId = in.readUTF();
        String requestJson = in.readUTF();

        log.debug("[RequestHandler] Processing proxy request {}", requestId);

        try {
            NetworkGateway.ProxyRequest request = plugin.getGson().fromJson(requestJson, NetworkGateway.ProxyRequest.class);
            NetworkGateway.ProxyResponse response = processLocalRequest(request);

            // Send response back to hub
            sendResponseToHub(requestId, response);

        } catch (Exception e) {
            log.error("[RequestHandler] Error processing proxy request {}", requestId, e);

            // Send error response
            NetworkGateway.ProxyResponse errorResponse = new NetworkGateway.ProxyResponse();
            errorResponse.setStatusCode(500);
            errorResponse.setBody("{\"error\":\"Internal server error\"}");
            errorResponse.setContentType("application/json");

            sendResponseToHub(requestId, errorResponse);
        }
    }

    private NetworkGateway.ProxyResponse processLocalRequest(NetworkGateway.ProxyRequest request) {
        NetworkGateway.ProxyResponse response = new NetworkGateway.ProxyResponse();

        try {
            // Build the local request URL
            String baseUrl = "http://localhost:" + plugin.getConfig().getInt("port", 4567);
            String fullUrl = baseUrl + request.getPath();

            // Add query parameters
            if (request.getQueryParams() != null && !request.getQueryParams().isEmpty()) {
                StringBuilder queryBuilder = new StringBuilder("?");
                request.getQueryParams().forEach((key, values) -> {
                    for (String value : values) {
                        if (queryBuilder.length() > 1) queryBuilder.append("&");
                        queryBuilder.append(key).append("=").append(value);
                    }
                });
                fullUrl += queryBuilder.toString();
            }

            // Build HTTP request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(30));

            // Add headers (excluding problematic ones)
            if (request.getHeaders() != null) {
                request.getHeaders().forEach((key, value) -> {
                    if (!key.toLowerCase().equals("host") &&
                            !key.toLowerCase().equals("content-length")) {
                        requestBuilder.header(key, value);
                    }
                });
            }

            // Set method and body
            switch (request.getMethod().toUpperCase()) {
                case "GET" -> requestBuilder.GET();
                case "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(
                        request.getBody() != null ? request.getBody() : ""));
                case "PUT" -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(
                        request.getBody() != null ? request.getBody() : ""));
                case "DELETE" -> requestBuilder.DELETE();
                default -> {
                    response.setStatusCode(405);
                    response.setBody("{\"error\":\"Method not allowed\"}");
                    return response;
                }
            }

            // Execute request
            HttpResponse<String> httpResponse = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            // Build response
            response.setStatusCode(httpResponse.statusCode());
            response.setBody(httpResponse.body());

            // Copy response headers
            Map<String, String> responseHeaders = new HashMap<>();
            httpResponse.headers().map().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    responseHeaders.put(key, values.getFirst());
                }
            });
            response.setHeaders(responseHeaders);

            // Set content type
            String contentType = httpResponse.headers().firstValue("content-type")
                    .orElse("application/json");
            response.setContentType(contentType);

            log.debug("[RequestHandler] Local request completed with status {}", httpResponse.statusCode());

        } catch (Exception e) {
            log.error("[RequestHandler] Error executing local request", e);
            response.setStatusCode(500);
            response.setBody("{\"error\":\"Failed to execute local request: " + e.getMessage() + "\"}");
            response.setContentType("application/json");
        }

        return response;
    }

    private void sendResponseToHub(String requestId, NetworkGateway.ProxyResponse response) {
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ForwardToAll");
            out.writeUTF("catwalk:network");

            ByteArrayDataOutput responseData = ByteStreams.newDataOutput();
            responseData.writeUTF("proxy_response");
            responseData.writeUTF(requestId);
            responseData.writeUTF(plugin.getGson().toJson(response));

            out.writeShort(responseData.toByteArray().length);
            out.write(responseData.toByteArray());

            // Send back to hub via Velocity
            Player player = plugin.getServer().getOnlinePlayers().iterator().next();
            if (player != null) {
                player.sendPluginMessage(plugin, Constants.PLUGIN_CHANNEL, out.toByteArray());
                log.debug("[RequestHandler] Sent response for request {}", requestId);
            } else {
                log.error("[RequestHandler] No online players to send response");
            }

        } catch (Exception e) {
            log.error("[RequestHandler] Error sending response for request {}", requestId, e);
        }
    }
}