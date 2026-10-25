package dev.ua.ikeepcalm.catwalk.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class ApiResponse {

    private final int statusCode;
    private final String body;
    private final Map<String, List<String>> headers;

    public String getHeader(String headerName) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(headerName) && !entry.getValue().isEmpty())
                .map(entry -> entry.getValue().getFirst())
                .findFirst()
                .orElse(null);
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    @Override
    public String toString() {
        return "ApiResponse{statusCode=" + statusCode + ", bodyLength=" + (body != null ? body.length() : 0) + ", headers=" + headers.size() + '}';
    }
}
