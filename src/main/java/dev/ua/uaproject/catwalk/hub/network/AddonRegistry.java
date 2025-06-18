package dev.ua.uaproject.catwalk.hub.network;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.hub.network.source.AddonInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class AddonRegistry {
    
    private final CatWalkMain plugin;
    
    @Getter
    private final Map<String, AddonInfo> localAddons = new ConcurrentHashMap<>();
    
    // Store remote addons (discovered from other servers in network)
    private final Map<String, Map<String, AddonInfo>> remoteAddons = new ConcurrentHashMap<>();
    
    public AddonRegistry(CatWalkMain plugin) {
        this.plugin = plugin;
    }
    
    // Called when an addon registers itself locally (existing bridge system)
    public void registerLocalAddon(String addonName, Object handlerInstance) {
        log.info("[AddonRegistry] Registering local addon: {}", addonName);
        
        AddonInfo addonInfo = AddonInfo.fromHandlerInstance(addonName, handlerInstance);
        localAddons.put(addonName, addonInfo);
        
        log.info("[AddonRegistry] Local addon '{}' registered with {} endpoints", 
                addonName, addonInfo.getEndpoints().size());
        
        // If we're part of a network, announce this addon to the hub
        if (plugin.getServerDiscovery() != null && !plugin.isHubMode()) {
            plugin.getServerDiscovery().announceAddonToNetwork(addonInfo);
        }
    }
    
    // Called when hub receives addon announcement from remote server
    public void registerRemoteAddon(String serverId, AddonInfo addonInfo) {
        if (plugin.isHubMode()) {
            log.info("[AddonRegistry] Registering remote addon '{}' from server '{}'", 
                    addonInfo.getName(), serverId);
            
            remoteAddons.computeIfAbsent(serverId, k -> new ConcurrentHashMap<>())
                       .put(addonInfo.getName(), addonInfo);
            
            // Create proxy routes for this remote addon
            if (plugin.getNetworkGateway() != null) {
                plugin.getNetworkGateway().createProxyRoutes(serverId, addonInfo);
            }
        }
    }
    
    // Get all addons from a specific server
    public Map<String, AddonInfo> getServerAddons(String serverId) {
        if (serverId.equals(plugin.getServerId())) {
            return new HashMap<>(localAddons);
        }
        return remoteAddons.getOrDefault(serverId, new HashMap<>());
    }
    
    // Get all addons across the entire network
    public Map<String, Map<String, AddonInfo>> getAllNetworkAddons() {
        Map<String, Map<String, AddonInfo>> allAddons = new HashMap<>(remoteAddons);
        allAddons.put(plugin.getServerId(), new HashMap<>(localAddons));
        return allAddons;
    }
    
    // Get network-wide summary
    public Map<String, Object> getNetworkSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        int totalServers = remoteAddons.size() + (localAddons.isEmpty() ? 0 : 1);
        int totalAddons = localAddons.size() + 
                         remoteAddons.values().stream().mapToInt(Map::size).sum();
        
        summary.put("totalServers", totalServers);
        summary.put("totalAddons", totalAddons);
        summary.put("hubServerId", plugin.getServerId());
        summary.put("servers", getAllNetworkAddons().keySet());
        
        return summary;
    }
}
