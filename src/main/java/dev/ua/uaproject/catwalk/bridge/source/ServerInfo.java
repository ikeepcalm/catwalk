package dev.ua.uaproject.catwalk.bridge.source;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ServerInfo {
    private String id;
    private String name;
    private String type;
    private int onlinePlayers;
    private String status;
    private long lastSeen;

    public ServerInfo(String id, String name, String type, int onlinePlayers) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.onlinePlayers = onlinePlayers;
        this.status = "online";
        this.lastSeen = System.currentTimeMillis();
    }

}