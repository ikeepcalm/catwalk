package dev.ua.ikeepcalm.catwalk.common.database.model;

import lombok.Builder;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class ServerAddon {
    private String serverId;
    private String addonName;
    private String addonVersion;
    private boolean enabled;
    private List<EndpointDefinition> endpoints;
    private Map<String, Object> openApiSpec;
    private Timestamp registeredAt;
    private Timestamp updatedAt;
}