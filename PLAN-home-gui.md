# Plan: Fitur Home GUI

## Overview

Fitur GUI untuk `/home` yang mendukung Java Edition (Bukkit Inventory GUI) dan Bedrock Edition (Floodgate Form API). Jika diaktifkan di config, command `/home` atau `/home <nama>` akan langsung membuka GUI.

---

## Arsitektur

### Config (`config.yml`)

```yaml
# Fitur Home GUI - Gunakan Bukkit Inventory GUI (Java) atau Floodgate Form API (Bedrock)
# Jika diaktifkan, /home akan membuka GUI alih-alih teleport langsung
home-gui:
  enabled: false
  # Material default untuk icon home (semua Material Minecraft bisa dipakai)
  default-icon: "CHEST"
```

### Settings (ISettings.java + Settings.java)

Tambah method:

- `boolean isHomeGuiEnabled()` → `config.getBoolean("home-gui.enabled", false)`
- `String getHomeGuiDefaultIcon()` → `config.getString("home-gui.default-icon", "CHEST")`

### Data Model (UserData.java)

Tambah field untuk icon per home:

- `Map<String, String> homeIcons()` → `Map<homeName, materialName>`
- `void setHomeIcon(String name, String material)` → simpan icon custom
- `String getHomeIcon(String name)` → ambil icon, default ke config
- `void delHomeIcon(String name)` → hapus icon saat home dihapus

### Floodgate Detection

```java
// Cek apakah player Bedrock
boolean isBedrockPlayer(Player player) {
    try {
        Class.forName("org.geysermc.floodgate.api.FloodgateApi");
        FloodgateApi api = FloodgateApi.getInstance();
        return api.isFloodgatePlayer(player.getUniqueId());
    } catch (ClassNotFoundException e) {
        return false;
    }
}
```

---

## Flow

### Java Edition (Bukkit Inventory GUI)

**Step 1: Home List GUI**

- Inventory dengan baris sesuai limit (chest size max 54)
- Slot kosong: Barrier item, klik untuk set home
- Slot terisi: Custom material icon / Default Chest icon, klik untuk kelola

**Step 2: Action GUI (saat home terisi diklik)**

- Chest 9 slot dengan aksi:
  - Wool Hijau: Teleport
  - Wool Merah: Delete
  - Wool Biru: Instruksi Rename
  - Wool Ungu: Instruksi /homeicon

### Bedrock Edition (Floodgate Form API)

**Step 1: Home List Form**

- `SimpleForm` dari FloodgateApi
- Tiap home = satu button dengan icon item + nama
- Empty slot = button "Set Home Here"
- Click → handle response

**Step 2: Action Form (saat home dipilih)**

- `SimpleForm` dengan opsi:
  - Teleport
  - Delete
  - Rename
  - Change Icon
- Click → handle response

---

## File yang Perlu Diubah

### Config

- `Essentials/src/main/resources/config.yml` - tambah section `home-gui`

### Settings

- `Essentials/src/main/java/com/earth2me/essentials/ISettings.java` - tambah method interface
- `Essentials/src/main/java/com/earth2me/essentials/Settings.java` - implement method

### User Data

- `Essentials/src/main/java/com/earth2me/essentials/UserData.java` - tambah homeIcons map + methods

### Commands

- `Essentials/src/main/java/com/earth2me/essentials/commands/Commandhome.java` - intercept saat GUI enabled
- `Essentials/src/main/java/com/earth2me/essentials/commands/Commandhomeicon.java` - tambah command ganti icon

### GUI Handler (Baru)

- `Essentials/src/main/java/com/earth2me/essentials/commands/HomeGuiHandler.java`
  - `openHomeGui(User user)` → deteksi Java/Bedrock, buka GUI sesuai
  - Bukkit Inventory listener temporer untuk Java
  - Floodgate form response handler reflective untuk Bedrock

### Plugin.yml

- Tambah floodgate sebagai optional dependency
- Tambah command `/homeicon`
- Tambah permission: `essentials.homegui`, `essentials.homeicon`

---

## Permissions

- `essentials.homegui` - akses fitur home GUI
- `essentials.homeicon` - akses ganti icon manual
- `essentials.sethome` - diperlukan untuk set home dari GUI
- `essentials.delhome` - diperlukan untuk delete home dari GUI
- `essentials.renamehome` - diperlukan untuk rename dari GUI

---

## Warmup Implementation

```java
// Di GUI handler, saat klik teleport:
Trade charge = new Trade("home", ess);
CompletableFuture<Boolean> future = new CompletableFuture<>();
future.thenAccept(success -> {
    if (success) user.sendTl("teleportHome", homeName);
});
user.getAsyncTeleport().teleport(homeLoc, charge, TeleportCause.COMMAND, future);
```

- Pakai `teleport-delay` dari config (sudah ada)
- Player bergerak/damage → cancel otomatis oleh Essentials teleport system

---

## Implementation Order

1. Config + Settings (ISettings, Settings.java, config.yml)
2. UserData (homeIcons)
3. HomeGuiHandler (Java Dialog API)
4. HomeGuiHandler (Bedrock Floodgate Form API)
5. HomeGuiListener (PlayerCustomClickEvent)
6. Commandhome.java intercept
7. Permissions di plugin.yml
8. Test Java + Bedrock
