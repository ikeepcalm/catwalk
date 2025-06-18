package dev.ua.uaproject.catwalk.hub.api.stats;

import dev.ua.uaproject.catwalk.CatWalkMain;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class StatsManager {
    private final CatWalkMain plugin;
    private final Logger logger;
    private final Path dataFolder;
    private final ZoneId serverTimeZone;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Store player online data: { timestamp -> playerCount }
    private final Map<Long, Integer> onlinePlayerHistory = new ConcurrentHashMap<>();

    // Store hourly distribution data: { hour -> { date -> playerCount } }
    private final Map<String, Map<String, Integer>> hourlyDistribution = new ConcurrentHashMap<>();

    // Store player playtime data: { uuid -> playtimeMillis }
    private final Map<UUID, Long> playerPlaytimes = new ConcurrentHashMap<>();

    // Track session start times for online players: { uuid -> sessionStartTime }
    private final Map<UUID, Long> playerSessions = new ConcurrentHashMap<>();

    private BukkitTask collectionTask;

    public StatsManager(CatWalkMain plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dataFolder = new File(plugin.getDataFolder(), "stats").toPath();
        this.serverTimeZone = ZoneId.systemDefault();

        // Create data directory if it doesn't exist
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to create stats directory", e);
        }

        // Load existing data
        loadData();

        // Start collection task (every 5 minutes = 20 ticks/second * 60 seconds * 5 minutes)
        startCollectionTask();
    }

    public void startCollectionTask() {
        if (collectionTask != null) {
            collectionTask.cancel();
        }

        collectionTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::collectAndSaveData, 20L, 20L * 60L * 10L);
    }

    public void stop() {
        if (collectionTask != null) {
            collectionTask.cancel();
            collectionTask = null;
        }

        // Save data on stop
        saveData();
    }

    // Called when a player joins
    public void handlePlayerJoin(UUID playerUuid) {
        playerSessions.put(playerUuid, System.currentTimeMillis());
    }

    // Called when a player quits
    public void handlePlayerQuit(UUID playerUuid) {
        long sessionStart = playerSessions.getOrDefault(playerUuid, System.currentTimeMillis());
        long sessionDuration = System.currentTimeMillis() - sessionStart;

        // Update playtime
        playerPlaytimes.put(playerUuid, playerPlaytimes.getOrDefault(playerUuid, 0L) + sessionDuration);

        // Remove from active sessions
        playerSessions.remove(playerUuid);

        // Save updated playtime
        savePlayerPlaytimes();
    }

    private void collectAndSaveData() {
        long timestamp = System.currentTimeMillis();


        int onlineCount = Bukkit.getOnlinePlayers().size();

        // Record current online player count
        onlinePlayerHistory.put(timestamp, onlineCount);

        // Update hourly distribution
        LocalDateTime now = LocalDateTime.now(serverTimeZone);
        String hour = now.format(timeFormatter);
        String date = now.format(dateFormatter);

        // Skip saving data if the minute is 00
        if (now.getMinute() == 0) {
            return;
        }

        hourlyDistribution.computeIfAbsent(hour, k -> new HashMap<>())
                .put(date, onlineCount);

        // Save data to files
        saveOnlinePlayerHistory();
        saveHourlyDistribution();
    }

    private void loadData() {
        loadOnlinePlayerHistory();
        loadHourlyDistribution();
        loadPlayerPlaytimes();
    }

    private void saveData() {
        saveOnlinePlayerHistory();
        saveHourlyDistribution();
        savePlayerPlaytimes();
    }

    // Online player history methods
    private void loadOnlinePlayerHistory() {
        Path historyFile = dataFolder.resolve("online_history.csv");

        if (Files.exists(historyFile)) {
            try {
                List<String> lines = Files.readAllLines(historyFile);
                for (String line : lines) {
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        try {
                            long timestamp = Long.parseLong(parts[0]);
                            int count = Integer.parseInt(parts[1]);
                            onlinePlayerHistory.put(timestamp, count);
                        } catch (NumberFormatException e) {
                            logger.warning("Invalid data format in online history: " + line);
                        }
                    }
                }

                logger.info("Loaded " + onlinePlayerHistory.size() + " online history records");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to load online player history", e);
            }
        }
    }

    private void saveOnlinePlayerHistory() {
        Path historyFile = dataFolder.resolve("online_history.csv");

        try {
            // Keep only the last 14 days of data (maximum that the API returns)
            long cutoffTime = System.currentTimeMillis() - (14 * 24 * 60 * 60 * 1000);
            List<String> lines = onlinePlayerHistory.entrySet().stream()
                    .filter(entry -> entry.getKey() >= cutoffTime)
                    .map(entry -> entry.getKey() + "," + entry.getValue())
                    .collect(Collectors.toList());

            Files.write(historyFile, lines);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save online player history", e);
        }
    }

    // Hourly distribution methods
    private void loadHourlyDistribution() {
        Path distributionFile = dataFolder.resolve("hourly_distribution.csv");

        if (Files.exists(distributionFile)) {
            try {
                List<String> lines = Files.readAllLines(distributionFile);
                for (String line : lines) {
                    String[] parts = line.split(",");
                    if (parts.length == 3) {
                        String hour = parts[0];
                        String date = parts[1];
                        try {
                            int count = Integer.parseInt(parts[2]);
                            hourlyDistribution.computeIfAbsent(hour, k -> new HashMap<>())
                                    .put(date, count);
                        } catch (NumberFormatException e) {
                            logger.warning("Invalid data format in hourly distribution: " + line);
                        }
                    }
                }

                logger.info("Loaded hourly distribution data");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to load hourly distribution", e);
            }
        }
    }

    private void saveHourlyDistribution() {
        Path distributionFile = dataFolder.resolve("hourly_distribution.csv");

        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Map<String, Integer>> hourEntry : hourlyDistribution.entrySet()) {
                String hour = hourEntry.getKey();
                for (Map.Entry<String, Integer> dateEntry : hourEntry.getValue().entrySet()) {
                    String date = dateEntry.getKey();
                    int count = dateEntry.getValue();
                    lines.add(hour + "," + date + "," + count);
                }
            }

            Files.write(distributionFile, lines);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save hourly distribution", e);
        }
    }

    // Player playtime methods
    private void loadPlayerPlaytimes() {
        Path playtimesFile = dataFolder.resolve("player_playtimes.csv");

        if (Files.exists(playtimesFile)) {
            try {
                List<String> lines = Files.readAllLines(playtimesFile);
                for (String line : lines) {
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        try {
                            UUID uuid = UUID.fromString(parts[0]);
                            long playtime = Long.parseLong(parts[1]);
                            playerPlaytimes.put(uuid, playtime);
                        } catch (IllegalArgumentException e) {
                            logger.warning("Invalid data format in player playtimes: " + line);
                        }
                    }
                }

                logger.info("Loaded " + playerPlaytimes.size() + " player playtime records");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to load player playtimes", e);
            }
        }
    }

    private void savePlayerPlaytimes() {
        Path playtimesFile = dataFolder.resolve("player_playtimes.csv");

        try {
            List<String> lines = playerPlaytimes.entrySet().stream()
                    .map(entry -> entry.getKey() + "," + entry.getValue())
                    .collect(Collectors.toList());

            Files.write(playtimesFile, lines);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save player playtimes", e);
        }
    }

    // API methods used by StatsApi
    public Map<String, Object> getStatsSummary() {
        Map<String, Object> summary = new HashMap<>();

        // Get total players
        int totalPlayers = Bukkit.getOfflinePlayers().length;
        summary.put("totalPlayers", totalPlayers);

        // Get online players
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        summary.put("onlinePlayers", onlinePlayers);

        // Get new players (joined in the last 24 hours)
        long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
        int newPlayers = countNewPlayers(oneDayAgo);
        summary.put("newPlayers", newPlayers);

        // Get average playtime in milliseconds
        long totalPlaytime = calculateTotalPlaytime();
        long avgPlaytime = totalPlayers > 0 ? totalPlaytime / totalPlayers : 0;
        summary.put("avgPlaytime", avgPlaytime);

        return summary;
    }

    public List<Map<String, Object>> getOnlinePlayersData(int days) {
        List<Map<String, Object>> data = new ArrayList<>();
        long cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000);

        // Convert stored history to the format needed by the API
        for (Map.Entry<Long, Integer> entry : onlinePlayerHistory.entrySet()) {
            long timestamp = entry.getKey();
            int playerCount = entry.getValue();

            if (timestamp >= cutoffTime) {
                LocalDateTime dateTime = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(timestamp), serverTimeZone);

                Map<String, Object> point = new HashMap<>();
                point.put("timestamp", timestamp);
                point.put("online", playerCount);
                point.put("hour", dateTime.getHour());
                point.put("day", dateTime.getDayOfWeek().toString());

                data.add(point);
            }
        }

        // Sort by timestamp
        data.sort(Comparator.comparing(m -> (Long) m.get("timestamp")));

        return data;
    }

    public Map<String, Integer> getCurrentHourlyDistribution() {
        Map<String, Integer> result = new TreeMap<>();

        // Initialize all hours
        for (int i = 0; i < 24; i++) {
            LocalDateTime time = LocalDateTime.now().withHour(i).withMinute(0);
            String hourStr = time.format(timeFormatter);
            result.put(hourStr, 0);
        }

        // Get today's date
        String today = LocalDateTime.now().format(dateFormatter);

        // Fill in data for today
        for (Map.Entry<String, Map<String, Integer>> entry : hourlyDistribution.entrySet()) {
            String hour = entry.getKey();
            Map<String, Integer> dateCounts = entry.getValue();

            if (dateCounts.containsKey(today)) {
                result.put(hour, dateCounts.get(today));
            }
        }

        // Add current hour
        LocalDateTime now = LocalDateTime.now();
        String currentHour = now.format(timeFormatter);
        result.put(currentHour, Bukkit.getOnlinePlayers().size());

        return result;
    }

    public List<Map<String, Object>> getTopPlayers(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Combine stored playtimes with current session durations
        Map<UUID, Long> combinedPlaytimes = new HashMap<>(playerPlaytimes);

        // Add current sessions for online players
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : playerSessions.entrySet()) {
            UUID uuid = entry.getKey();
            long sessionStart = entry.getValue();
            long sessionDuration = currentTime - sessionStart;

            combinedPlaytimes.put(uuid, combinedPlaytimes.getOrDefault(uuid, 0L) + sessionDuration);
        }

        // Sort by playtime (descending)
        List<Map.Entry<UUID, Long>> sortedPlayers = new ArrayList<>(combinedPlaytimes.entrySet());
        sortedPlayers.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());

        // Build result list
        int count = 0;
        for (Map.Entry<UUID, Long> entry : sortedPlayers) {
            if (count >= limit) break;

            UUID uuid = entry.getKey();
            long playtime = entry.getValue();

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String name = offlinePlayer.getName();

            if (name == null) continue;

            Map<String, Object> playerData = new HashMap<>();
            playerData.put("name", name);
            playerData.put("uuid", uuid.toString());
            playerData.put("playtime", playtime);
            playerData.put("online", offlinePlayer.isOnline());

            // For online players, add more detail
            if (offlinePlayer.isOnline()) {
                Player player = offlinePlayer.getPlayer();
                if (player != null) {
                    playerData.put("level", player.getLevel());
                    playerData.put("health", player.getHealth());
                }
            }

            result.add(playerData);
            count++;
        }

        return result;
    }

    private int countNewPlayers(long since) {
        int count = 0;
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getFirstPlayed() >= since) {
                count++;
            }
        }
        return count;
    }

    private long calculateTotalPlaytime() {
        // Combine stored playtimes with current session durations
        Map<UUID, Long> combinedPlaytimes = new HashMap<>(playerPlaytimes);

        // Add current sessions for online players
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : playerSessions.entrySet()) {
            UUID uuid = entry.getKey();
            long sessionStart = entry.getValue();
            long sessionDuration = currentTime - sessionStart;

            combinedPlaytimes.put(uuid, combinedPlaytimes.getOrDefault(uuid, 0L) + sessionDuration);
        }

        // Sum all playtimes
        return combinedPlaytimes.values().stream().mapToLong(Long::longValue).sum();
    }
}