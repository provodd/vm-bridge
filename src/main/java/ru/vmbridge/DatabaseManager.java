package ru.vmbridge;

import org.bukkit.scheduler.BukkitRunnable;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Manages the MySQL connection, schema, and all DB operations.
 *
 * Tables:
 *   vm_shops        — shop-to-branch assignments (persistent config data)
 *   vm_transactions — trade event log
 *
 * Write rules:
 *   - vm_shops writes are synchronous (main thread) — small, infrequent ops.
 *   - vm_transactions writes are async — high-frequency, must not block.
 *   - All access to the Connection object is guarded by `lock`.
 */
public class DatabaseManager {

    private final VMBridge plugin;
    private Connection connection;
    private final Object lock = new Object();

    public DatabaseManager(VMBridge plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ //
    //  Connect / disconnect                                                //
    // ------------------------------------------------------------------ //

    public boolean connect() {
        String host     = plugin.getConfig().getString("mysql.host",     "localhost");
        int    port     = plugin.getConfig().getInt   ("mysql.port",     3306);
        String database = plugin.getConfig().getString("mysql.database", "minecraft");
        String username = plugin.getConfig().getString("mysql.username", "root");
        String password = plugin.getConfig().getString("mysql.password", "");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&autoReconnect=true&characterEncoding=UTF-8";

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            synchronized (lock) {
                connection = DriverManager.getConnection(url, username, password);
                createTables();
            }
            plugin.getLogger().info("[DB] Connected to " + host + ":" + port + "/" + database);
            return true;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("[DB] MySQL driver not found — reinstall the plugin jar.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DB] Connection failed: " + e.getMessage(), e);
        }
        return false;
    }

    public void disconnect() {
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[DB] Error closing connection", e);
            }
            connection = null;
        }
        plugin.getLogger().info("[DB] Disconnected.");
    }

    public boolean isConnected() {
        synchronized (lock) {
            try {
                return connection != null && !connection.isClosed() && connection.isValid(2);
            } catch (SQLException e) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Schema                                                              //
    // ------------------------------------------------------------------ //

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS vm_shops (
                        shop_uuid    VARCHAR(36)  NOT NULL PRIMARY KEY,
                        shop_name    VARCHAR(100),
                        branch       VARCHAR(20)  NOT NULL,
                        owner_uuid   VARCHAR(36)  COMMENT 'NULL for AdminShop',
                        owner_name   VARCHAR(50)  COMMENT 'NULL for AdminShop',
                        assigned_by  VARCHAR(50)  NOT NULL COMMENT 'Admin who assigned',
                        assigned_at  DATETIME     NOT NULL,
                        INDEX idx_branch (branch),
                        INDEX idx_owner  (owner_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS vm_branch_owners (
                        branch      VARCHAR(20)   NOT NULL PRIMARY KEY,
                        clan_tag    VARCHAR(100)  NOT NULL,
                        updated_at  DATETIME      NOT NULL COMMENT 'Moscow time of last cache refresh'
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS vm_transactions (
                        id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
                        timestamp         DATETIME      NOT NULL,
                        event_type        VARCHAR(10)   NOT NULL COMMENT 'BUY or SELL',
                        buyer_uuid        VARCHAR(36)   NOT NULL,
                        buyer_name        VARCHAR(50)   NOT NULL,
                        shop_uuid         VARCHAR(36)   NOT NULL,
                        shop_name         VARCHAR(100),
                        shop_owner_uuid   VARCHAR(36)   COMMENT 'NULL for AdminShop',
                        shop_owner_name   VARCHAR(50)   COMMENT 'NULL for AdminShop',
                        branch            VARCHAR(20)   COMMENT 'green/blue/pink/orange or NULL',
                        item_name         VARCHAR(200),
                        item_amount       INT,
                        transaction_value DECIMAL(15,2) COMMENT 'Total price of the deal',
                        tax_rate          DECIMAL(6,4)  COMMENT '0.0 if no tax',
                        tax_amount        DECIMAL(15,2) COMMENT 'Amount deposited to clan bank',
                        tax_clan          VARCHAR(50)   COMMENT 'Clan tag that received the tax',
                        tax_status        VARCHAR(40)   COMMENT 'SUCCESS / reason for failure',
                        INDEX idx_buyer  (buyer_uuid),
                        INDEX idx_shop   (shop_uuid),
                        INDEX idx_branch (branch),
                        INDEX idx_clan   (tax_clan),
                        INDEX idx_time   (timestamp)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                    """);
        }
    }

    // ------------------------------------------------------------------ //
    //  vm_shops — synchronous CRUD (called from main thread)              //
    // ------------------------------------------------------------------ //

    /**
     * Loads all shop assignments from the DB.
     * Called once on startup from the main thread.
     */
    public Map<UUID, ShopAssignment> loadAllShops() {
        Map<UUID, ShopAssignment> result = new HashMap<>();
        String sql = "SELECT shop_uuid, shop_name, branch, owner_uuid, owner_name, assigned_by, assigned_at FROM vm_shops";

        synchronized (lock) {
            try {
                ensureConnected();
                try (PreparedStatement ps = connection.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("shop_uuid"));
                        result.put(uuid, new ShopAssignment(
                                uuid,
                                rs.getString("shop_name"),
                                rs.getString("branch"),
                                rs.getString("owner_uuid"),
                                rs.getString("owner_name"),
                                rs.getString("assigned_by"),
                                rs.getTimestamp("assigned_at").toLocalDateTime()
                        ));
                    }
                }
                plugin.getLogger().info("[DB] Loaded " + result.size() + " shop assignments from MySQL.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Failed to load shops: " + e.getMessage(), e);
            }
        }
        return result;
    }

    /**
     * Inserts or updates a shop assignment.
     * Synchronous — called from main thread on /vmbridge setbranch.
     */
    public void upsertShop(ShopAssignment a) {
        String sql = """
                INSERT INTO vm_shops (shop_uuid, shop_name, branch, owner_uuid, owner_name, assigned_by, assigned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    shop_name   = VALUES(shop_name),
                    branch      = VALUES(branch),
                    owner_uuid  = VALUES(owner_uuid),
                    owner_name  = VALUES(owner_name),
                    assigned_by = VALUES(assigned_by),
                    assigned_at = VALUES(assigned_at)
                """;
        synchronized (lock) {
            try {
                ensureConnected();
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString   (1, a.shopUuid().toString());
                    ps.setString   (2, a.shopName());
                    ps.setString   (3, a.branch());
                    ps.setString   (4, a.ownerUuid());
                    ps.setString   (5, a.ownerName());
                    ps.setString   (6, a.assignedBy());
                    ps.setTimestamp(7, Timestamp.valueOf(a.assignedAt()));
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[DB] Failed to upsert shop: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Removes a shop assignment.
     * Synchronous — called from main thread on /vmbridge removebranch.
     */
    public void removeShop(UUID shopUuid) {
        synchronized (lock) {
            try {
                ensureConnected();
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM vm_shops WHERE shop_uuid = ?")) {
                    ps.setString(1, shopUuid.toString());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[DB] Failed to remove shop: " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  vm_branch_owners — branch clan cache                              //
    // ------------------------------------------------------------------ //

    /** Loads all branch → clan_tag entries. Called once on startup (main thread). */
    public Map<String, String> loadBranchOwners() {
        Map<String, String> result = new HashMap<>();
        synchronized (lock) {
            try {
                ensureConnected();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT branch, clan_tag FROM vm_branch_owners");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("branch"), rs.getString("clan_tag"));
                    }
                }
                plugin.getLogger().info("[DB] Loaded " + result.size() + " branch owner(s) from MySQL.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[DB] Failed to load branch owners: " + e.getMessage(), e);
            }
        }
        return result;
    }

    /** Upserts a branch owner. Safe to call from an async thread. */
    public void saveBranchOwner(String branch, String clanTag, LocalDateTime updatedAt) {
        String sql = """
                INSERT INTO vm_branch_owners (branch, clan_tag, updated_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE clan_tag = VALUES(clan_tag), updated_at = VALUES(updated_at)
                """;
        synchronized (lock) {
            try {
                ensureConnected();
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString   (1, branch);
                    ps.setString   (2, clanTag);
                    ps.setTimestamp(3, Timestamp.valueOf(updatedAt));
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "[DB] Failed to save branch owner '" + branch + "': " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  vm_transactions — async logging                                    //
    // ------------------------------------------------------------------ //

    public void logAsync(TransactionRecord record) {
        new BukkitRunnable() {
            @Override
            public void run() {
                insertTransaction(record);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void insertTransaction(TransactionRecord r) {
        String sql = """
                INSERT INTO vm_transactions
                (timestamp, event_type, buyer_uuid, buyer_name, shop_uuid, shop_name,
                 shop_owner_uuid, shop_owner_name, branch, item_name, item_amount,
                 transaction_value, tax_rate, tax_amount, tax_clan, tax_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        synchronized (lock) {
            try {
                ensureConnected();
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setTimestamp(1,  Timestamp.valueOf(r.timestamp()));
                    ps.setString  (2,  r.eventType());
                    ps.setString  (3,  r.buyerUuid());
                    ps.setString  (4,  r.buyerName());
                    ps.setString  (5,  r.shopUuid());
                    ps.setString  (6,  r.shopName());
                    ps.setString  (7,  r.shopOwnerUuid());
                    ps.setString  (8,  r.shopOwnerName());
                    ps.setString  (9,  r.branch());
                    ps.setString  (10, r.itemName());
                    ps.setInt     (11, r.itemAmount());
                    ps.setDouble  (12, r.transactionValue());
                    ps.setDouble  (13, r.taxRate());
                    ps.setDouble  (14, r.taxAmount());
                    ps.setString  (15, r.taxClan());
                    ps.setString  (16, r.taxStatus());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "[DB] Failed to write transaction: " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Internal                                                           //
    // ------------------------------------------------------------------ //

    private void ensureConnected() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            plugin.getLogger().warning("[DB] Connection lost — reconnecting...");
            connect();
            if (connection == null || connection.isClosed()) {
                throw new SQLException("Reconnect failed");
            }
        }
    }
}
