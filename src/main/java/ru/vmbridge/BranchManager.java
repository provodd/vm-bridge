package ru.vmbridge;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * In-memory cache of shop-to-branch assignments.
 *
 * Primary storage: MySQL (vm_shops table).
 * Fallback:        plugins/VMBridge/data.yml  (used only if MySQL is unavailable at startup).
 * Backup:          data.yml is always written on shutdown and on every change,
 *                  so data is never lost if MySQL goes down unexpectedly.
 */
public class BranchManager {

    public static final Set<String> VALID_BRANCHES = Set.of("green", "blue", "pink", "orange");

    private final VMBridge plugin;
    private final File dataFile;

    /** UUID → full assignment info. ConcurrentHashMap for safe cross-thread reads. */
    private final ConcurrentHashMap<UUID, ShopAssignment> assignments = new ConcurrentHashMap<>();

    /** branch → resolved clan tag. Updated once per day (or on demand). */
    private final ConcurrentHashMap<String, String> ownerCache = new ConcurrentHashMap<>();

    public BranchManager(VMBridge plugin) {
        this.plugin = plugin;
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    // ------------------------------------------------------------------ //
    //  Load / save                                                         //
    // ------------------------------------------------------------------ //

    public void load() {
        assignments.clear();

        if (plugin.getDb() != null && plugin.getDb().isConnected()) {
            assignments.putAll(plugin.getDb().loadAllShops());
        } else {
            plugin.getLogger().warning("[BranchManager] MySQL not available — loading from data.yml fallback.");
            loadFromYaml();
        }
    }

    /** Called on server shutdown as a safety backup. */
    public void save() {
        saveToYaml();
    }

    // ------------------------------------------------------------------ //
    //  Assignment operations                                               //
    // ------------------------------------------------------------------ //

    public void setAssignment(ShopAssignment assignment) {
        assignments.put(assignment.shopUuid(), assignment);

        if (plugin.getDb() != null && plugin.getDb().isConnected()) {
            plugin.getDb().upsertShop(assignment);
        }
        saveToYaml(); // always keep YAML in sync as backup
    }

    public void removeAssignment(UUID shopUuid) {
        assignments.remove(shopUuid);

        if (plugin.getDb() != null && plugin.getDb().isConnected()) {
            plugin.getDb().removeShop(shopUuid);
        }
        saveToYaml();
    }

    // ------------------------------------------------------------------ //
    //  Lookups                                                             //
    // ------------------------------------------------------------------ //

    /** Returns the branch name for this entity UUID, or null if not assigned. */
    public String getBranch(UUID shopUuid) {
        ShopAssignment a = assignments.get(shopUuid);
        return a == null ? null : a.branch();
    }

    public ShopAssignment getAssignment(UUID shopUuid) {
        return assignments.get(shopUuid);
    }

    public boolean isAssigned(UUID shopUuid) {
        return assignments.containsKey(shopUuid);
    }

    public Map<UUID, ShopAssignment> getAllAssignments() {
        return Map.copyOf(assignments);
    }

    // ------------------------------------------------------------------ //
    //  Config helpers                                                      //
    // ------------------------------------------------------------------ //

    public String getBranchOwnerPlaceholder(String branch) {
        return plugin.getConfig().getString(
                "branch-owner-placeholder." + branch,
                "%simplesiege_" + branch + "_owner%");
    }

    public double getTaxRate(String branch) {
        return plugin.getConfig().getDouble("tax-rate." + branch, 0.05);
    }

    public boolean isValidBranch(String branch) {
        return VALID_BRANCHES.contains(branch.toLowerCase());
    }

    public String getBranchOwnerUrl(String branch) {
        return plugin.getConfig().getString("branch-owner-url." + branch, "");
    }

    // ------------------------------------------------------------------ //
    //  Owner cache                                                         //
    // ------------------------------------------------------------------ //

    /** Returns the currently cached clan tag for this branch, or null if not yet resolved. */
    public String getCachedOwner(String branch) {
        return ownerCache.get(branch);
    }

    /**
     * Triggers an async cache refresh. Safe to call from any thread.
     * For URL-based branches: performs HTTP GET on the async thread.
     * For PAPI-based branches: dispatches to the main thread for resolution.
     */
    public void refreshOwnerCache() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::refreshNow);
    }

    /**
     * On startup: immediately refreshes all branches, then schedules a per-branch
     * timer according to {@code branch-cache-schedule.<branch>.schedule/time}.
     */
    public void scheduleAndRefresh() {
        for (String branch : VALID_BRANCHES) {
            scheduleForBranch(branch);
        }
    }

    private void scheduleForBranch(String branch) {
        String base     = "branch-cache-schedule." + branch;
        String schedule = plugin.getConfig().getString(base + ".schedule", "daily");
        String timeStr  = plugin.getConfig().getString(base + ".time", "09:00");

        LocalTime targetTime = parseTime(timeStr, branch);

        long delayTicks;
        long periodTicks;

        if (schedule.equalsIgnoreCase("daily")) {
            LocalTime now = LocalTime.now();
            long secondsUntil = now.isBefore(targetTime)
                    ? Duration.between(now, targetTime).getSeconds()
                    : Duration.between(now, targetTime).getSeconds() + 86400L;
            delayTicks  = secondsUntil * 20L;
            periodTicks = 24L * 60L * 60L * 20L;
            // daily — обновляем сразу при старте, не ждём нужного времени
            plugin.getServer().getScheduler()
                    .runTaskAsynchronously(plugin, () -> refreshBranch(branch));
        } else {
            DayOfWeek targetDay = parseDayOfWeek(schedule);
            if (targetDay == null) {
                plugin.getLogger().warning("[VMBridge Cache] Неизвестный schedule для ветки '"
                        + branch + "': '" + schedule + "'. Используется daily.");
                scheduleForBranchDaily(branch, targetTime);
                return;
            }
            LocalDateTime now  = LocalDateTime.now();
            LocalDateTime next = nextOccurrence(now, targetDay, targetTime);
            delayTicks  = Duration.between(now, next).getSeconds() * 20L;
            periodTicks = 7L * 24L * 60L * 60L * 20L;
            // конкретный день — при старте обновляем только если сегодня тот самый день
            // и время уже наступило (иначе ждём таймера)
            if (now.getDayOfWeek() == targetDay && !LocalTime.now().isBefore(targetTime)) {
                plugin.getServer().getScheduler()
                        .runTaskAsynchronously(plugin, () -> refreshBranch(branch));
            }
        }

        plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, () -> refreshBranch(branch), delayTicks, periodTicks);

        plugin.getLogger().info("[VMBridge Cache] Ветка '" + branch
                + "': обновление " + schedule + " в " + timeStr + ".");
    }

    private void scheduleForBranchDaily(String branch, LocalTime targetTime) {
        LocalTime now = LocalTime.now();
        long secondsUntil = now.isBefore(targetTime)
                ? Duration.between(now, targetTime).getSeconds()
                : Duration.between(now, targetTime).getSeconds() + 86400L;
        long delayTicks  = secondsUntil * 20L;
        long periodTicks = 24L * 60L * 60L * 20L;
        plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, () -> refreshBranch(branch), delayTicks, periodTicks);
    }

    // ------------------------------------------------------------------ //
    //  Cache internals                                                     //
    // ------------------------------------------------------------------ //

    /** Refreshes all branches. Must be called from an async thread. */
    private void refreshNow() {
        for (String branch : VALID_BRANCHES) {
            refreshBranch(branch);
        }
    }

    /** Refreshes a single branch. Must be called from an async thread. */
    private void refreshBranch(String branch) {
        String url = getBranchOwnerUrl(branch);
        if (url != null && !url.isBlank()) {
            fetchFromUrl(branch, url);
        } else {
            String placeholder = getBranchOwnerPlaceholder(branch);
            if (placeholder != null && !placeholder.isBlank() && plugin.isPapiEnabled()) {
                fetchFromPapi(branch, placeholder);
            }
        }
    }

    private static final int URL_MAX_ATTEMPTS = 3;
    private static final int URL_RETRY_DELAY_MS = 2000;

    private void fetchFromUrl(String branch, String urlStr) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= URL_MAX_ATTEMPTS; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");

                int code = conn.getResponseCode();
                if (code != 200) {
                    plugin.getLogger().warning("[VMBridge Cache] Ветка '" + branch
                            + "', попытка " + attempt + "/" + URL_MAX_ATTEMPTS
                            + ": HTTP " + code + ".");
                    retryDelay(branch, attempt);
                    continue;
                }

                String body;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    body = reader.lines().collect(Collectors.joining()).trim();
                }

                String clanTag = parseResponse(body);
                if (clanTag == null || clanTag.isBlank()) {
                    plugin.getLogger().warning("[VMBridge Cache] Ветка '" + branch
                            + "', попытка " + attempt + "/" + URL_MAX_ATTEMPTS
                            + ": пустой или нераспознанный ответ: " + body);
                    retryDelay(branch, attempt);
                    continue;
                }

                ownerCache.put(branch, clanTag);
                plugin.getLogger().info("[VMBridge Cache] Ветка '" + branch
                        + "' → клан '" + clanTag + "' (попытка " + attempt + ", URL).");
                return;

            } catch (Exception e) {
                lastException = e;
                plugin.getLogger().warning("[VMBridge Cache] Ветка '" + branch
                        + "', попытка " + attempt + "/" + URL_MAX_ATTEMPTS
                        + ": " + e.getMessage());
                retryDelay(branch, attempt);
            }
        }

        plugin.getLogger().severe("[VMBridge Cache] Ветка '" + branch
                + "': все " + URL_MAX_ATTEMPTS + " попытки неудачны."
                + (lastException != null ? " Последняя ошибка: " + lastException.getMessage() : ""));
    }

    private void retryDelay(String branch, int attempt) {
        if (attempt < URL_MAX_ATTEMPTS) {
            try {
                Thread.sleep(URL_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Parses plain text or JSON {@code {"clan": "tag"}} responses.
     * Returns the clan tag, or null if the response could not be parsed.
     */
    private String parseResponse(String body) {
        if (body == null || body.isBlank()) return null;

        if (body.startsWith("{")) {
            // minimal JSON parse — no external dependencies
            int keyIdx = body.indexOf("\"clan\"");
            if (keyIdx == -1) return null;
            int colon = body.indexOf(':', keyIdx + 6);
            if (colon == -1) return null;
            int open  = body.indexOf('"', colon + 1);
            if (open == -1) return null;
            int close = body.indexOf('"', open + 1);
            if (close == -1) return null;
            String tag = body.substring(open + 1, close).trim();
            return tag.isBlank() ? null : tag;
        }

        // plain text
        return body.trim();
    }

    private void fetchFromPapi(String branch, String placeholder) {
        // PlaceholderAPI must be called on the main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                String clanTag = PlaceholderAPI.setPlaceholders(null, placeholder);
                if (clanTag != null && !clanTag.isBlank() && !clanTag.equals(placeholder)) {
                    ownerCache.put(branch, clanTag.trim());
                    plugin.getLogger().info("[VMBridge Cache] " + branch
                            + " → " + clanTag.trim() + " (PAPI)");
                } else {
                    plugin.getLogger().warning("[VMBridge Cache] Плейсхолдер '"
                            + placeholder + "' для ветки '" + branch + "' не разрешён.");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[VMBridge Cache] PAPI ошибка для ветки '"
                        + branch + "': " + e.getMessage());
            }
        });
    }

    private LocalTime parseTime(String timeStr, String branch) {
        try {
            String[] parts = timeStr.split(":");
            int hour   = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return LocalTime.of(hour, minute);
        } catch (Exception e) {
            plugin.getLogger().warning("[VMBridge Cache] Неверный формат time для ветки '"
                    + branch + "': '" + timeStr + "'. Используется 09:00.");
            return LocalTime.of(9, 0);
        }
    }

    private DayOfWeek parseDayOfWeek(String s) {
        return switch (s.toLowerCase()) {
            case "monday"    -> DayOfWeek.MONDAY;
            case "tuesday"   -> DayOfWeek.TUESDAY;
            case "wednesday" -> DayOfWeek.WEDNESDAY;
            case "thursday"  -> DayOfWeek.THURSDAY;
            case "friday"    -> DayOfWeek.FRIDAY;
            case "saturday"  -> DayOfWeek.SATURDAY;
            case "sunday"    -> DayOfWeek.SUNDAY;
            default          -> null;
        };
    }

    private LocalDateTime nextOccurrence(LocalDateTime from, DayOfWeek targetDay, LocalTime targetTime) {
        LocalDateTime candidate = from.with(targetDay).withHour(targetTime.getHour())
                .withMinute(targetTime.getMinute()).withSecond(0).withNano(0);
        if (!candidate.isAfter(from)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    // ------------------------------------------------------------------ //
    //  YAML fallback                                                       //
    // ------------------------------------------------------------------ //

    private void loadFromYaml() {
        if (!dataFile.exists()) return;

        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        if (!data.isConfigurationSection("shops")) return;

        int count = 0;
        for (String key : data.getConfigurationSection("shops").getKeys(false)) {
            try {
                UUID uuid     = UUID.fromString(key);
                String branch = data.getString("shops." + key + ".branch", "");
                if (branch.isBlank()) continue;

                assignments.put(uuid, new ShopAssignment(
                        uuid,
                        data.getString("shops." + key + ".shop_name", "?"),
                        branch,
                        data.getString("shops." + key + ".owner_uuid", null),
                        data.getString("shops." + key + ".owner_name", null),
                        data.getString("shops." + key + ".assigned_by", "unknown"),
                        LocalDateTime.now() // assigned_at not stored in old format — use now
                ));
                count++;
            } catch (IllegalArgumentException ignored) {}
        }
        plugin.getLogger().info("[BranchManager] Loaded " + count + " shop assignments from data.yml.");
    }

    private void saveToYaml() {
        FileConfiguration data = new YamlConfiguration();
        assignments.forEach((uuid, a) -> {
            String base = "shops." + uuid;
            data.set(base + ".branch",      a.branch());
            data.set(base + ".shop_name",   a.shopName());
            data.set(base + ".owner_uuid",  a.ownerUuid());
            data.set(base + ".owner_name",  a.ownerName());
            data.set(base + ".assigned_by", a.assignedBy());
        });
        try {
            plugin.getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[BranchManager] Failed to save data.yml: " + e.getMessage(), e);
        }
    }
}
