package ru.vmbridge;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class VMBridge extends JavaPlugin {

    private static VMBridge instance;

    private BranchManager branchManager;
    private DatabaseManager db;
    private ClanManager clanManager;

    private boolean simpleClansEnabled;
    private boolean papiEnabled;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        migrateConfig();

        // MySQL must be ready before BranchManager so it can load shop assignments from DB
        db = new DatabaseManager(this);
        if (!db.connect()) {
            getLogger().severe("MySQL connection failed. Will use data.yml fallback for shop assignments.");
            db = null;
        }

        branchManager = new BranchManager(this);

        // SimpleClans
        if (getServer().getPluginManager().isPluginEnabled("SimpleClans")) {
            clanManager = SimpleClans.getInstance().getClanManager();
            simpleClansEnabled = true;
            getLogger().info("SimpleClans hooked.");
        } else {
            getLogger().severe("SimpleClans not found! Tax collection will NOT work.");
        }

        // PlaceholderAPI
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            papiEnabled = true;
            new VMBridgePlaceholders(this).register();
            getLogger().info("PlaceholderAPI hooked.");
        } else {
            getLogger().warning("PlaceholderAPI not found! Branch owners will not be resolved.");
        }

        branchManager.scheduleAndRefresh();

        getServer().getPluginManager().registerEvents(new TaxListener(this), this);

        VMBridgeCommand cmd = new VMBridgeCommand(this);
        getCommand("vmbridge").setExecutor(cmd);
        getCommand("vmbridge").setTabCompleter(cmd);

        getLogger().info("VMBridge v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (branchManager != null) branchManager.save();
        if (db != null) db.disconnect();
        getLogger().info("VMBridge disabled.");
    }

    // ------------------------------------------------------------------ //
    //  Accessors                                                           //
    // ------------------------------------------------------------------ //

    // ------------------------------------------------------------------ //
    //  Config migration                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Adds any keys present in the default config.yml (bundled in the jar)
     * that are missing from the on-disk config. Existing values are never touched.
     */
    private void migrateConfig() {
        var resource = getResource("config.yml");
        if (resource == null) {
            getLogger().severe("[Config] Не удалось найти config.yml внутри jar — миграция пропущена.");
            return;
        }

        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!getConfig().isSet(key)) {
                getConfig().set(key, defaults.get(key));
                getLogger().info("[Config] Добавлен новый параметр: " + key + " = " + defaults.get(key));
                added++;
            }
        }

        if (added > 0) {
            saveConfig();
            getLogger().info("[Config] Миграция завершена, добавлено параметров: " + added + ".");
        } else {
            getLogger().info("[Config] Миграция: все параметры актуальны.");
        }
    }

    public static VMBridge getInstance() { return instance; }
    public BranchManager getBranchManager() { return branchManager; }
    public DatabaseManager getDb() { return db; }
    public ClanManager getClanManager() { return clanManager; }
    public boolean isSimpleClansEnabled() { return simpleClansEnabled; }
    public boolean isPapiEnabled() { return papiEnabled; }
}
