package dev.ua.ikeepcalm.catwalk.common.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * Unified logging utility for CatWalk using Adventure API with consistent formatting
 */
public class CatWalkLogger {
    
    private static final String PREFIX = "[CatWalk] ";
    private static Plugin plugin;
    
    public static void initialize(Plugin pluginInstance) {
        plugin = pluginInstance;
    }
    
    // Info level logging
    public static void info(String message) {
        log(Level.INFO, message, NamedTextColor.GREEN);
    }
    
    public static void info(String message, Object... args) {
        info(String.format(message, args));
    }
    
    // Warning level logging
    public static void warn(String message) {
        log(Level.WARNING, message, NamedTextColor.YELLOW);
    }
    
    public static void warn(String message, Object... args) {
        warn(String.format(message, args));
    }
    
    // Error level logging
    public static void error(String message) {
        log(Level.SEVERE, message, NamedTextColor.RED);
    }
    
    public static void error(String message, Throwable throwable) {
        log(Level.SEVERE, message + " - " + throwable.getMessage(), NamedTextColor.RED);
    }
    
    public static void error(String message, Object... args) {
        error(String.format(message, args));
    }
    
    // Debug level logging (only shows when debug is enabled)
    public static void debug(String message) {
        if (isDebugEnabled()) {
            log(Level.INFO, "[DEBUG] " + message, NamedTextColor.GRAY);
        }
    }
    
    public static void debug(String message, Object... args) {
        debug(String.format(message, args));
    }
    
    // Success logging with special formatting
    public static void success(String message) {
        Component component = Component.text(PREFIX)
                .color(NamedTextColor.GRAY)
                .append(Component.text("✓ " + message)
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true));
        
        Bukkit.getConsoleSender().sendMessage(component);
    }
    
    public static void success(String message, Object... args) {
        success(String.format(message, args));
    }
    
    // Network operation logging
    public static void network(String operation, String details) {
        if (isDebugEnabled()) {
            Component component = Component.text(PREFIX)
                    .color(NamedTextColor.GRAY)
                    .append(Component.text("[NET] ")
                            .color(NamedTextColor.BLUE))
                    .append(Component.text(operation + ": " + details)
                            .color(NamedTextColor.WHITE));
            
            Bukkit.getConsoleSender().sendMessage(component);
        }
    }
    
    private static void log(Level level, String message, NamedTextColor color) {
        Component component = Component.text(PREFIX)
                .color(NamedTextColor.GRAY)
                .append(Component.text(message).color(color));
        
        Bukkit.getConsoleSender().sendMessage(component);
    }
    
    private static boolean isDebugEnabled() {
        return plugin != null && plugin.getConfig().getBoolean("debug", false);
    }
}