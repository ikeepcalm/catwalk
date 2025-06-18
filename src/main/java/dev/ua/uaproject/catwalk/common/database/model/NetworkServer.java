package dev.ua.uaproject.catwalk.common.database.model;

import lombok.Builder;
import lombok.Data;

import java.sql.Timestamp;
import java.util.Map;

// Network server representation
@Data
@Builder
public class NetworkServer {
    private String serverId;
    private String serverName;
    private ServerType serverType;
    private String host;
    private Integer port;
    private int onlinePlayers;
    private int maxPlayers;
    private Status status;
    private Timestamp lastHeartbeat;
    private Timestamp createdAt;
    private Map<String, Object> metadata;

    public enum ServerType {
        HUB, BACKEND
    }

    public enum Status {
        ONLINE, OFFLINE, MAINTENANCE
    }
}