package dev.ua.ikeepcalm.catwalk.hub.webserver;

import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Data
public class ApiKey {

    private final String name;
    private final String key;
    private final Set<String> scopes;
    private final boolean master;

    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    public boolean allows(RouteAccess access) {
        return master || !access.getScopes().isEmpty() && scopes.containsAll(access.getScopes());
    }
}
