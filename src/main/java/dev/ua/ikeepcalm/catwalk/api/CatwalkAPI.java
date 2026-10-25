package dev.ua.ikeepcalm.catwalk.api;

import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CatwalkAPI {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private static final Map<String, String> TOKENS_BY_ORIGIN = new ConcurrentHashMap<>();

    public static void setBearerToken(String baseUrl, String token) {
        String origin = origin(URI.create(baseUrl));
        if (token == null || token.isEmpty()) {
            TOKENS_BY_ORIGIN.remove(origin);
        } else {
            TOKENS_BY_ORIGIN.put(origin, token);
        }
    }

    public static String getBearerToken(String baseUrl) {
        return TOKENS_BY_ORIGIN.get(origin(URI.create(baseUrl)));
    }

    private static String origin(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Not an absolute URL: " + uri);
        }
        int port = uri.getPort() != -1 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + ":" + port;
    }

    public static ApiResponse sendGet(String url) {
        return sendRequest(url, "GET", null, Map.of());
    }

    public static ApiResponse sendGet(String url, Map<String, String> headers) {
        return sendRequest(url, "GET", null, headers);
    }

    public static ApiResponse sendPost(String url, String body) {
        return sendRequest(url, "POST", body, Map.of());
    }

    public static ApiResponse sendPost(String url, String body, Map<String, String> headers) {
        return sendRequest(url, "POST", body, headers);
    }

    public static ApiResponse sendPut(String url, String body) {
        return sendRequest(url, "PUT", body, Map.of());
    }

    public static ApiResponse sendPut(String url, String body, Map<String, String> headers) {
        return sendRequest(url, "PUT", body, headers);
    }

    public static ApiResponse sendDelete(String url) {
        return sendRequest(url, "DELETE", null, Map.of());
    }

    public static ApiResponse sendDelete(String url, Map<String, String> headers) {
        return sendRequest(url, "DELETE", null, headers);
    }

    public static CompletableFuture<ApiResponse> sendGetAsync(String url) {
        return sendRequestAsync(url, "GET", null, Map.of());
    }

    public static CompletableFuture<ApiResponse> sendPostAsync(String url, String body) {
        return sendRequestAsync(url, "POST", body, Map.of());
    }

    public static ApiResponse sendRequest(String url, String method, String body, Map<String, String> headers) {
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(buildRequest(url, method, body, headers), HttpResponse.BodyHandlers.ofString());
            CatWalkLogger.debug("API request %s %s -> %d", method, url, response.statusCode());
            return toApiResponse(response);
        } catch (IllegalArgumentException e) {
            return failure("Invalid URL: " + e.getMessage());
        } catch (IOException e) {
            CatWalkLogger.error("Network error for %s %s", e, method, url);
            return failure("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("Request interrupted: " + e.getMessage());
        }
    }

    public static CompletableFuture<ApiResponse> sendRequestAsync(String url, String method, String body, Map<String, String> headers) {
        HttpRequest request;
        try {
            request = buildRequest(url, method, body, headers);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(failure("Invalid URL: " + e.getMessage()));
        }
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(CatwalkAPI::toApiResponse)
                .exceptionally(error -> {
                    CatWalkLogger.error("Async request %s %s failed", error, method, url);
                    return failure("Request failed: " + error.getMessage());
                });
    }

    private static HttpRequest buildRequest(String url, String method, String body, Map<String, String> headers) {
        URI uri = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(60));

        if (headers != null) {
            headers.forEach(builder::header);
        }
        String token = TOKENS_BY_ORIGIN.get(origin(uri));
        if (token != null) {
            builder.setHeader("Authorization", "Bearer " + token);
        }

        if (body == null || body.isEmpty()) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static ApiResponse toApiResponse(HttpResponse<String> response) {
        return new ApiResponse(response.statusCode(), response.body(), response.headers().map());
    }

    private static ApiResponse failure(String message) {
        return new ApiResponse(-1, message, Map.of());
    }
}
