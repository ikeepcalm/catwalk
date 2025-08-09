package dev.ua.ikeepcalm.catwalk.common.database.model;

import lombok.Builder;
import lombok.Data;

import java.sql.Timestamp;
import java.util.Map;

@Data
@Builder
public class NetworkRequest {
    private String requestId;
    private String targetServerId;
    private String endpointPath;
    private HttpMethod httpMethod;
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private String body;
    private Timestamp createdAt;
    private Timestamp processedAt;
    private RequestStatus status;
    private int priority;
    private int timeoutSeconds;
    private int retryCount;
    private int maxRetries;
    
    public enum RequestStatus {
        PENDING, PROCESSING, COMPLETED, FAILED, TIMEOUT
    }
    
    public enum HttpMethod {
        GET, POST, PUT, DELETE, PATCH
    }
}