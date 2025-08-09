package dev.ua.ikeepcalm.catwalk.common.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

public class CatWalkCommand {

    private final CatWalkMain plugin;

    public CatWalkCommand(CatWalkMain plugin) {
        this.plugin = plugin;
        registerCommand();
    }

    private void registerCommand() {
        LifecycleEventManager<org.bukkit.plugin.Plugin> manager = plugin.getLifecycleManager();

        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final CommandDispatcher<CommandSourceStack> dispatcher = event.registrar().getDispatcher();

            // Register the main command with subcommands
            final LiteralCommandNode<CommandSourceStack> catWalkCommand = Commands.literal("catwalk")
                    .requires(source -> source.getSender().hasPermission("catwalk.admin"))
                    .then(
                            Commands.literal("reload")
                                    .executes(this::executeReload)
                    )
                    .then(
                            Commands.literal("info")
                                    .executes(this::executeInfo)
                    )
                    .then(
                            Commands.literal("status")
                                    .executes(this::executeStatus)
                    )
                    .then(
                            Commands.literal("network")
                                    .then(
                                            Commands.literal("status")
                                                    .executes(this::executeNetworkStatus)
                                    )
                                    .then(
                                            Commands.literal("servers")
                                                    .executes(this::executeNetworkServers)
                                    )
                    )
                    .executes(this::executeDefault)
                    .build();

            dispatcher.getRoot().addChild(catWalkCommand);

            plugin.getLogger().info("Registered Brigadier commands successfully");
        });
    }

    // Default command execution (shows help)
    private int executeDefault(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();

        sender.sendMessage(Component.text()
                .append(Component.text("CatWalk Commands", NamedTextColor.BLUE, TextDecoration.BOLD))
                .appendNewline()
                .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                .append(Component.text("/catwalk reload", NamedTextColor.AQUA))
                .append(Component.text(" - Reload the plugin", NamedTextColor.GRAY))
                .appendNewline()
                .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                .append(Component.text("/catwalk info", NamedTextColor.AQUA))
                .append(Component.text(" - Show plugin information", NamedTextColor.GRAY))
                .appendNewline()
                .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                .append(Component.text("/catwalk status", NamedTextColor.AQUA))
                .append(Component.text(" - Show server status", NamedTextColor.GRAY))
                .appendNewline()
                .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                .append(Component.text("/catwalk network status", NamedTextColor.AQUA))
                .append(Component.text(" - Show network status", NamedTextColor.GRAY))
                .build()
        );

        return Command.SINGLE_SUCCESS;
    }

    // Reload command
    private int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();

        sender.sendMessage(Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text("CatWalk", NamedTextColor.BLUE))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Reloading plugin...", NamedTextColor.YELLOW))
                .build()
        );

        try {
            plugin.reload();

            sender.sendMessage(Component.text()
                    .append(Component.text("[", NamedTextColor.DARK_GRAY))
                    .append(Component.text("CatWalk", NamedTextColor.BLUE))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Plugin reloaded successfully!", NamedTextColor.GREEN))
                    .build()
            );

        } catch (Exception e) {
            sender.sendMessage(Component.text()
                    .append(Component.text("[", NamedTextColor.DARK_GRAY))
                    .append(Component.text("CatWalk", NamedTextColor.BLUE))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Failed to reload: " + e.getMessage(), NamedTextColor.RED))
                    .build()
            );
        }

        return Command.SINGLE_SUCCESS;
    }

    // Info command
    private int executeInfo(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();

        String version = plugin.getPluginMeta().getVersion();
        String website = plugin.getPluginMeta().getWebsite();
        java.util.List<String> authors = plugin.getPluginMeta().getAuthors();

        sender.sendMessage(Component.text()
                .append(Component.text("CatWalk Plugin Information", NamedTextColor.BLUE, TextDecoration.BOLD))
                .appendNewline()
                .append(Component.text("Version: ", NamedTextColor.BLUE))
                .append(Component.text(version, NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text("Website: ", NamedTextColor.BLUE))
                .append(Component.text(website != null ? website : "N/A", NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text("Authors: ", NamedTextColor.BLUE))
                .append(Component.text(String.join(", ", authors), NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text("Mode: ", NamedTextColor.BLUE))
                .append(Component.text(plugin.isHubMode() ? "Hub Gateway" : "Regular Server", NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text("Server ID: ", NamedTextColor.BLUE))
                .append(Component.text(plugin.getServerId(), NamedTextColor.AQUA))
                .build()
        );

        return Command.SINGLE_SUCCESS;
    }

    // Status command
    private int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();

        // Gather server status information
        boolean webServerRunning = plugin.getWebServer() != null;
        int port = plugin.getConfig().getInt("port", 4567);
        int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
        int maxPlayers = plugin.getServer().getMaxPlayers();

        sender.sendMessage(Component.text()
                .append(Component.text("CatWalk Server Status", NamedTextColor.BLUE, TextDecoration.BOLD))
                .appendNewline()
                .append(Component.text("Web Server: ", NamedTextColor.BLUE))
                .append(Component.text(webServerRunning ? "Running" : "Stopped",
                        webServerRunning ? NamedTextColor.GREEN : NamedTextColor.RED))
                .appendNewline()
                .append(Component.text("API Port: ", NamedTextColor.BLUE))
                .append(Component.text(String.valueOf(port), NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text("Players: ", NamedTextColor.BLUE))
                .append(Component.text(onlinePlayers + "/" + maxPlayers, NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text("Mode: ", NamedTextColor.BLUE))
                .append(Component.text(plugin.isHubMode() ? "Hub Gateway" : "Backend Server", NamedTextColor.AQUA))
                .build()
        );

        return Command.SINGLE_SUCCESS;
    }

    // Network status command
    private int executeNetworkStatus(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();

        if (!plugin.isHubMode()) {
            sender.sendMessage(Component.text()
                    .append(Component.text("[", NamedTextColor.DARK_GRAY))
                    .append(Component.text("CatWalk", NamedTextColor.BLUE))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Network commands are only available on hub servers", NamedTextColor.RED))
                    .build()
            );
            return Command.SINGLE_SUCCESS;
        }

        // Show network status (if hub gateway is implemented)
        sender.sendMessage(Component.text()
                .append(Component.text("Network Status", NamedTextColor.BLUE, TextDecoration.BOLD))
                .appendNewline()
                .append(Component.text("Hub Server: ", NamedTextColor.BLUE))
                .append(Component.text(plugin.getServerId(), NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text("Network Mode: ", NamedTextColor.BLUE))
                .append(Component.text("Active", NamedTextColor.GREEN))
                .appendNewline()
                .append(Component.text("Use '/catwalk network servers' for detailed server list", NamedTextColor.GRAY))
                .build()
        );

        return Command.SINGLE_SUCCESS;
    }

    // Network servers command
    private int executeNetworkServers(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();

        if (!plugin.isHubMode()) {
            sender.sendMessage(Component.text()
                    .append(Component.text("[", NamedTextColor.DARK_GRAY))
                    .append(Component.text("CatWalk", NamedTextColor.BLUE))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Network commands are only available on hub servers", NamedTextColor.RED))
                    .build()
            );
            return Command.SINGLE_SUCCESS;
        }

        // Show network servers (when hub gateway is implemented)
        sender.sendMessage(Component.text()
                .append(Component.text("Network Servers", NamedTextColor.BLUE, TextDecoration.BOLD))
                .appendNewline()
                .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                .append(Component.text(plugin.getServerId() + " (Hub)", NamedTextColor.GREEN))
                .appendNewline()
                .append(Component.text("Use the web API at /v1/network/servers for detailed information", NamedTextColor.GRAY))
                .build()
        );

        return Command.SINGLE_SUCCESS;
    }
}