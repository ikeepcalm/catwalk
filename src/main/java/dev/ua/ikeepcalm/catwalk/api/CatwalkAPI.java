package dev.ua.ikeepcalm.catwalk.api;

import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * API utility class for making HTTP requests with Bearer authorization
 * from CatWalk addons to external endpoints.
 */
public class CatwalkAPI {
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * -- SETTER --
     *  Set the Bearer token to be used for all requests.
     *  This should typically be called once during addon initialization.
     *
     *
     * -- GETTER --
     *  Get the currently configured Bearer token.
     *
     */
    @Getter
    @Setter
    private static String bearerToken;

    /**
     * Send a GET request to the specified URL.
     * 
     * @param url the target URL
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendGet(String url) {
        return sendGet(url, Map.of());
    }
    
    /**
     * Send a GET request to the specified URL with custom headers.
     * 
     * @param url the target URL
     * @param headers additional headers to include
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendGet(String url, Map<String, String> headers) {
        return sendRequest(url, "GET", null, headers);
    }
    
    /**
     * Send a POST request to the specified URL with JSON body.
     * 
     * @param url the target URL
     * @param body the JSON body content
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendPost(String url, String body) {
        return sendPost(url, body, Map.of());
    }
    
    /**
     * Send a POST request to the specified URL with JSON body and custom headers.
     * 
     * @param url the target URL
     * @param body the JSON body content
     * @param headers additional headers to include
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendPost(String url, String body, Map<String, String> headers) {
        return sendRequest(url, "POST", body, headers);
    }
    
    /**
     * Send a PUT request to the specified URL with JSON body.
     * 
     * @param url the target URL
     * @param body the JSON body content
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendPut(String url, String body) {
        return sendPut(url, body, Map.of());
    }
    
    /**
     * Send a PUT request to the specified URL with JSON body and custom headers.
     * 
     * @param url the target URL
     * @param body the JSON body content
     * @param headers additional headers to include
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendPut(String url, String body, Map<String, String> headers) {
        return sendRequest(url, "PUT", body, headers);
    }
    
    /**
     * Send a DELETE request to the specified URL.
     * 
     * @param url the target URL
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendDelete(String url) {
        return sendDelete(url, Map.of());
    }
    
    /**
     * Send a DELETE request to the specified URL with custom headers.
     * 
     * @param url the target URL
     * @param headers additional headers to include
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendDelete(String url, Map<String, String> headers) {
        return sendRequest(url, "DELETE", null, headers);
    }
    
    /**
     * Send an HTTP request with the specified method, body, and headers.
     * 
     * @param url the target URL
     * @param method HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param body request body (can be null for GET/DELETE)
     * @param headers additional headers to include
     * @return ApiResponse containing the response details
     */
    public static ApiResponse sendRequest(String url, String method, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .timeout(Duration.ofSeconds(60));
            
            // Add Bearer token if available
            if (bearerToken != null && !bearerToken.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + bearerToken);
            }
            
            // Add custom headers
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }
            
            // Set content type for requests with body
            if (body != null && !body.isEmpty()) {
                requestBuilder.header("Content-Type", "application/json");
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            CatWalkLogger.debug("API Request: %s %s -> %d", method, url, response.statusCode());
            
            return new ApiResponse(
                response.statusCode(),
                response.body(),
                response.headers().map()
            );
            
        } catch (URISyntaxException e) {
            CatWalkLogger.error("Invalid URL: %s", e, url);
            return new ApiResponse(-1, "Invalid URL: " + e.getMessage(), Map.of());
        } catch (IOException e) {
            CatWalkLogger.error("Network error for %s %s: %s", e, method, url, e.getMessage());
            return new ApiResponse(-1, "Network error: " + e.getMessage(), Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CatWalkLogger.error("Request interrupted for %s %s: %s", e, method, url, e.getMessage());
            return new ApiResponse(-1, "Request interrupted: " + e.getMessage(), Map.of());
        }
    }
    
    /**
     * Send an asynchronous HTTP request.
     * 
     * @param url the target URL
     * @param method HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param body request body (can be null for GET/DELETE)
     * @param headers additional headers to include
     * @return CompletableFuture containing the ApiResponse
     */
    public static CompletableFuture<ApiResponse> sendRequestAsync(String url, String method, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .timeout(Duration.ofSeconds(60));
            
            // Add Bearer token if available
            if (bearerToken != null && !bearerToken.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + bearerToken);
            }
            
            // Add custom headers
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }
            
            // Set content type for requests with body
            if (body != null && !body.isEmpty()) {
                requestBuilder.header("Content-Type", "application/json");
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            
            HttpRequest request = requestBuilder.build();
            
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        CatWalkLogger.debug("Async API Request: %s %s -> %d", method, url, response.statusCode());
                        return new ApiResponse(
                                response.statusCode(),
                                response.body(),
                                response.headers().map()
                        );
                    })
                    .exceptionally(throwable -> {
                        CatWalkLogger.error("Async request failed for %s %s: %s", throwable, method, url, throwable.getMessage());
                        return new ApiResponse(-1, "Request failed: " + throwable.getMessage(), Map.of());
                    });
            
        } catch (URISyntaxException e) {
            CatWalkLogger.error("Invalid URL: %s", e, url);
            return CompletableFuture.completedFuture(
                    new ApiResponse(-1, "Invalid URL: " + e.getMessage(), Map.of())
            );
        }
    }
    
    /**
     * Send an asynchronous GET request.
     * 
     * @param url the target URL
     * @return CompletableFuture containing the ApiResponse
     */
    public static CompletableFuture<ApiResponse> sendGetAsync(String url) {
        return sendRequestAsync(url, "GET", null, Map.of());
    }
    
    /**
     * Send an asynchronous POST request.
     * 
     * @param url the target URL
     * @param body the JSON body content
     * @return CompletableFuture containing the ApiResponse
     */
    public static CompletableFuture<ApiResponse> sendPostAsync(String url, String body) {
        return sendRequestAsync(url, "POST", body, Map.of());
    }
}