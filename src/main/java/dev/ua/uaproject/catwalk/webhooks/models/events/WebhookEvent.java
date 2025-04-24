package dev.ua.uaproject.catwalk.webhooks.models.events;

import com.google.gson.annotations.Expose;

public class WebhookEvent {
    public enum EventType {PlayerJoin, PlayerQuit, PlayerKick, PlayerChat, PlayerDeath}

    @Expose
    protected EventType eventType;
}
