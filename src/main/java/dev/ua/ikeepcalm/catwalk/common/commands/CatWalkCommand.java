package dev.ua.ikeepcalm.catwalk.common.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkServer;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.hub.webserver.CustomOpenApiGenerator.RouteInfo;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

import java.util.Comparator;
import java.util.List;

public class CatWalkCommand {

    private final CatWalkMain plugin;

    public CatWalkCommand(CatWalkMain plugin) {
        this.plugin = plugin;
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("catwalk")
                        .requires(source -> source.getSender().hasPermission("catwalk.admin"))
                        .executes(this::help)
                        .then(Commands.literal("reload").executes(this::reload))
                        .then(Commands.literal("info").executes(this::info))
                        .then(Commands.literal("status").executes(this::status))
                        .then(Commands.literal("endpoints").executes(this::endpoints))
                        .then(Commands.literal("network")
                                .then(Commands.literal("status").executes(this::networkStatus))
                                .then(Commands.literal("servers").executes(this::networkServers)))
                        .build(),
                "CatWalk REST API administration"
        ));
    }

    private int help(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        sender.sendMessage(title("CatWalk Commands"));
        helpLine(sender, "/catwalk reload", "Reload the configuration and web server");
        helpLine(sender, "/catwalk info", "Show plugin information");
        helpLine(sender, "/catwalk status", "Show web server status");
        helpLine(sender, "/catwalk endpoints", "List all registered API endpoints");
        helpLine(sender, "/catwalk network status", "Show network status");
        helpLine(sender, "/catwalk network servers", "List online network servers");
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        try {
            plugin.reload();
            sender.sendMessage(prefixed("Reloaded.", NamedTextColor.GREEN));
        } catch (RuntimeException e) {
            CatWalkLogger.error("Reload failed", e);
            sender.sendMessage(prefixed("Reload failed: " + e.getMessage(), NamedTextColor.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        sender.sendMessage(title("CatWalk Plugin Information"));
        field(sender, "Version", plugin.getPluginMeta().getVersion());
        field(sender, "Authors", String.join(", ", plugin.getPluginMeta().getAuthors()));
        field(sender, "Mode", plugin.getModeName());
        field(sender, "Server ID", plugin.getServerId());
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        boolean running = plugin.getWebServer() != null;
        sender.sendMessage(title("CatWalk Server Status"));
        sender.sendMessage(Component.text("Web Server: ", NamedTextColor.BLUE)
                .append(Component.text(running ? "Running" : "Stopped", running ? NamedTextColor.GREEN : NamedTextColor.RED)));
        field(sender, "API Port", String.valueOf(plugin.getConfig().getInt("port")));
        field(sender, "Players", plugin.getServer().getOnlinePlayers().size() + "/" + plugin.getServer().getMaxPlayers());
        field(sender, "Mode", plugin.getModeName());
        return Command.SINGLE_SUCCESS;
    }

    private int endpoints(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        WebServer webServer = plugin.getWebServer();
        if (webServer == null) {
            sender.sendMessage(prefixed("Web server is not running", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        List<RouteInfo> routes = webServer.getOpenApiGenerator().getRegisteredRoutes().stream()
                .sorted(Comparator.comparing(RouteInfo::getPath).thenComparing(route -> route.getMethod().name()))
                .toList();
        sender.sendMessage(title("Registered CatWalk Endpoints ").append(Component.text("(" + routes.size() + ")", NamedTextColor.GRAY)));

        for (RouteInfo route : routes) {
            boolean authRequired = webServer.requiresAuthentication(route.getPath()) || Boolean.TRUE.equals(route.getRequiresAuth());
            String origin = route.getPluginName() != null ? route.getPluginName() : "core";
            sender.sendMessage(Component.text(String.format("%-6s ", route.getMethod().name()), NamedTextColor.AQUA)
                    .append(Component.text(route.getPath(), NamedTextColor.WHITE))
                    .append(Component.text("  [" + origin + "]", NamedTextColor.GRAY))
                    .append(Component.text(authRequired ? "  (auth required)" : "  (public)", authRequired ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int networkStatus(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (plugin.getNetworkRegistry() == null) {
            sender.sendMessage(prefixed("Network commands are not available in standalone mode", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getNetworkRegistry().getAllServers().whenComplete((servers, error) -> {
            if (error != null) {
                sender.sendMessage(prefixed("Failed to read network status: " + error.getMessage(), NamedTextColor.RED));
                return;
            }
            sender.sendMessage(title("Network Status"));
            field(sender, "This server", plugin.getServerId() + " (" + plugin.getModeName() + ")");
            field(sender, "Online servers", String.valueOf(servers.size()));
            field(sender, "Online players", String.valueOf(servers.stream().mapToInt(NetworkServer::getOnlinePlayers).sum()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int networkServers(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (plugin.getNetworkRegistry() == null) {
            sender.sendMessage(prefixed("Network commands are not available in standalone mode", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getNetworkRegistry().getAllServers().whenComplete((servers, error) -> {
            if (error != null) {
                sender.sendMessage(prefixed("Failed to read network servers: " + error.getMessage(), NamedTextColor.RED));
                return;
            }
            sender.sendMessage(title("Network Servers ").append(Component.text("(" + servers.size() + ")", NamedTextColor.GRAY)));
            for (NetworkServer server : servers) {
                sender.sendMessage(Component.text("- ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(server.getServerId(), NamedTextColor.GREEN))
                        .append(Component.text(" " + server.getServerType().name().toLowerCase(), NamedTextColor.GRAY))
                        .append(Component.text("  " + server.getOnlinePlayers() + "/" + server.getMaxPlayers() + " players", NamedTextColor.AQUA)));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static Component title(String text) {
        return Component.text(text, NamedTextColor.BLUE, TextDecoration.BOLD);
    }

    private static Component prefixed(String message, NamedTextColor color) {
        return Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("CatWalk", NamedTextColor.BLUE))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, color));
    }

    private static void field(CommandSender sender, String name, String value) {
        sender.sendMessage(Component.text(name + ": ", NamedTextColor.BLUE).append(Component.text(value, NamedTextColor.AQUA)));
    }

    private static void helpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text("- ", NamedTextColor.DARK_GRAY)
                .append(Component.text(command, NamedTextColor.AQUA))
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }
}
