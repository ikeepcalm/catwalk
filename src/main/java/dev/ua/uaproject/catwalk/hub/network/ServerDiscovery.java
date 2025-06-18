package dev.ua.uaproject.catwalk.hub.network;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.common.utils.Constants;
import dev.ua.uaproject.catwalk.hub.network.source.AddonInfo;
import lombok.extern.slf4j.Slf4j;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.HashMap;

@Slf4j
public class ServerDiscovery {
    
    private final CatWalkMain plugin;
    private BukkitTask announcementTask;
    
    public ServerDiscovery(CatWalkMain plugin) {
        this.plugin = plugin;
        
        // Setup plugin messaging
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Constants.PLUGIN_CHANNEL);
        
        // Start periodic announcements if this is not a hub
        if (!plugin.isHubMode()) {
            startPeriodicAnnouncements();
        }
        
        log.info("[ServerDiscovery] Initialized for server '{}'", plugin.getServerId());
    }
    
    // Announce this server to the network (for non-hub servers)
    public void announceServerToNetwork() {
        if (plugin.isHubMode()) return; // Hubs don't announce themselves
        
        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("serverId", plugin.getServerId());
        serverInfo.put("serverType", "backend");
        serverInfo.put("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
        serverInfo.put("maxPlayers", plugin.getServer().getMaxPlayers());
        serverInfo.put("timestamp", System.currentTimeMillis());

        sendToNetwork("server_announcement", plugin.getServerId(), plugin.getGson().toJson(serverInfo));
        
        log.info("[ServerDiscovery] Announced server '{}' to network", plugin.getServerId());
    }
    
    // Announce an addon to the network (for non-hub servers)
    public void announceAddonToNetwork(AddonInfo addonInfo) {
        if (plugin.isHubMode()) return; // Hubs handle addons locally
        
        sendToNetwork("addon_announcement", plugin.getServerId(), plugin.getGson().toJson(addonInfo));
        
        log.info("[ServerDiscovery] Announced addon '{}' to network", addonInfo.getName());
    }
    
    // Send discovery message to all servers in network
    private void sendToNetwork(String messageType, String serverId, String data) {
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ForwardToAll");
            out.writeUTF("catwalk:network");
            
            ByteArrayDataOutput messageData = ByteStreams.newDataOutput();
            messageData.writeUTF(messageType);
            messageData.writeUTF(serverId);
            messageData.writeUTF(data);
            
            out.writeShort(messageData.toByteArray().length);
            out.write(messageData.toByteArray());
            
            // Send via Velocity to all servers
            Player player = plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
            if (player != null) {
                player.sendPluginMessage(plugin, Constants.PLUGIN_CHANNEL, out.toByteArray());
                log.debug("[ServerDiscovery] Sent {} to network", messageType);
            } else {
                log.warn("[ServerDiscovery] No online players to send network message");
            }
            
        } catch (Exception e) {
            log.error("[ServerDiscovery] Error sending network message", e);
        }
    }
    
    // Start periodic announcements for non-hub servers
    private void startPeriodicAnnouncements() {
        // Announce every 60 seconds
        announcementTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, 
            this::announceServerToNetwork, 
            100L, // 5 second initial delay
            1200L // 60 second interval
        );
        
        log.info("[ServerDiscovery] Started periodic network announcements");
    }
    
    public void shutdown() {
        if (announcementTask != null) {
            announcementTask.cancel();
            announcementTask = null;
        }
        log.info("[ServerDiscovery] Shut down server discovery");
    }
}