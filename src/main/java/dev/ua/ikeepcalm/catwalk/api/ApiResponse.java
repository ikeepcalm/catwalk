package dev.ua.ikeepcalm.catwalk.api;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Represents an HTTP API response with status code, body, and headers.
 */
@Getter
public class ApiResponse {

    /**
     * -- GETTER --
     *  Get the HTTP status code.
     *
     */
    private final int statusCode;
    /**
     * -- GETTER --
     *  Get the response body as a string.
     *
     */
    private final String body;
    /**
     * -- GETTER --
     *  Get all response headers.
     *
     */
    private final Map<String, List<String>> headers;
    
    public ApiResponse(int statusCode, String body, Map<String, List<String>> headers) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
    }

    /**
     * Get the first value of a specific header (case-insensitive).
     * 
     * @param headerName the name of the header
     * @return the first header value, or null if not found
     */
    public String getHeader(String headerName) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(headerName)) {
                List<String> values = entry.getValue();
                return values != null && !values.isEmpty() ? values.get(0) : null;
            }
        }
        return null;
    }
    
    /**
     * Check if the response indicates success (status code 2xx).
     * 
     * @return true if the status code is between 200-299
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
    
    /**
     * Check if the response indicates a client error (status code 4xx).
     * 
     * @return true if the status code is between 400-499
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }
    
    /**
     * Check if the response indicates a server error (status code 5xx).
     * 
     * @return true if the status code is between 500-599
     */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }
    
    @Override
    public String toString() {
        return "ApiResponse{" +
                "statusCode=" + statusCode +
                ", bodyLength=" + (body != null ? body.length() : 0) +
                ", headers=" + headers.size() +
                '}';
    }
}