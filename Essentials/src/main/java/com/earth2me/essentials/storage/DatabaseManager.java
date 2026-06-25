package com.earth2me.essentials.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.earth2me.essentials.IEssentials;
import com.earth2me.essentials.config.entities.LazyLocation;
import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class DatabaseManager {
    private final IEssentials essentials;
    private HikariDataSource dataSource;
    private final boolean enabled;
    private final String type;

    public static class DbUserRecord {
        public final String yamlData;
        public final BigDecimal money;
        public final String username;
        public final String nickname;
        public final String ipAddress;
        public final String geolocation;
        public final long lastLogin;
        public final long lastLogout;
        public final boolean godMode;
        public final boolean flyMode;
        public final boolean muted;
        public final String muteReason;
        public final boolean jailed;
        public final String jailName;
        public final boolean afk;
        public final boolean socialSpy;
        public final boolean npc;
        public final String npcName;
        
        // Locations
        public final String lastLocWorld;
        public final Double lastLocX;
        public final Double lastLocY;
        public final Double lastLocZ;
        public final Float lastLocYaw;
        public final Float lastLocPitch;
        
        public final String logoutLocWorld;
        public final Double logoutLocX;
        public final Double logoutLocY;
        public final Double logoutLocZ;
        public final Float logoutLocYaw;
        public final Float logoutLocPitch;
        
        // Homes
        public final Map<String, LazyLocation> homes;

        public DbUserRecord(final String yamlData, final BigDecimal money, final String username, final String nickname,
                            final String ipAddress, final String geolocation, final long lastLogin, final long lastLogout,
                            final boolean godMode, final boolean flyMode, final boolean muted, final String muteReason,
                            final boolean jailed, final String jailName, final boolean afk, final boolean socialSpy,
                            final boolean npc, final String npcName, final String lastLocWorld, final Double lastLocX,
                            final Double lastLocY, final Double lastLocZ, final Float lastLocYaw, final Float lastLocPitch,
                            final String logoutLocWorld, final Double logoutLocX, final Double logoutLocY, final Double logoutLocZ,
                            final Float logoutLocYaw, final Float logoutLocPitch,
                            final Map<String, LazyLocation> homes) {
            this.yamlData = yamlData;
            this.money = money;
            this.username = username;
            this.nickname = nickname;
            this.ipAddress = ipAddress;
            this.geolocation = geolocation;
            this.lastLogin = lastLogin;
            this.lastLogout = lastLogout;
            this.godMode = godMode;
            this.flyMode = flyMode;
            this.muted = muted;
            this.muteReason = muteReason;
            this.jailed = jailed;
            this.jailName = jailName;
            this.afk = afk;
            this.socialSpy = socialSpy;
            this.npc = npc;
            this.npcName = npcName;
            this.lastLocWorld = lastLocWorld;
            this.lastLocX = lastLocX;
            this.lastLocY = lastLocY;
            this.lastLocZ = lastLocZ;
            this.lastLocYaw = lastLocYaw;
            this.lastLocPitch = lastLocPitch;
            this.logoutLocWorld = logoutLocWorld;
            this.logoutLocX = logoutLocX;
            this.logoutLocY = logoutLocY;
            this.logoutLocZ = logoutLocZ;
            this.logoutLocYaw = logoutLocYaw;
            this.logoutLocPitch = logoutLocPitch;
            this.homes = homes;
        }
    }

    public DatabaseManager(final IEssentials essentials) {
        this.essentials = essentials;
        this.type = essentials.getSettings().getDatabaseType().toLowerCase();
        this.enabled = "sqlite".equals(type) || "mysql".equals(type);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void init() {
        if (!enabled) {
            return;
        }

        try {
            final HikariConfig config = new HikariConfig();
            
            if ("sqlite".equals(type)) {
                config.setDriverClassName("org.sqlite.JDBC");
                final File dbFile = new File(essentials.getDataFolder(), essentials.getSettings().getDatabaseSqliteFile());
                if (!dbFile.getParentFile().exists()) {
                    dbFile.getParentFile().mkdirs();
                }
                final int busyTimeout = essentials.getSettings().getDatabaseSqliteBusyTimeoutMs();
                config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath() + "?busy_timeout=" + busyTimeout);
            } else if ("mysql".equals(type)) {
                try {
                    Class.forName("com.mysql.cj.jdbc.Driver");
                    config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                } catch (ClassNotFoundException e) {
                    config.setDriverClassName("com.mysql.jdbc.Driver");
                }
                
                final String host = essentials.getSettings().getDatabaseMysqlHost();
                final int port = essentials.getSettings().getDatabaseMysqlPort();
                final String db = essentials.getSettings().getDatabaseMysqlDatabase();
                final boolean useSsl = essentials.getSettings().isDatabaseMysqlUseSsl();
                
                config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true&serverTimezone=UTC");
                config.setUsername(essentials.getSettings().getDatabaseMysqlUsername());
                config.setPassword(essentials.getSettings().getDatabaseMysqlPassword());
            }

            // Pool settings
            config.setMaximumPoolSize(essentials.getSettings().getDatabasePoolMaximumPoolSize());
            config.setMinimumIdle(essentials.getSettings().getDatabasePoolMinimumIdle());
            config.setConnectionTimeout(essentials.getSettings().getDatabasePoolConnectionTimeoutSeconds() * 1000L);
            config.setIdleTimeout(essentials.getSettings().getDatabasePoolIdleTimeoutSeconds() * 1000L);
            config.setMaxLifetime(essentials.getSettings().getDatabasePoolMaxLifetimeMinutes() * 60000L);
            
            config.setPoolName("EssentialsPool");
 
            dataSource = new HikariDataSource(config);
            setupTable();
            essentials.getLogger().info("[DatabaseManager] Database storage successfully initialized (" + type + ").");
        } catch (Exception e) {
            essentials.getLogger().log(Level.SEVERE, "[DatabaseManager] Failed to initialize database pool!", e);
        }
    }

    private void setupTable() {
        final String sqlUsers = "CREATE TABLE IF NOT EXISTS essentials_users (" +
                "uuid VARCHAR(36) NOT NULL," +
                "username VARCHAR(64) NULL," +
                "nickname VARCHAR(64) NULL," +
                "money DECIMAL(30, 2) DEFAULT 0.00," +
                "ip_address VARCHAR(45) NULL," +
                "geolocation VARCHAR(100) NULL," +
                "last_login BIGINT DEFAULT 0," +
                "last_logout BIGINT DEFAULT 0," +
                "god_mode TINYINT(1) DEFAULT 0," +
                "fly_mode TINYINT(1) DEFAULT 0," +
                "muted TINYINT(1) DEFAULT 0," +
                "mute_reason VARCHAR(255) NULL," +
                "jailed TINYINT(1) DEFAULT 0," +
                "jail_name VARCHAR(64) NULL," +
                "afk TINYINT(1) DEFAULT 0," +
                "social_spy TINYINT(1) DEFAULT 0," +
                "npc TINYINT(1) DEFAULT 0," +
                "npc_name VARCHAR(64) NULL," +
                "last_location_world VARCHAR(128) NULL," +
                "last_location_x DOUBLE NULL," +
                "last_location_y DOUBLE NULL," +
                "last_location_z DOUBLE NULL," +
                "last_location_yaw FLOAT NULL," +
                "last_location_pitch FLOAT NULL," +
                "logout_location_world VARCHAR(128) NULL," +
                "logout_location_x DOUBLE NULL," +
                "logout_location_y DOUBLE NULL," +
                "logout_location_z DOUBLE NULL," +
                "logout_location_yaw FLOAT NULL," +
                "logout_location_pitch FLOAT NULL," +
                "data LONGTEXT NOT NULL," +
                "PRIMARY KEY (uuid)" +
                ")";
                
        final String sqlHomes = "CREATE TABLE IF NOT EXISTS essentials_user_homes (" +
                "uuid VARCHAR(36) NOT NULL," +
                "home_name VARCHAR(64) NOT NULL," +
                "world VARCHAR(128) NOT NULL," +
                "x DOUBLE NOT NULL," +
                "y DOUBLE NOT NULL," +
                "z DOUBLE NOT NULL," +
                "yaw FLOAT NOT NULL," +
                "pitch FLOAT NOT NULL," +
                "PRIMARY KEY (uuid, home_name)" +
                ")";

        try (final Connection conn = getConnection(); final Statement stmt = conn.createStatement()) {
            stmt.execute(sqlUsers);
            stmt.execute(sqlHomes);

            // Migrate existing table if columns are missing
            addColumnIfNotExists(conn, "essentials_users", "nickname", "VARCHAR(64) NULL");
            addColumnIfNotExists(conn, "essentials_users", "money", "DECIMAL(30, 2) DEFAULT 0.00");
            addColumnIfNotExists(conn, "essentials_users", "ip_address", "VARCHAR(45) NULL");
            addColumnIfNotExists(conn, "essentials_users", "geolocation", "VARCHAR(100) NULL");
            addColumnIfNotExists(conn, "essentials_users", "last_logout", "BIGINT DEFAULT 0");
            addColumnIfNotExists(conn, "essentials_users", "god_mode", "TINYINT(1) DEFAULT 0");
            addColumnIfNotExists(conn, "essentials_users", "fly_mode", "TINYINT(1) DEFAULT 0");
            addColumnIfNotExists(conn, "essentials_users", "muted", "TINYINT(1) DEFAULT 0");
            addColumnIfNotExists(conn, "essentials_users", "mute_reason", "VARCHAR(255) NULL");
            addColumnIfNotExists(conn, "essentials_users", "jailed", "TINYINT(1) DEFAULT 0");
            addColumnIfNotExists(conn, "essentials_users", "jail_name", "VARCHAR(64) NULL");
            addColumnIfNotExists(conn, "essentials_users", "afk", "TINYINT(1) DEFAULT 0");
            addColumnIfNotExists(conn, "essentials_users", "social_spy", "TINYINT(1) DEFAULT 0");
            addColumnIfNotExists(conn, "essentials_users", "npc", "TINYINT(1) DEFAULT 0");
            addColumnIfNotExists(conn, "essentials_users", "npc_name", "VARCHAR(64) NULL");

            addColumnIfNotExists(conn, "essentials_users", "last_location_world", "VARCHAR(128) NULL");
            addColumnIfNotExists(conn, "essentials_users", "last_location_x", "DOUBLE NULL");
            addColumnIfNotExists(conn, "essentials_users", "last_location_y", "DOUBLE NULL");
            addColumnIfNotExists(conn, "essentials_users", "last_location_z", "DOUBLE NULL");
            addColumnIfNotExists(conn, "essentials_users", "last_location_yaw", "FLOAT NULL");
            addColumnIfNotExists(conn, "essentials_users", "last_location_pitch", "FLOAT NULL");

            addColumnIfNotExists(conn, "essentials_users", "logout_location_world", "VARCHAR(128) NULL");
            addColumnIfNotExists(conn, "essentials_users", "logout_location_x", "DOUBLE NULL");
            addColumnIfNotExists(conn, "essentials_users", "logout_location_y", "DOUBLE NULL");
            addColumnIfNotExists(conn, "essentials_users", "logout_location_z", "DOUBLE NULL");
            addColumnIfNotExists(conn, "essentials_users", "logout_location_yaw", "FLOAT NULL");
            addColumnIfNotExists(conn, "essentials_users", "logout_location_pitch", "FLOAT NULL");
        } catch (final SQLException e) {
            essentials.getLogger().log(Level.SEVERE, "[DatabaseManager] Failed to create or migrate database tables!", e);
        }
    }

    private void addColumnIfNotExists(final Connection conn, final String tableName, final String columnName, final String columnDefinition) {
        boolean exists = false;
        try (final ResultSet rs = conn.getMetaData().getColumns(null, null, null, null)) {
            while (rs.next()) {
                final String tblName = rs.getString("TABLE_NAME");
                final String colName = rs.getString("COLUMN_NAME");
                if (tableName.equalsIgnoreCase(tblName) && columnName.equalsIgnoreCase(colName)) {
                    exists = true;
                    break;
                }
            }
        } catch (final SQLException e) {
            // Ignore metadata errors
        }

        if (!exists) {
            final String alterSql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition;
            try (final Statement stmt = conn.createStatement()) {
                stmt.execute(alterSql);
                essentials.getLogger().info("[DatabaseManager] Migrated: added column '" + columnName + "' to table '" + tableName + "'.");
            } catch (final SQLException e) {
                if (e.getMessage().contains("duplicate") || e.getMessage().contains("exists") || e.getErrorCode() == 1060) {
                    return;
                }
                essentials.getLogger().log(Level.WARNING, "[DatabaseManager] Failed to add column '" + columnName + "' to table '" + tableName + "'.", e);
            }
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DatabaseManager source is not initialized");
        }
        return dataSource.getConnection();
    }

    public DbUserRecord loadUser(final UUID uuid) {
        if (!enabled) {
            return null;
        }
        
        final String userSql = "SELECT * FROM essentials_users WHERE uuid = ?";
        final String homesSql = "SELECT * FROM essentials_user_homes WHERE uuid = ?";
        
        try (final Connection conn = getConnection()) {
            DbUserRecord record = null;
            try (final PreparedStatement ps = conn.prepareStatement(userSql)) {
                ps.setString(1, uuid.toString());
                try (final ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        final String yamlData = rs.getString("data");
                        final BigDecimal money = rs.getBigDecimal("money");
                        final String username = rs.getString("username");
                        final String nickname = rs.getString("nickname");
                        final String ipAddress = rs.getString("ip_address");
                        final String geolocation = rs.getString("geolocation");
                        final long lastLogin = rs.getLong("last_login");
                        final long lastLogout = rs.getLong("last_logout");
                        final boolean godMode = rs.getBoolean("god_mode");
                        final boolean flyMode = rs.getBoolean("fly_mode");
                        final boolean muted = rs.getBoolean("muted");
                        final String muteReason = rs.getString("mute_reason");
                        final boolean jailed = rs.getBoolean("jailed");
                        final String jailName = rs.getString("jail_name");
                        final boolean afk = rs.getBoolean("afk");
                        final boolean socialSpy = rs.getBoolean("social_spy");
                        final boolean npc = rs.getBoolean("npc");
                        final String npcName = rs.getString("npc_name");
                        
                        // Locations
                        final String lastLocWorld = rs.getString("last_location_world");
                        final Double lastLocX = rs.getObject("last_location_x") != null ? rs.getDouble("last_location_x") : null;
                        final Double lastLocY = rs.getObject("last_location_y") != null ? rs.getDouble("last_location_y") : null;
                        final Double lastLocZ = rs.getObject("last_location_z") != null ? rs.getDouble("last_location_z") : null;
                        final Float lastLocYaw = rs.getObject("last_location_yaw") != null ? rs.getFloat("last_location_yaw") : null;
                        final Float lastLocPitch = rs.getObject("last_location_pitch") != null ? rs.getFloat("last_location_pitch") : null;
                        
                        final String logoutLocWorld = rs.getString("logout_location_world");
                        final Double logoutLocX = rs.getObject("logout_location_x") != null ? rs.getDouble("logout_location_x") : null;
                        final Double logoutLocY = rs.getObject("logout_location_y") != null ? rs.getDouble("logout_location_y") : null;
                        final Double logoutLocZ = rs.getObject("logout_location_z") != null ? rs.getDouble("logout_location_z") : null;
                        final Float logoutLocYaw = rs.getObject("logout_location_yaw") != null ? rs.getFloat("logout_location_yaw") : null;
                        final Float logoutLocPitch = rs.getObject("logout_location_pitch") != null ? rs.getFloat("logout_location_pitch") : null;
                        
                        // Load homes
                        final Map<String, LazyLocation> homes = new HashMap<>();
                        try (final PreparedStatement psHomes = conn.prepareStatement(homesSql)) {
                            psHomes.setString(1, uuid.toString());
                            try (final ResultSet rsHomes = psHomes.executeQuery()) {
                                while (rsHomes.next()) {
                                    final String homeName = rsHomes.getString("home_name");
                                    final String hWorld = rsHomes.getString("world");
                                    final double hX = rsHomes.getDouble("x");
                                    final double hY = rsHomes.getDouble("y");
                                    final double hZ = rsHomes.getDouble("z");
                                    final float hYaw = rsHomes.getFloat("yaw");
                                    final float hPitch = rsHomes.getFloat("pitch");
                                    
                                    homes.put(homeName, new LazyLocation(hWorld, hWorld, hX, hY, hZ, hYaw, hPitch));
                                }
                            }
                        }
                        
                        record = new DbUserRecord(yamlData, money, username, nickname, ipAddress, geolocation,
                                lastLogin, lastLogout, godMode, flyMode, muted, muteReason, jailed, jailName,
                                afk, socialSpy, npc, npcName, lastLocWorld, lastLocX, lastLocY, lastLocZ,
                                lastLocYaw, lastLocPitch, logoutLocWorld, logoutLocX, logoutLocY, logoutLocZ,
                                logoutLocYaw, logoutLocPitch, homes);
                    }
                }
            }
            return record;
        } catch (SQLException e) {
            essentials.getLogger().log(Level.SEVERE, "[DatabaseManager] Failed to load data for UUID: " + uuid, e);
        }
        return null;
    }

    public void saveUser(final UUID uuid, final String username, final String nickname, final BigDecimal money,
                         final String ipAddress, final String geolocation, final long lastLogin, final long lastLogout,
                         final boolean godMode, final boolean flyMode, final boolean muted, final String muteReason,
                         final boolean jailed, final String jailName, final boolean afk, final boolean socialSpy,
                         final boolean npc, final String npcName, final String lastLocWorld, final Double lastLocX,
                         final Double lastLocY, final Double lastLocZ, final Float lastLocYaw, final Float lastLocPitch,
                         final String logoutLocWorld, final Double logoutLocX, final Double logoutLocY, final Double logoutLocZ,
                         final Float logoutLocYaw, final Float logoutLocPitch, final String yamlData,
                         final Map<String, LazyLocation> homes) {
        if (!enabled) {
            return;
        }

        final String userSql = "REPLACE INTO essentials_users (uuid, username, nickname, money, ip_address, geolocation, " +
                "last_login, last_logout, god_mode, fly_mode, muted, mute_reason, jailed, jail_name, afk, social_spy, " +
                "npc, npc_name, last_location_world, last_location_x, last_location_y, last_location_z, last_location_yaw, " +
                "last_location_pitch, logout_location_world, logout_location_x, logout_location_y, logout_location_z, " +
                "logout_location_yaw, logout_location_pitch, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        final String deleteHomesSql = "DELETE FROM essentials_user_homes WHERE uuid = ?";
        final String insertHomeSql = "INSERT INTO essentials_user_homes (uuid, home_name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (final Connection conn = getConnection()) {
            final boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (final PreparedStatement ps = conn.prepareStatement(userSql)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, username != null ? username : "");
                    ps.setString(3, nickname);
                    ps.setBigDecimal(4, money != null ? money : BigDecimal.ZERO);
                    ps.setString(5, ipAddress != null ? ipAddress : "");
                    ps.setString(6, geolocation);
                    ps.setLong(7, lastLogin);
                    ps.setLong(8, lastLogout);
                    ps.setInt(9, godMode ? 1 : 0);
                    ps.setInt(10, flyMode ? 1 : 0);
                    ps.setInt(11, muted ? 1 : 0);
                    ps.setString(12, muteReason);
                    ps.setInt(13, jailed ? 1 : 0);
                    ps.setString(14, jailName);
                    ps.setInt(15, afk ? 1 : 0);
                    ps.setInt(16, socialSpy ? 1 : 0);
                    ps.setInt(17, npc ? 1 : 0);
                    ps.setString(18, npcName);
                    
                    // Last Location
                    ps.setString(19, lastLocWorld);
                    if (lastLocX != null) {
                        ps.setDouble(20, lastLocX);
                    } else {
                        ps.setNull(20, java.sql.Types.DOUBLE);
                    }
                    if (lastLocY != null) {
                        ps.setDouble(21, lastLocY);
                    } else {
                        ps.setNull(21, java.sql.Types.DOUBLE);
                    }
                    if (lastLocZ != null) {
                        ps.setDouble(22, lastLocZ);
                    } else {
                        ps.setNull(22, java.sql.Types.DOUBLE);
                    }
                    if (lastLocYaw != null) {
                        ps.setFloat(23, lastLocYaw);
                    } else {
                        ps.setNull(23, java.sql.Types.FLOAT);
                    }
                    if (lastLocPitch != null) {
                        ps.setFloat(24, lastLocPitch);
                    } else {
                        ps.setNull(24, java.sql.Types.FLOAT);
                    }
                    
                    // Logout Location
                    ps.setString(25, logoutLocWorld);
                    if (logoutLocX != null) {
                        ps.setDouble(26, logoutLocX);
                    } else {
                        ps.setNull(26, java.sql.Types.DOUBLE);
                    }
                    if (logoutLocY != null) {
                        ps.setDouble(27, logoutLocY);
                    } else {
                        ps.setNull(27, java.sql.Types.DOUBLE);
                    }
                    if (logoutLocZ != null) {
                        ps.setDouble(28, logoutLocZ);
                    } else {
                        ps.setNull(28, java.sql.Types.DOUBLE);
                    }
                    if (logoutLocYaw != null) {
                        ps.setFloat(29, logoutLocYaw);
                    } else {
                        ps.setNull(29, java.sql.Types.FLOAT);
                    }
                    if (logoutLocPitch != null) {
                        ps.setFloat(30, logoutLocPitch);
                    } else {
                        ps.setNull(30, java.sql.Types.FLOAT);
                    }
                    
                    ps.setString(31, yamlData);
                    ps.executeUpdate();
                }
                
                try (final PreparedStatement psDel = conn.prepareStatement(deleteHomesSql)) {
                    psDel.setString(1, uuid.toString());
                    psDel.executeUpdate();
                }
                
                if (homes != null && !homes.isEmpty()) {
                    try (final PreparedStatement psIns = conn.prepareStatement(insertHomeSql)) {
                        for (final Map.Entry<String, LazyLocation> entry : homes.entrySet()) {
                            final String homeName = entry.getKey();
                            final LazyLocation loc = entry.getValue();
                            if (loc != null) {
                                psIns.setString(1, uuid.toString());
                                psIns.setString(2, homeName);
                                psIns.setString(3, loc.world() != null ? loc.world() : "");
                                psIns.setDouble(4, loc.x());
                                psIns.setDouble(5, loc.y());
                                psIns.setDouble(6, loc.z());
                                psIns.setFloat(7, loc.yaw());
                                psIns.setFloat(8, loc.pitch());
                                psIns.addBatch();
                            }
                        }
                        psIns.executeBatch();
                    }
                }
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            essentials.getLogger().log(Level.SEVERE, "[DatabaseManager] Failed to save data for UUID: " + uuid, e);
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            essentials.getLogger().info("[DatabaseManager] Database pool closed.");
        }
    }
}
