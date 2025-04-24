package dev.ua.uaproject.catwalk.webhooks.models.events;

import com.google.gson.annotations.Expose;
import dev.ua.uaproject.catwalk.api.v1.models.Player;

public class PlayerKickWebhookEvent extends WebhookEvent {
    @Expose
    Player player;

    @Expose
    String reason;

    public PlayerKickWebhookEvent() {
        eventType = EventType.PlayerKick;
    }

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
