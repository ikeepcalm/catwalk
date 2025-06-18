package dev.ua.uaproject.catwalk.common.database.model;

import lombok.Builder;
import lombok.Data;

import java.sql.Timestamp;
import java.util.Map;

@Data
@Builder
public class NetworkResponse {
    private String requestId;
    private String serverId;
    private int statusCode;
    private Map<String, String> headers;
    private String body;
    private String contentType;
    private Integer processedTimeMs;
    private Timestamp createdAt;
}