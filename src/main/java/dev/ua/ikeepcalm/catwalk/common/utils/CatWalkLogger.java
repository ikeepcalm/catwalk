package dev.ua.ikeepcalm.catwalk.common.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

public class CatWalkLogger {

    private static final ComponentLogger LOG = ComponentLogger.logger("CatWalk");
    private static volatile boolean debug;

    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    public static void info(String message, Object... args) {
        LOG.info(Component.text(format(message, args), NamedTextColor.GREEN));
    }

    public static void warn(String message, Object... args) {
        LOG.warn(Component.text(format(message, args), NamedTextColor.YELLOW));
    }

    public static void error(String message, Object... args) {
        LOG.error(Component.text(format(message, args), NamedTextColor.RED));
    }

    public static void error(String message, Throwable throwable, Object... args) {
        LOG.error(Component.text(format(message, args), NamedTextColor.RED), throwable);
    }

    public static void debug(String message, Object... args) {
        if (debug) {
            LOG.info(Component.text("[DEBUG] " + format(message, args), NamedTextColor.GRAY));
        }
    }

    private static String format(String message, Object... args) {
        return args.length == 0 ? message : String.format(message, args);
    }
}
