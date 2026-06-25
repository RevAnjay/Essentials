package com.earth2me.essentials.config;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.IEssentials;
import com.earth2me.essentials.config.entities.LazyLocation;
import com.earth2me.essentials.storage.DatabaseManager;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class EssentialsUserConfiguration extends EssentialsConfiguration {
    private static IEssentials essInstance;
    private String username;
    private final UUID uuid;

    public static void setEssentials(final IEssentials ess) {
        essInstance = ess;
    }

    private static DatabaseManager getDatabaseManager() {
        if (essInstance instanceof Essentials) {
            return ((Essentials) essInstance).getDatabaseManager();
        }
        return null;
    }

    public EssentialsUserConfiguration(final String username, final UUID uuid, final File configFile) {
        super(configFile);
        this.username = username;
        this.uuid = uuid;
    }

    public String getUsername() {
        return username;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUsername(final String username) {
        this.username = username;
    }

    @Override
    public boolean legacyFileExists() {
        if (username == null) {
            return false;
        }
        return new File(configFile.getParentFile(), username + ".yml").exists();
    }

    @Override
    public void convertLegacyFile() {
        final File file = new File(configFile.getParentFile(), username + ".yml");
        try {
            //noinspection UnstableApiUsage
            Files.move(file, new File(configFile.getParentFile(), uuid + ".yml"));
        } catch (final IOException ex) {
            Essentials.getWrappedLogger().log(Level.WARNING, "Failed to migrate user: " + username, ex);
        }

        setProperty("last-account-name", username);
    }

    private File getAltFile() {
        final UUID fn = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username.toLowerCase(Locale.ENGLISH)).getBytes(Charsets.UTF_8));
        return new File(configFile.getParentFile(), fn + ".yml");
    }

    @Override
    public boolean altFileExists() {
        if (username == null || username.equals(username.toLowerCase())) {
            return false;
        }
        return getAltFile().exists();
    }

    @Override
    public void convertAltFile() {
        try {
            Files.move(getAltFile(), new File(configFile.getParentFile(), uuid + ".yml"));
        } catch (final IOException ex) {
            Essentials.getWrappedLogger().log(Level.WARNING, "Failed to migrate user: " + username, ex);
        }
    }

    @Override
    public synchronized void load() {
        final DatabaseManager db = getDatabaseManager();
        if (db != null && db.isEnabled()) {
            final DatabaseManager.DbUserRecord record = db.loadUser(uuid);
            if (record != null) {
                try {
                    loadFromString(record.yamlData);
                    
                    if (record.money != null) {
                        setProperty("money", record.money);
                    }
                    if (record.username != null) {
                        setProperty("last-account-name", record.username);
                    }
                    if (record.nickname != null) {
                        setProperty("nickname", record.nickname);
                    }
                    if (record.ipAddress != null) {
                        setProperty("ip-address", record.ipAddress);
                    }
                    if (record.geolocation != null) {
                        setProperty("geolocation", record.geolocation);
                    }
                    setProperty("timestamps.login", record.lastLogin);
                    setProperty("timestamps.logout", record.lastLogout);
                    setProperty("godmode", record.godMode);
                    setProperty("flymode", record.flyMode);
                    setProperty("muted", record.muted);
                    if (record.muteReason != null) {
                        setProperty("mute-reason", record.muteReason);
                    }
                    setProperty("jailed", record.jailed);
                    if (record.jailName != null) {
                        setProperty("jail", record.jailName);
                    }
                    setProperty("afk", record.afk);
                    setProperty("socialspy", record.socialSpy);
                    setProperty("npc", record.npc);
                    if (record.npcName != null) {
                        setProperty("npc-name", record.npcName);
                    }
                    
                    // Locations
                    if (record.lastLocWorld != null) {
                        setProperty("lastlocation.world", record.lastLocWorld);
                        setProperty("lastlocation.x", record.lastLocX);
                        setProperty("lastlocation.y", record.lastLocY);
                        setProperty("lastlocation.z", record.lastLocZ);
                        setProperty("lastlocation.yaw", record.lastLocYaw);
                        setProperty("lastlocation.pitch", record.lastLocPitch);
                    } else {
                        removeProperty("lastlocation");
                    }
                    
                    if (record.logoutLocWorld != null) {
                        setProperty("logoutlocation.world", record.logoutLocWorld);
                        setProperty("logoutlocation.x", record.logoutLocX);
                        setProperty("logoutlocation.y", record.logoutLocY);
                        setProperty("logoutlocation.z", record.logoutLocZ);
                        setProperty("logoutlocation.yaw", record.logoutLocYaw);
                        setProperty("logoutlocation.pitch", record.logoutLocPitch);
                    } else {
                        removeProperty("logoutlocation");
                    }
                    
                    // Homes
                    if (record.homes != null && !record.homes.isEmpty()) {
                        removeProperty("homes");
                        for (final Map.Entry<String, LazyLocation> entry : record.homes.entrySet()) {
                            setProperty("homes." + entry.getKey(), entry.getValue());
                        }
                    } else {
                        removeProperty("homes");
                    }
                    
                    return;
                } catch (Exception e) {
                    Essentials.getWrappedLogger().log(Level.SEVERE, "Failed to load user config from database for " + uuid + ", falling back to file...", e);
                }
            }
            
            super.load();
            
            if (getRootNode() != null && !getRootNode().virtual() && !getRootNode().isNull()) {
                try {
                    saveToDb(db);
                    
                    if (configFile.exists()) {
                        final File convertedFile = new File(configFile.getParentFile(), configFile.getName() + ".converted");
                        if (configFile.renameTo(convertedFile)) {
                            Essentials.getWrappedLogger().info("[Database] Migrated " + configFile.getName() + " to database and renamed it to " + convertedFile.getName());
                        } else {
                            Essentials.getWrappedLogger().warning("[Database] Failed to rename converted user file: " + configFile.getName());
                        }
                    }
                } catch (Exception e) {
                    Essentials.getWrappedLogger().log(Level.SEVERE, "Failed to migrate user data to database for " + uuid, e);
                }
            }
        } else {
            super.load();
        }
    }

    private void saveToDb(final DatabaseManager db) throws Exception {
        final java.math.BigDecimal money = getBigDecimal("money", null);
        final String usernameVal = getString("last-account-name", null);
        final String nickname = getString("nickname", null);
        final String ipAddress = getString("ip-address", null);
        final String geolocation = getString("geolocation", null);
        final long lastLogin = getLong("timestamps.login", 0L);
        final long lastLogout = getLong("timestamps.logout", 0L);
        final boolean godMode = getBoolean("godmode", false);
        final boolean flyMode = getBoolean("flymode", false);
        final boolean muted = getBoolean("muted", false);
        final String muteReason = getString("mute-reason", null);
        final boolean jailed = getBoolean("jailed", false);
        final String jailName = getString("jail", null);
        final boolean afk = getBoolean("afk", false);
        final boolean socialSpy = getBoolean("socialspy", false);
        final boolean npc = getBoolean("npc", false);
        final String npcName = getString("npc-name", null);

        // Locations
        final String lastLocWorld = getString("lastlocation.world", null);
        final Double lastLocX = hasProperty("lastlocation.x") ? getDouble("lastlocation.x", 0.0) : null;
        final Double lastLocY = hasProperty("lastlocation.y") ? getDouble("lastlocation.y", 0.0) : null;
        final Double lastLocZ = hasProperty("lastlocation.z") ? getDouble("lastlocation.z", 0.0) : null;
        final Float lastLocYaw = hasProperty("lastlocation.yaw") ? getFloat("lastlocation.yaw", 0.0f) : null;
        final Float lastLocPitch = hasProperty("lastlocation.pitch") ? getFloat("lastlocation.pitch", 0.0f) : null;

        final String logoutLocWorld = getString("logoutlocation.world", null);
        final Double logoutLocX = hasProperty("logoutlocation.x") ? getDouble("logoutlocation.x", 0.0) : null;
        final Double logoutLocY = hasProperty("logoutlocation.y") ? getDouble("logoutlocation.y", 0.0) : null;
        final Double logoutLocZ = hasProperty("logoutlocation.z") ? getDouble("logoutlocation.z", 0.0) : null;
        final Float logoutLocYaw = hasProperty("logoutlocation.yaw") ? getFloat("logoutlocation.yaw", 0.0f) : null;
        final Float logoutLocPitch = hasProperty("logoutlocation.pitch") ? getFloat("logoutlocation.pitch", 0.0f) : null;

        // Homes
        final java.util.Map<String, com.earth2me.essentials.config.entities.LazyLocation> homesMap = getLocationSectionMap("homes");

        // YAML data doc
        final String yamlString = saveToString();

        // Save
        db.saveUser(uuid, usernameVal != null ? usernameVal : username, nickname, money, ipAddress, geolocation,
                lastLogin, lastLogout, godMode, flyMode, muted, muteReason, jailed, jailName, afk, socialSpy,
                npc, npcName, lastLocWorld, lastLocX, lastLocY, lastLocZ, lastLocYaw, lastLocPitch,
                logoutLocWorld, logoutLocX, logoutLocY, logoutLocZ, logoutLocYaw, logoutLocPitch, yamlString, homesMap);
    }

    @Override
    public synchronized void save() {
        final DatabaseManager db = getDatabaseManager();
        if (db != null && db.isEnabled()) {
            if (isTransaction()) {
                return;
            }
            if (essInstance != null) {
                essInstance.runTaskAsynchronously(() -> {
                    try {
                        saveToDb(db);
                    } catch (Exception e) {
                        Essentials.getWrappedLogger().log(Level.SEVERE, "Failed to save user " + uuid + " to database asynchronously", e);
                    }
                });
            } else {
                blockingSave();
            }
        } else {
            super.save();
        }
    }

    @Override
    public synchronized void blockingSave() {
        final DatabaseManager db = getDatabaseManager();
        if (db != null && db.isEnabled()) {
            try {
                saveToDb(db);
            } catch (Exception e) {
                Essentials.getWrappedLogger().log(Level.SEVERE, "Failed to save user " + uuid + " to database synchronously", e);
            }
        } else {
            super.blockingSave();
        }
    }
}
