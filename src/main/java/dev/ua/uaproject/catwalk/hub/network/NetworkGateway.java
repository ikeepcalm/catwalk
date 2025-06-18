package dev.ua.uaproject.catwalk.hub.network;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.common.utils.Constants;
import dev.ua.uaproject.catwalk.hub.network.source.AddonInfo;
import dev.ua.uaproject.catwalk.hub.network.source.EndpointInfo;
import dev.ua.uaproject.catwalk.hub.webserver.WebServer;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NetworkGateway implements PluginMessageListener {

    private final CatWalkMain plugin;
    private final WebServer webServer;
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> serverLastSeen = new ConcurrentHashMap<>();

    public NetworkGateway(CatWalkMain plugin, WebServer webServer) {
        this.plugin = plugin;
        this.webServer = webServer;

        setupPluginMessaging();

        log.info("[NetworkGateway] Initialized for hub server");
    }

    private void setupPluginMessaging() {
        // Register channels for Velocity communication
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Constants.PLUGIN_CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Constants.PLUGIN_CHANNEL, this);

        log.info("[NetworkGateway] Plugin messaging channels registered");
    }

    // Create proxy routes for a remote addon
    public void createProxyRoutes(String serverId, AddonInfo addonInfo) {
        log.info("[NetworkGateway] Creating proxy routes for addon '{}' on server '{}'",
                addonInfo.getName(), serverId);

        for (EndpointInfo endpoint : addonInfo.getEndpoints()) {
            for (HttpMethod method : endpoint.getMethods()) {
                String proxyPath = "/v1/servers/" + serverId + endpoint.getPath();

                // Create the proxy handler
                switch (method) {
                    case GET -> webServer.get(proxyPath, ctx -> handleProxyRequest(serverId, endpoint.getPath(), ctx));
                    case POST ->
                            webServer.post(proxyPath, ctx -> handleProxyRequest(serverId, endpoint.getPath(), ctx));
                    case PUT -> webServer.put(proxyPath, ctx -> handleProxyRequest(serverId, endpoint.getPath(), ctx));
                    case DELETE ->
                            webServer.delete(proxyPath, ctx -> handleProxyRequest(serverId, endpoint.getPath(), ctx));
                    default -> log.warn("[NetworkGateway] Unsupported HTTP method: {}", method);
                }

                log.info("[NetworkGateway] Created proxy route: {} {} → {}:{}",
                        method, proxyPath, serverId, endpoint.getPath());
            }
        }
    }

    // Handle proxied request to remote server
    private void handleProxyRequest(String serverId, String originalPath, Context ctx) {
        String requestId = UUID.randomUUID().toString();

        log.debug("[NetworkGateway] Proxying request {} to server '{}': {} {}",
                requestId, serverId, ctx.method(), originalPath);

        // Check if target server is available
        if (!isServerAvailable(serverId)) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .json(Map.of("error", "Target server '" + serverId + "' is not available"));
            return;
        }

        // Prepare request data
        ProxyRequest proxyRequest = new ProxyRequest();
        proxyRequest.setPath(originalPath);
        proxyRequest.setMethod(ctx.method().name());
        proxyRequest.setHeaders(ctx.headerMap());
        proxyRequest.setQueryParams(ctx.queryParamMap());
        proxyRequest.setBody(ctx.body());

        // Create future for response
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        // Send request to target server via plugin messaging
        sendProxyRequest(serverId, requestId, proxyRequest);

        // Wait for response with timeout
        future.orTimeout(30, TimeUnit.SECONDS)
                .thenAccept(response -> {
                    try {
                        ProxyResponse proxyResponse = plugin.getGson().fromJson(response, ProxyResponse.class);

                        // Set response status and headers
                        ctx.status(proxyResponse.getStatusCode());
                        proxyResponse.getHeaders().forEach(ctx::header);

                        // Set response body
                        if (proxyResponse.getBody() != null) {
                            if (proxyResponse.getContentType().contains("application/json")) {
                                ctx.json(proxyResponse.getBody());
                            } else {
                                ctx.result(proxyResponse.getBody());
                            }
                        }

                    } catch (Exception e) {
                        log.error("[NetworkGateway] Error processing proxy response for request {}", requestId, e);
                        ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .json(Map.of("error", "Error processing response from target server"));
                    }
                })
                .exceptionally(ex -> {
                    log.error("[NetworkGateway] Proxy request {} to server '{}' failed", requestId, serverId, ex);

                    if (!ctx.res().isCommitted()) {
                        ctx.status(HttpStatus.GATEWAY_TIMEOUT)
                                .json(Map.of("error", "Request to target server timed out"));
                    }
                    return null;
                });
    }

    // Send proxy request via plugin messaging
    private void sendProxyRequest(String serverId, String requestId, ProxyRequest request) {
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Forward");
            out.writeUTF(serverId);
            out.writeUTF("catwalk:proxy");

            // Serialize request data
            ByteArrayDataOutput requestData = ByteStreams.newDataOutput();
            requestData.writeUTF("proxy_request");
            requestData.writeUTF(requestId);
            requestData.writeUTF(plugin.getGson().toJson(request));

            out.writeShort(requestData.toByteArray().length);
            out.write(requestData.toByteArray());

            // Send via Velocity
            Player player = plugin.getServer().getOnlinePlayers().iterator().next();
            if (player != null) {
                player.sendPluginMessage(plugin, Constants.PLUGIN_CHANNEL, out.toByteArray());
                log.debug("[NetworkGateway] Sent proxy request {} to server '{}'", requestId, serverId);
            } else {
                log.error("[NetworkGateway] No online players to send plugin message");
                CompletableFuture<String> future = pendingRequests.remove(requestId);
                if (future != null) {
                    future.completeExceptionally(new RuntimeException("No online players for plugin messaging"));
                }
            }

        } catch (Exception e) {
            log.error("[NetworkGateway] Error sending proxy request {} to server '{}'", requestId, serverId, e);
            CompletableFuture<String> future = pendingRequests.remove(requestId);
            if (future != null) {
                future.completeExceptionally(e);
            }
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        log.info("[RequestHandler] Received channel '{}' from '{}'", channel, player.getName());
        if (!Constants.PLUGIN_CHANNEL.equals(channel)) return;

        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String messageType = in.readUTF();

            switch (messageType) {
                case "proxy_response":
                    handleProxyResponse(in);
                    break;
                case "server_announcement":
                    handleServerAnnouncement(in);
                    break;
                case "addon_announcement":
                    handleAddonAnnouncement(in);
                    break;
                default:
                    log.warn("[NetworkGateway] Unknown message type: {}", messageType);
            }

        } catch (Exception e) {
            log.error("[NetworkGateway] Error processing plugin message", e);
        }
    }

    private void handleProxyResponse(ByteArrayDataInput in) {
        String requestId = in.readUTF();
        String response = in.readUTF();

        CompletableFuture<String> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(response);
            log.debug("[NetworkGateway] Completed proxy request {}", requestId);
        } else {
            log.warn("[NetworkGateway] Received response for unknown request: {}", requestId);
        }
    }

    private void handleServerAnnouncement(ByteArrayDataInput in) {
        String serverId = in.readUTF();
        String serverInfoJson = in.readUTF();

        serverLastSeen.put(serverId, System.currentTimeMillis());

        log.info("[NetworkGateway] Server '{}' announced itself to the network", serverId);

        // You can parse serverInfoJson and store server metadata if needed
    }

    private void handleAddonAnnouncement(ByteArrayDataInput in) {
        String serverId = in.readUTF();
        String addonInfoJson = in.readUTF();

        try {
            AddonInfo addonInfo = plugin.getGson().fromJson(addonInfoJson, AddonInfo.class);
            plugin.getAddonRegistry().registerRemoteAddon(serverId, addonInfo);

            log.info("[NetworkGateway] Registered remote addon '{}' from server '{}'",
                    addonInfo.getName(), serverId);

        } catch (Exception e) {
            log.error("[NetworkGateway] Error parsing addon announcement from server '{}'", serverId, e);
        }
    }

    private boolean isServerAvailable(String serverId) {
        Long lastSeen = serverLastSeen.get(serverId);
        if (lastSeen == null) return false;

        // Consider server available if seen within last 2 minutes
        return (System.currentTimeMillis() - lastSeen) < 120000;
    }

    public void shutdown() {
        log.info("[NetworkGateway] Shutting down network gateway");
        pendingRequests.values().forEach(future ->
                future.completeExceptionally(new RuntimeException("Gateway shutting down")));
        pendingRequests.clear();
    }

    // Data classes for proxy requests/responses
    @lombok.Data
    public static class ProxyRequest {
        private String path;
        private String method;
        private Map<String, String> headers;
        private Map<String, java.util.List<String>> queryParams;
        private String body;
    }

    @lombok.Data
    public static class ProxyResponse {
        private int statusCode = 200;
        private Map<String, String> headers = new java.util.HashMap<>();
        private String body;
        private String contentType = "application/json";
    }
}
