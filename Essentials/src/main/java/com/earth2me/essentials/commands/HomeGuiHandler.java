package com.earth2me.essentials.commands;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import com.earth2me.essentials.adventure.ComponentHolder;
import com.earth2me.essentials.config.EssentialsConfiguration;
import com.earth2me.essentials.utils.LocationUtil;
import com.earth2me.essentials.utils.NumberUtil;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.essentialsx.api.v2.events.HomeModifyEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class HomeGuiHandler implements Listener {

    public static class HomeGuiHolder implements InventoryHolder {
        private final Inventory inventory;
        private final int page;
        private final int pageCount;
        private final UUID targetUserId;
        private final boolean readOnly;

        public HomeGuiHolder(final int size, final Component title, final int page, final int pageCount,
                             final UUID targetUserId, final boolean readOnly) {
            this.inventory = Bukkit.createInventory(this, size, title);
            this.page = page;
            this.pageCount = pageCount;
            this.targetUserId = targetUserId;
            this.readOnly = readOnly;
        }

        public int getPage() { return page; }
        public int getPageCount() { return pageCount; }
        public UUID getTargetUserId() { return targetUserId; }
        public boolean isReadOnly() { return readOnly; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Essentials ess;
    private final EssentialsConfiguration guiConfig;

    // 2-click delete confirmation: UUID -> Map<slotNum, expiryTimestamp>
    private static final Map<UUID, Map<Integer, Long>> PENDING_DELETE_CONFIRMS = new ConcurrentHashMap<>();
    // delete-to-set-delay: UUID -> Map<slotNum, deletedAtTimestamp>
    private static final Map<UUID, Map<Integer, Long>> DELETE_SET_DELAYS = new ConcurrentHashMap<>();

    // Item cache: key="path|slot|homeName" -> cached ItemStack (cleared on reload)
    private final Map<String, ItemStack> itemCache = new java.util.concurrent.ConcurrentHashMap<>();

    // Configurable click types (loaded from config, defaults: LEFT for all)
    private java.util.EnumSet<ClickType> teleportClicks = java.util.EnumSet.of(ClickType.LEFT);
    private java.util.EnumSet<ClickType> setClicks = java.util.EnumSet.of(ClickType.LEFT);
    private java.util.EnumSet<ClickType> deleteClicks = java.util.EnumSet.of(ClickType.LEFT);

    // Reflection cache for setHideTooltip (Paper 1.20.5+)
    private java.lang.reflect.Method setHideTooltipMethod;
    private boolean hideTooltipChecked;

    private static final int MAX_GUI_SLOTS = 54;

    private Class<?> floodgateApiClass;
    private java.lang.reflect.Method floodgateGetInstance;
    private java.lang.reflect.Method floodgateIsPlayer;
    private boolean floodgateChecked;

    public HomeGuiHandler(final Essentials ess, final EssentialsConfiguration guiConfig) {
        this.ess = ess;
        this.guiConfig = guiConfig;
        loadClickTypes();
    }

    // Called by Essentials on reload to refresh config-derived state
    public void onReload() {
        itemCache.clear();
        loadClickTypes();
    }

    private void loadClickTypes() {
        teleportClicks = readClickTypes("homes.gui.actions.teleport", java.util.EnumSet.of(ClickType.LEFT));
        setClicks = readClickTypes("homes.gui.actions.set", java.util.EnumSet.of(ClickType.LEFT));
        deleteClicks = readClickTypes("homes.gui.actions.delete", java.util.EnumSet.of(ClickType.LEFT));
    }

    private java.util.EnumSet<ClickType> readClickTypes(final String path, final java.util.EnumSet<ClickType> defaults) {
        if (guiConfig == null) return defaults;
        final List<String> raw = getStringList(path);
        if (raw.isEmpty()) return defaults;
        final java.util.EnumSet<ClickType> result = java.util.EnumSet.noneOf(ClickType.class);
        for (final String entry : raw) {
            if (entry == null) continue;
            try { result.add(ClickType.valueOf(entry.trim().toUpperCase())); } catch (IllegalArgumentException ignored) { }
        }
        return result.isEmpty() ? defaults : result;
    }

    public boolean isTeleportClick(final ClickType click) { return teleportClicks.contains(click); }
    public boolean isSetClick(final ClickType click) { return setClicks.contains(click); }
    public boolean isDeleteClick(final ClickType click) { return deleteClicks.contains(click); }

    private boolean isBedrockPlayer(final UUID uuid) {
        if (!floodgateChecked) {
            try {
                floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateGetInstance = floodgateApiClass.getMethod("getInstance");
                floodgateIsPlayer = floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class);
            } catch (final Throwable ignored) { }
            floodgateChecked = true;
        }
        if (floodgateGetInstance == null) return false;
        try {
            return (boolean) floodgateIsPlayer.invoke(floodgateGetInstance.invoke(null), uuid);
        } catch (final Throwable ignored) { return false; }
    }

    public void openHomeGui(final User user) {
        openHomeGui(user, null);
    }

    // Open home GUI. If targetUser is non-null and viewer is authorized, show target's homes read-only.
    public void openHomeGui(final User viewer, final User targetUser) {
        final Player player = viewer.getBase();
        if (player == null) return;
        try {
            final boolean readOnly = targetUser != null && !targetUser.getBase().getUniqueId().equals(player.getUniqueId());
            if (readOnly && !viewer.isAuthorized("essentials.homes.others")) {
                viewer.sendTl("noPerm", "essentials.homes.others");
                return;
            }
            final User target = targetUser != null ? targetUser : viewer;
            if (isBedrockPlayer(player.getUniqueId())) {
                openBedrockHomeList(player, target);
            } else if (hasDialogSupport(player)) {
                openJavaHomeList(target);
            } else {
                openJavaInventoryHomeList(viewer, target, 0, readOnly);
            }
        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to open home GUI: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private boolean hasDialogSupport(final Player player) {
        try {
            Player.class.getMethod("showDialog", Dialog.class);
            return true;
        } catch (final NoSuchMethodException | NoClassDefFoundError ignored) { return false; }
    }

    // ------------------------------------------------------------------
    // Text / Item helpers
    // ------------------------------------------------------------------
    private Component parseText(final String text, final int slot, final String homeName) {
        return parseHolder(text, slot, homeName).getComponent() instanceof Component c ? c : Component.empty();
    }

    private ComponentHolder parseHolder(final String text, final int slot, final String homeName) {
        if (text == null) return ess.getAdventureFacet().deserializeMiniMessage("");
        final String replaced = text.replace("%slot%", String.valueOf(slot))
                .replace("%home%", homeName != null ? homeName : "")
                .replace("{slot}", String.valueOf(slot))
                .replace("{home}", homeName != null ? homeName : "");
        return ess.getAdventureFacet().deserializeMiniMessage(replaced);
    }

    private List<String> getStringList(final String path) {
        if (guiConfig == null) return new ArrayList<>();
        final List<String> list = guiConfig.getList(path, String.class);
        return list != null ? list : new ArrayList<>();
    }

    // Resolve item path: try page-specific override first, then global
    private String resolveItemPath(final String pageItemsPath, final String key) {
        if (pageItemsPath != null && guiConfig != null) {
            final String pagePath = pageItemsPath + "." + key;
            if (guiConfig.hasProperty(pagePath)) return pagePath;
        }
        return "homes.gui.items." + key;
    }

    private ItemStack buildItem(final String keyPath, final int slot, final String homeName) {
        // Cache key: path + slot + homeName (homeName affects placeholder substitution)
        final String cacheKey = keyPath + "|" + slot + "|" + (homeName != null ? homeName : "");
        final ItemStack cached = itemCache.get(cacheKey);
        if (cached != null) return cached.clone();

        final String matStr = guiConfig != null ? guiConfig.getString(keyPath + ".material", "STONE") : "STONE";
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) mat = Material.STONE;
        final ItemStack item = new ItemStack(mat);
        final ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            final String nameStr = guiConfig != null ? guiConfig.getString(keyPath + ".name", "") : "";
            meta.displayName(parseText(nameStr, slot, homeName));
            final List<String> loreStrings = getStringList(keyPath + ".lore");
            final List<Component> lore = new ArrayList<>();
            for (final String l : loreStrings) lore.add(parseText(l, slot, homeName));
            meta.lore(lore);
            // hide-tooltip: use Paper 1.20.5+ setHideTooltip if available, else fallback to addItemFlags
            if (guiConfig != null && guiConfig.getBoolean(keyPath + ".hide-tooltip", false)) {
                applyHideTooltip(meta);
            }
            item.setItemMeta(meta);
        }
        itemCache.put(cacheKey, item.clone());
        return item;
    }

    // Use Paper's setHideTooltip (1.20.5+) via reflection, fallback to addItemFlags on older versions
    private void applyHideTooltip(final ItemMeta meta) {
        if (!hideTooltipChecked) {
            try { setHideTooltipMethod = meta.getClass().getMethod("setHideTooltip", boolean.class); } catch (NoSuchMethodException ignored) { }
            hideTooltipChecked = true;
        }
        if (setHideTooltipMethod != null) {
            try { setHideTooltipMethod.invoke(meta, true); return; } catch (Throwable ignored) { }
        }
        // Fallback for older versions
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
    }

    // Build filler item for ingredient characters (e.g. ".")
    private ItemStack buildIngredientItem(final String ingredientPath) {
        return buildItem(ingredientPath, -1, null);
    }

    // ------------------------------------------------------------------
    // Page resolution (Jossentials-identical logic)
    // ------------------------------------------------------------------
    private static class PageDef {
        final String path;
        final String itemPath;
        final String navPath;
        final List<String> structure;
        final List<Integer> homeSlots;
        final List<Integer> actionSlots;
        final List<Integer> prevSlots;
        final List<Integer> nextSlots;
        final int startSlot;

        PageDef(String path, String itemPath, String navPath, List<String> structure,
                List<Integer> homeSlots, List<Integer> actionSlots,
                List<Integer> prevSlots, List<Integer> nextSlots, int startSlot) {
            this.path = path; this.itemPath = itemPath; this.navPath = navPath;
            this.structure = structure; this.homeSlots = homeSlots; this.actionSlots = actionSlots;
            this.prevSlots = prevSlots; this.nextSlots = nextSlots; this.startSlot = startSlot;
        }
    }

    private List<PageDef> resolvePages(final int maxSlots) {
        final List<PageDef> pages = new ArrayList<>();
        if (guiConfig == null) {
            pages.add(defaultPage());
            return pages;
        }
        final CommentedConfigurationNode pagesNode = guiConfig.getSection("homes.gui.pages");
        if (pagesNode == null || pagesNode.virtual()) {
            pages.add(defaultPage());
            return pages;
        }
        // Collect page keys sorted by numeric suffix
        final List<String> pageKeys = new ArrayList<>();
        pagesNode.childrenMap().forEach((k, v) -> pageKeys.add(String.valueOf(k)));
        pageKeys.sort((a, b) -> {
            int oa = pageOrder(a), ob = pageOrder(b);
            if (oa != ob) return Integer.compare(oa, ob);
            return a.compareTo(b);
        });

        int startSlot = 0;
        for (final String pageKey : pageKeys) {
            if (startSlot >= maxSlots) break;
            final String pagePath = "homes.gui.pages." + pageKey;
            final PageDef def = createPageDef(pagePath, startSlot);
            if (def.homeSlots.isEmpty()) continue;
            pages.add(def);
            startSlot += def.homeSlots.size();
        }
        if (pages.isEmpty()) pages.add(defaultPage());
        return pages;
    }

    private PageDef defaultPage() {
        final List<String> structure = Arrays.asList(".........", "..HHHHH..", "..AAAAA..", "P.......N");
        return new PageDef("homes.gui", "homes.gui.items", "homes.gui.navigation",
                structure, extractSlots(structure, 'H'), extractSlots(structure, 'A'),
                extractSlots(structure, 'P'), extractSlots(structure, 'N'), 0);
    }

    private PageDef createPageDef(final String pagePath, final int startSlot) {
        List<String> structure = getStringList(pagePath + ".structure");
        if (structure.isEmpty()) structure = Arrays.asList(".........", "..HHHHH..", "..AAAAA..", "P.......N");
        return new PageDef(pagePath, pagePath + ".items", pagePath + ".navigation",
                structure, extractSlots(structure, 'H'), extractSlots(structure, 'A'),
                extractSlots(structure, 'P'), extractSlots(structure, 'N'), startSlot);
    }

    private static List<Integer> extractSlots(final List<String> structure, final char target) {
        final List<Integer> indices = new ArrayList<>();
        for (int r = 0; r < structure.size(); r++) {
            final String row = structure.get(r).replace(" ", "");
            for (int c = 0; c < row.length() && c < 9; c++) {
                if (row.charAt(c) == target) indices.add(r * 9 + c);
            }
        }
        return indices;
    }

    private static int pageOrder(final String pageKey) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(pageKey);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------
    // Inventory GUI (Jossentials-style)
    // ------------------------------------------------------------------
    void openJavaInventoryHomeList(final User user, final int page) {
        openJavaInventoryHomeList(user, user, page, false);
    }

    void openJavaInventoryHomeList(final User viewer, final User target, final int page, final boolean readOnly) {
        final Player player = viewer.getBase();
        if (player == null) return;
        try {
            final int maxSlots = guiConfig != null ? guiConfig.getInt("homes.max-slots", 10) : 10;
            final List<PageDef> pages = resolvePages(maxSlots);
            final int currentPage = Math.max(0, Math.min(page, pages.size() - 1));
            final PageDef pdef = pages.get(currentPage);

            final int guiSize = pdef.structure.size() * 9;
            final String titlePath = readOnly ? "homes.gui.other-title" : "homes.gui.title";
            final String rawTitle = guiConfig != null ? guiConfig.getString(titlePath, "Personal Homes - Page %page%") : "Personal Homes";
            final Component titleComp = parseText(rawTitle.replace("%page%", String.valueOf(currentPage + 1)), 1, null);

            final HomeGuiHolder holder = new HomeGuiHolder(guiSize, titleComp, currentPage, pages.size(),
                    target.getBase().getUniqueId(), readOnly);
            final Inventory inv = holder.getInventory();

            // Fill ingredient slots (characters in structure that are not H/A/P/N)
            fillIngredients(inv, pdef);

            // Resolve home names for slots on this page
            final String[] slotHomeNames = resolveSlotHomeNames(target, maxSlots);

            // Track which absolute slot numbers appear on this page
            final int slotsThisPage = Math.min(pdef.homeSlots.size(), maxSlots - pdef.startSlot);
            final boolean showLocked = guiConfig == null || guiConfig.getBoolean("homes.gui.show-locked-slots", true);
            final Map<Integer, Long> playerConfirms = PENDING_DELETE_CONFIRMS.getOrDefault(player.getUniqueId(), new HashMap<>());
            final long now = System.currentTimeMillis();

            for (int i = 0; i < slotsThisPage; i++) {
                final int slotNum = pdef.startSlot + i + 1;
                final int hSlot = pdef.homeSlots.get(i);
                final String homeName = slotHomeNames[pdef.startSlot + i];
                final boolean set = homeName != null;
                final boolean hasPermission = readOnly || target.isAuthorized("essentials.home");

                final String keyPath;
                if (set && !hasPermission) {
                    keyPath = resolveItemPath(pdef.itemPath, "locked-set");
                } else if (!hasPermission) {
                    if (!showLocked) continue;
                    keyPath = resolveItemPath(pdef.itemPath, "locked");
                } else if (set) {
                    // Show delete-confirm on icon if pending
                    final Long confirmExpiry = playerConfirms.get(slotNum);
                    if (confirmExpiry != null && now < confirmExpiry) {
                        keyPath = resolveItemPath(pdef.itemPath, "delete-confirm");
                    } else {
                        keyPath = resolveItemPath(pdef.itemPath, "set");
                    }
                } else {
                    keyPath = resolveItemPath(pdef.itemPath, "empty");
                }
                inv.setItem(hSlot, buildItem(keyPath, slotNum, homeName));

                if (!readOnly && i < pdef.actionSlots.size()) {
                    final int aSlot = pdef.actionSlots.get(i);
                    final String aKeyPath;
                    if (!hasPermission) {
                        aKeyPath = resolveItemPath(pdef.itemPath, "action-locked");
                    } else if (!set) {
                        aKeyPath = resolveItemPath(pdef.itemPath, "action-set");
                    } else {
                        final Long confirmExpiry = playerConfirms.get(slotNum);
                        if (confirmExpiry != null && now < confirmExpiry) {
                            aKeyPath = resolveItemPath(pdef.itemPath, "action-delete-confirm");
                        } else {
                            aKeyPath = resolveItemPath(pdef.itemPath, "action-delete");
                        }
                    }
                    inv.setItem(aSlot, buildItem(aKeyPath, slotNum, homeName));
                }
            }

            // Navigation items
            for (final int idx : pdef.prevSlots) {
                final boolean enabled = currentPage > 0;
                final String navKey = enabled ? "previous" : "previous-disabled";
                inv.setItem(idx, buildItem(resolveNavPath(pdef.navPath, navKey, "homes.gui.navigation." + navKey), -1, null));
            }
            for (final int idx : pdef.nextSlots) {
                final boolean enabled = currentPage + 1 < pages.size();
                final String navKey = enabled ? "next" : "next-disabled";
                inv.setItem(idx, buildItem(resolveNavPath(pdef.navPath, navKey, "homes.gui.navigation." + navKey), -1, null));
            }

            player.openInventory(inv);
        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to open Java inventory home list: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private String resolveNavPath(final String pageNavPath, final String key, final String globalFallback) {
        if (pageNavPath != null && guiConfig != null) {
            final String pagePath = pageNavPath + "." + key;
            if (guiConfig.hasProperty(pagePath)) return pagePath;
        }
        // Try global path, fall back to enabled version if disabled not found
        if (guiConfig != null && guiConfig.hasProperty(globalFallback)) return globalFallback;
        // Fallback to non-disabled variant
        final String baseKey = key.replace("-disabled", "");
        final String basePath = pageNavPath != null ? pageNavPath + "." + baseKey : "homes.gui.navigation." + baseKey;
        return basePath;
    }

    private void fillIngredients(final Inventory inv, final PageDef pdef) {
        if (guiConfig == null) return;
        // Collect all characters used in structure
        final Map<Character, String> ingredientPaths = new HashMap<>();
        for (int r = 0; r < pdef.structure.size(); r++) {
            final String row = pdef.structure.get(r).replace(" ", "");
            for (int c = 0; c < row.length() && c < 9; c++) {
                final char ch = row.charAt(c);
                if (ch == 'H' || ch == 'A' || ch == 'P' || ch == 'N') continue;
                if (ingredientPaths.containsKey(ch)) continue;
                // Try page-specific ingredient, then global
                final String pageIngPath = pdef.path + ".ingredients." + ch;
                final String globalIngPath = "homes.gui.ingredients." + ch;
                if (guiConfig.hasProperty(pageIngPath)) {
                    ingredientPaths.put(ch, pageIngPath);
                } else if (guiConfig.hasProperty(globalIngPath)) {
                    ingredientPaths.put(ch, globalIngPath);
                }
            }
        }
        // Place ingredient items
        for (int r = 0; r < pdef.structure.size(); r++) {
            final String row = pdef.structure.get(r).replace(" ", "");
            for (int c = 0; c < row.length() && c < 9; c++) {
                final char ch = row.charAt(c);
                if (ch == 'H' || ch == 'A' || ch == 'P' || ch == 'N') continue;
                final int rawSlot = r * 9 + c;
                final String ingPath = ingredientPaths.get(ch);
                if (ingPath != null) {
                    final ItemStack filler = buildIngredientItem(ingPath);
                    if (filler.getType() != Material.AIR) {
                        inv.setItem(rawSlot, filler);
                    }
                }
            }
        }
    }

    private String[] resolveSlotHomeNames(final User user, final int maxSlots) {
        final String[] slotHomeNames = new String[maxSlots];
        final List<String> unmappedHomes = new ArrayList<>(user.getHomes());
        // 1. Restore from saved slots
        final java.util.Iterator<String> savedIt = unmappedHomes.iterator();
        while (savedIt.hasNext()) {
            final String name = savedIt.next();
            final Integer savedSlot = user.getHomeSlot(name);
            if (savedSlot != null && savedSlot >= 0 && savedSlot < maxSlots && slotHomeNames[savedSlot] == null) {
                slotHomeNames[savedSlot] = name;
                savedIt.remove();
            }
        }
        // 2. Auto-map "home" -> slot 0, "home2" -> slot 1, etc.
        final java.util.Iterator<String> it = unmappedHomes.iterator();
        while (it.hasNext()) {
            final String name = it.next();
            if ("home".equals(name)) {
                if (slotHomeNames[0] == null) { slotHomeNames[0] = name; it.remove(); }
            } else if (name.startsWith("home")) {
                try {
                    final int slot = Integer.parseInt(name.substring(4)) - 1;
                    if (slot >= 0 && slot < maxSlots && slotHomeNames[slot] == null) {
                        slotHomeNames[slot] = name; it.remove();
                    }
                } catch (final NumberFormatException ignored) { }
            }
        }
        // 3. Fill remaining unmapped homes into empty slots
        for (final String customHome : unmappedHomes) {
            for (int i = 0; i < maxSlots; i++) {
                if (slotHomeNames[i] == null) { slotHomeNames[i] = customHome; break; }
            }
        }
        return slotHomeNames;
    }

    // ------------------------------------------------------------------
    // Click handling
    // ------------------------------------------------------------------
    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        final Inventory top = event.getView().getTopInventory();
        final InventoryHolder holder = top.getHolder();
        if (!(holder instanceof HomeGuiHolder guiHolder)) return;

        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;

        final User viewer = ess.getUser(player);
        if (viewer == null) return;

        // Resolve target user: if readOnly, use the target stored in the holder
        final User user;
        if (guiHolder.isReadOnly() && guiHolder.getTargetUserId() != null) {
            final User target = ess.getUser(guiHolder.getTargetUserId());
            if (target == null) { viewer.sendTl("playerNotFound"); return; }
            user = target;
        } else {
            user = viewer;
        }

        final int rawSlot = event.getRawSlot();
        final int maxSlots = guiConfig != null ? guiConfig.getInt("homes.max-slots", 10) : 10;
        final List<PageDef> pages = resolvePages(maxSlots);
        final PageDef pdef = pages.get(guiHolder.getPage());

        // Check navigation clicks first
        if (pdef.prevSlots.contains(rawSlot)) {
            if (guiHolder.getPage() > 0) openJavaInventoryHomeList(viewer, user, guiHolder.getPage() - 1, guiHolder.isReadOnly());
            return;
        }
        if (pdef.nextSlots.contains(rawSlot)) {
            if (guiHolder.getPage() + 1 < pages.size()) openJavaInventoryHomeList(viewer, user, guiHolder.getPage() + 1, guiHolder.isReadOnly());
            return;
        }

        // In read-only mode, block all home/action clicks except teleport
        if (guiHolder.isReadOnly()) {
            final int hIndex = pdef.homeSlots.indexOf(rawSlot);
            if (hIndex >= 0) {
                final int slotNum = pdef.startSlot + hIndex + 1;
                final String targetHome = findHomeAtSlot(user, slotNum);
                if (targetHome != null && user.hasHome(targetHome) && isTeleportClick(event.getClick())) {
                    // Admin teleporting to target's home
                    if (viewer.isAuthorized("essentials.home.others")) {
                        player.closeInventory();
                        doTeleport(viewer, user, targetHome);
                    }
                }
            }
            return;
        }

        // Home slot click - respect configured click types
        final int hIndex = pdef.homeSlots.indexOf(rawSlot);
        if (hIndex >= 0) {
            final int slotNum = pdef.startSlot + hIndex + 1;
            final String targetHome = findHomeAtSlot(user, slotNum);
            if (targetHome != null && user.hasHome(targetHome) && isTeleportClick(event.getClick())) {
                player.closeInventory();
                doTeleport(user, targetHome);
            }
            return;
        }

        // Action slot click - respect configured click types
        final int aIndex = pdef.actionSlots.indexOf(rawSlot);
        if (aIndex >= 0) {
            final int slotNum = pdef.startSlot + aIndex + 1;
            final String targetHome = findHomeAtSlot(user, slotNum);

            if (targetHome == null || !user.hasHome(targetHome)) {
                // Empty slot -> set home (if set click type matches)
                if (!isSetClick(event.getClick())) return;
                // Check delete-to-set-delay
                final long delayMs = guiConfig != null ? guiConfig.getLong("homes.delete-to-set-delay-ms", 0) : 0;
                if (delayMs > 0) {
                    final Map<Integer, Long> delays = DELETE_SET_DELAYS.get(player.getUniqueId());
                    if (delays != null) {
                        final Long deletedAt = delays.get(slotNum);
                        if (deletedAt != null) {
                            final long remaining = delayMs - (System.currentTimeMillis() - deletedAt);
                            if (remaining > 0) {
                                user.sendComponent(parseHolder("<red>Please wait <gold>" + String.format(Locale.US, "%.1f", remaining / 1000.0) + "s</gold> before setting this home again.", slotNum, null));
                                return;
                            }
                            delays.remove(slotNum);
                            if (delays.isEmpty()) DELETE_SET_DELAYS.remove(player.getUniqueId());
                        }
                    }
                }
                doSetHome(user, slotNum - 1, () -> openJavaInventoryHomeList(user, guiHolder.getPage()));
            } else {
                // Home is set -> delete (if delete click type matches)
                if (!isDeleteClick(event.getClick())) return;
                // Delete home (2-click confirmation)
                final Map<Integer, Long> confirms = PENDING_DELETE_CONFIRMS.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
                final Long expiry = confirms.get(slotNum);
                final long now = System.currentTimeMillis();
                final boolean confirmEnabled = guiConfig == null || guiConfig.getBoolean("homes.delete-confirmation.enabled", true);
                final int windowSeconds = guiConfig != null ? guiConfig.getInt("homes.delete-confirmation.window-seconds", 5) : 5;

                if (confirmEnabled && (expiry == null || now > expiry)) {
                    confirms.put(slotNum, now + (windowSeconds * 1000L));
                    user.sendComponent(parseHolder("<yellow>Click again within " + windowSeconds + "s to delete home #" + slotNum, slotNum, targetHome));
                    openJavaInventoryHomeList(user, guiHolder.getPage());
                } else {
                    confirms.remove(slotNum);
                    if (confirms.isEmpty()) PENDING_DELETE_CONFIRMS.remove(player.getUniqueId());
                    // Mark delete-to-set-delay
                    final long delayMs = guiConfig != null ? guiConfig.getLong("homes.delete-to-set-delay-ms", 0) : 0;
                    if (delayMs > 0) {
                        DELETE_SET_DELAYS.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(slotNum, System.currentTimeMillis());
                    }
                    doDeleteHome(user, targetHome);
                    openJavaInventoryHomeList(user, guiHolder.getPage());
                }
            }
        }
    }

    private String findHomeAtSlot(final User user, final int slotNum) {
        for (final String h : user.getHomes()) {
            final Integer s = user.getHomeSlot(h);
            if (s != null && s == (slotNum - 1)) return h;
        }
        // Fallback to conventional naming
        final String convName = slotNum == 1 ? "home" : "home" + slotNum;
        if (user.hasHome(convName)) return convName;
        return null;
    }

    // ------------------------------------------------------------------
    // Name validation
    // ------------------------------------------------------------------
    private static boolean isValidHomeName(final String name) {
        if (name == null || name.isEmpty()) return false;
        if ("bed".equals(name) || NumberUtil.isInt(name)) return false;
        return true;
    }

    private static boolean isSafeGuiHomeName(final String name) {
        if (!isValidHomeName(name)) return false;
        return name.indexOf('/') < 0;
    }

    // ------------------------------------------------------------------
    // Paper Dialog GUI (when available)
    // ------------------------------------------------------------------
    void openJavaHomeList(final User user) {
        final Player player = user.getBase();
        if (player == null) return;
        try {
            final int maxHomes = Math.min(ess.getSettings().getHomeLimit(user), MAX_GUI_SLOTS);
            if (maxHomes <= 0) {
                user.sendTl("errorWithMessage", "Home limit is zero; cannot open home GUI.");
                return;
            }
            final List<DialogBody> bodyList = new ArrayList<>();
            bodyList.add(DialogBody.plainMessage(Component.text(
                    "You have " + user.getHomes().size() + "/" + maxHomes + " homes", NamedTextColor.GRAY)));
            final String[] slotHomeNames = resolveSlotHomeNames(user, maxHomes);
            final List<ActionButton> buttons = new ArrayList<>();
            for (int i = 0; i < maxHomes; i++) {
                final String homeName = slotHomeNames[i];
                if (homeName != null) {
                    buttons.add(ActionButton.create(
                            Component.text(homeName, NamedTextColor.GREEN),
                            Component.text("Click to manage"), 200,
                            DialogAction.customClick(Key.key("essentials", "homegui/select/" + homeName), null)));
                } else {
                    buttons.add(ActionButton.create(
                            Component.text("Set Home #" + (i + 1), NamedTextColor.GRAY),
                            Component.text("Click to set home"), 200,
                            DialogAction.customClick(Key.key("essentials", "homegui/set/" + i), null)));
                }
            }
            showDialog(player, Component.text("Your Homes"), bodyList, buttons);
        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to open Java home list: " + t.getMessage());
            t.printStackTrace();
        }
    }

    void openJavaActionDialog(final User user, final String homeName) {
        if (!user.hasHome(homeName)) return;
        final Player player = user.getBase();
        if (player == null) return;
        try {
            final List<DialogBody> bodyList = new ArrayList<>();
            bodyList.add(DialogBody.plainMessage(Component.text(homeName, NamedTextColor.GOLD)));
            bodyList.add(DialogBody.plainMessage(Component.text("Click an action below", NamedTextColor.GRAY)));
            final List<ActionButton> actions = new ArrayList<>();
            if (user.isAuthorized("essentials.home")) {
                actions.add(ActionButton.create(Component.text("Teleport", NamedTextColor.GREEN), null, 100,
                        DialogAction.customClick(Key.key("essentials", "homegui/teleport/" + homeName), null)));
            }
            if (user.isAuthorized("essentials.delhome")) {
                actions.add(ActionButton.create(Component.text("Delete", NamedTextColor.RED), null, 100,
                        DialogAction.customClick(Key.key("essentials", "homegui/delete/" + homeName), null)));
            }
            if (user.isAuthorized("essentials.renamehome")) {
                actions.add(ActionButton.create(Component.text("Rename", NamedTextColor.AQUA), null, 100,
                        DialogAction.customClick(Key.key("essentials", "homegui/rename/" + homeName), null)));
            }
            if (actions.isEmpty()) {
                user.sendTl("errorWithMessage", "You do not have permission to perform any home actions.");
                return;
            }
            showDialog(player, Component.text("Home: " + homeName), bodyList, actions);
        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to open Java action dialog: " + t.getMessage());
            t.printStackTrace();
        }
    }

    void openJavaRenameDialog(final User user, final String homeName) {
        try {
            if (!user.hasHome(homeName)) return;
            final DialogInput input = DialogInput.text("name", Component.text("New name for " + homeName)).build();
            final ActionButton yesBtn = ActionButton.create(Component.text("Rename", NamedTextColor.GREEN), null, 100,
                    DialogAction.customClick(Key.key("essentials", "homegui/dorename/" + homeName), null));
            final ActionButton noBtn = ActionButton.create(Component.text("Cancel", NamedTextColor.RED), null, 100, null);
            showConfirmDialog(user.getBase(), Component.text("Rename Home"), Arrays.asList(input), yesBtn, noBtn);
        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to open Java rename dialog: " + t.getMessage());
            t.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerCustomClick(final PlayerCustomClickEvent event) {
        try {
            final Key identifier = event.getIdentifier();
            if (!"essentials".equals(identifier.namespace())) return;
            final String fullPath = identifier.value();
            if (!fullPath.startsWith("homegui/")) return;
            final Player player = ((PlayerGameConnection) event.getCommonConnection()).getPlayer();
            final User user = ess.getUser(player);
            if (user == null) return;
            final String action = fullPath.substring(8);
            final int slashIdx = action.indexOf('/');
            final String op = slashIdx > 0 ? action.substring(0, slashIdx) : action;
            final String arg = slashIdx > 0 ? action.substring(slashIdx + 1) : "";
            final DialogResponseView view = event.getDialogResponseView();

            if ("select".equals(op)) {
                if (isSafeGuiHomeName(arg) && user.hasHome(arg)) openJavaActionDialog(user, arg);
                else { user.sendTl("invalidHome", arg); openJavaHomeList(user); }
            } else if ("set".equals(op)) {
                final int slot; try { slot = Integer.parseInt(arg); } catch (NumberFormatException ignored) { return; }
                doSetHome(user, slot, () -> openJavaHomeList(user));
            } else if ("teleport".equals(op)) {
                if (isSafeGuiHomeName(arg) && user.hasHome(arg)) doTeleport(user, arg);
                else { user.sendTl("invalidHome", arg); openJavaHomeList(user); }
            } else if ("delete".equals(op)) {
                if (isSafeGuiHomeName(arg) && user.hasHome(arg)) doDeleteHome(user, arg);
                else user.sendTl("invalidHome", arg);
                openJavaHomeList(user);
            } else if ("rename".equals(op)) {
                if (isSafeGuiHomeName(arg) && user.hasHome(arg)) openJavaRenameDialog(user, arg);
                else { user.sendTl("invalidHome", arg); openJavaHomeList(user); }
            } else if ("dorename".equals(op)) {
                if (view != null) {
                    final String newName = view.getText("name");
                    if (isSafeGuiHomeName(arg) && user.hasHome(arg)) doRenameHome(user, arg, newName);
                    else user.sendTl("invalidHome", arg);
                }
                openJavaHomeList(user);
            }
        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to handle Java dialog action: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    // Bedrock (Floodgate) forms
    // ------------------------------------------------------------------
    private void handleBedrockHomeClick(final Player player, final User user, final int slot, final String[] slotHomeNames, final int maxHomes) {
        if (slot < 0) return;
        final int slotNum = slot + 1;
        if (slot < maxHomes) {
            final String homeName = slotHomeNames[slot];
            if (homeName != null && user.hasHome(homeName)) {
                openBedrockActionForm(player, user, homeName);
            } else {
                // Empty slot -> set home
                handleBedrockSetHome(player, user, slot, slotHomeNames);
            }
        }
    }

    private void handleBedrockSetHome(final Player player, final User user, final int slotIndex, final String[] slotHomeNames) {
        // Check delete-to-set-delay
        final long delayMs = guiConfig != null ? guiConfig.getLong("homes.delete-to-set-delay-ms", 0) : 0;
        if (delayMs > 0) {
            final Map<Integer, Long> delays = DELETE_SET_DELAYS.get(player.getUniqueId());
            if (delays != null) {
                final Long deletedAt = delays.get(slotIndex + 1);
                if (deletedAt != null) {
                    final long remaining = delayMs - (System.currentTimeMillis() - deletedAt);
                    if (remaining > 0) {
                        player.sendMessage("Please wait " + String.format(Locale.US, "%.1f", remaining / 1000.0) + "s before setting this home again.");
                        openBedrockHomeList(player, user);
                        return;
                    }
                    delays.remove(slotIndex + 1);
                }
            }
        }
        if (!user.isAuthorized("essentials.sethome")) {
            user.sendTl("noPerm", "essentials.sethome");
            openBedrockHomeList(player, user);
            return;
        }
        final String name = "home" + (slotIndex + 1);
        doSetHomeDirect(user, name);
        if (user.hasHome(name)) user.setHomeSlot(name, slotIndex);
        openBedrockHomeList(player, user);
    }

    private void openBedrockHomeList(final Player player, final User user) {
        try {
            final int maxHomes = Math.min(ess.getSettings().getHomeLimit(user), MAX_GUI_SLOTS);
            final String[] slotHomeNames = resolveSlotHomeNames(user, maxHomes);
            final FloodgateForms.FormBuilder fb = FloodgateForms.simpleForm()
                    .title("Your Homes")
                    .content("Select a home slot:");
            for (int i = 0; i < maxHomes; i++) {
                final String homeName = slotHomeNames[i];
                if (homeName != null && user.hasHome(homeName)) {
                    final Location loc = user.getHome(homeName);
                    final String tip = loc != null ? loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() : "?";
                    fb.button(homeName, "textures/blocks/chest_front");
                } else {
                    fb.button("Empty Slot #" + (i + 1), "textures/blocks/barrier");
                }
            }
            fb.validResultHandler(res -> {
                final FloodgateForms.FormResponse r = new FloodgateForms.FormResponse(res);
                handleBedrockHomeClick(player, user, r.getClickedButtonId(), slotHomeNames, maxHomes);
            });
            final Object form = fb.build();
            FloodgateForms.send(form, player);
        } catch (final Throwable t) {
            ess.getLogger().severe("Bedrock home list failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private void openBedrockActionForm(final Player player, final User user, final String homeName) {
        try {
            final FloodgateForms.FormBuilder fb = FloodgateForms.simpleForm()
                    .title("Home: " + homeName)
                    .content("Choose an action:");
            if (user.isAuthorized("essentials.home")) fb.button("Teleport", "textures/items/ender_pearl");
            if (user.isAuthorized("essentials.delhome")) fb.button("Delete", "textures/blocks/barrier");
            if (user.isAuthorized("essentials.renamehome")) fb.button("Rename", "textures/items/sign");
            fb.validResultHandler(res -> {
                final FloodgateForms.FormResponse r = new FloodgateForms.FormResponse(res);
                final int id = r.getClickedButtonId();
                if (id < 0) return;
                int idx = 0;
                if (user.isAuthorized("essentials.home")) { if (id == idx) { doTeleport(user, homeName); return; } idx++; }
                if (user.isAuthorized("essentials.delhome")) {
                    if (id == idx) {
                        handleBedrockDeleteConfirm(player, user, homeName);
                        return;
                    }
                    idx++;
                }
                if (user.isAuthorized("essentials.renamehome")) { if (id == idx) { showBedrockRename(player, user, homeName); return; } }
            });
            final Object form = fb.build();
            FloodgateForms.send(form, player);
        } catch (final Throwable t) {
            ess.getLogger().severe("Bedrock action form failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private void handleBedrockDeleteConfirm(final Player player, final User user, final String homeName) {
        // Find slot number for this home
        int slotNum = -1;
        final Integer savedSlot = user.getHomeSlot(homeName);
        if (savedSlot != null) {
            slotNum = savedSlot + 1;
        } else {
            // Conventional name fallback
            if ("home".equals(homeName)) slotNum = 1;
            else if (homeName.startsWith("home")) {
                try { slotNum = Integer.parseInt(homeName.substring(4)); } catch (NumberFormatException ignored) {}
            }
        }

        final boolean confirmEnabled = guiConfig == null || guiConfig.getBoolean("homes.delete-confirmation.enabled", true);
        if (!confirmEnabled) {
            doBedrockDelete(player, user, homeName, slotNum);
            return;
        }

        final int windowSeconds = guiConfig != null ? guiConfig.getInt("homes.delete-confirmation.window-seconds", 5) : 5;
        final Map<Integer, Long> confirms = PENDING_DELETE_CONFIRMS.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        final Long expiry = confirms.get(slotNum);
        final long now = System.currentTimeMillis();

        if (expiry == null || now > expiry) {
            // First click -> show confirmation form
            confirms.put(slotNum, now + (windowSeconds * 1000L));
            player.sendMessage("Click Delete again within " + windowSeconds + "s to confirm deletion of home: " + homeName);
            openBedrockActionForm(player, user, homeName);
        } else {
            // Second click -> delete
            confirms.remove(slotNum);
            doBedrockDelete(player, user, homeName, slotNum);
        }
    }

    private void doBedrockDelete(final Player player, final User user, final String homeName, final int slotNum) {
        // Mark delete-to-set-delay
        final long delayMs = guiConfig != null ? guiConfig.getLong("homes.delete-to-set-delay-ms", 0) : 0;
        if (delayMs > 0 && slotNum > 0) {
            DELETE_SET_DELAYS.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(slotNum, System.currentTimeMillis());
        }
        doDeleteHome(user, homeName);
        openBedrockHomeList(player, user);
    }

    private void showBedrockRename(final Player player, final User user, final String homeName) {
        try {
            final FloodgateForms.FormBuilder fb = FloodgateForms.customForm()
                    .title("Rename Home");
            fb.input("New name:", homeName);
            fb.validResultHandler(res -> {
                final FloodgateForms.FormResponse r = new FloodgateForms.FormResponse(res);
                final String newName = r.getInput(0);
                if (newName != null && !newName.trim().isEmpty()) doRenameHome(user, homeName, newName.trim());
                openBedrockHomeList(player, user);
            });
            final Object form = fb.build();
            FloodgateForms.send(form, player);
        } catch (final Throwable t) {
            ess.getLogger().severe("Bedrock rename form failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    // Home operations
    // ------------------------------------------------------------------
    void doSetHome(final User user, final int slotIndex) { doSetHome(user, slotIndex, null); }

    void doSetHome(final User user, final int slotIndex, final Runnable after) {
        final String name = "home" + (slotIndex + 1);
        doSetHomeDirect(user, name);
        if (user.hasHome(name)) user.setHomeSlot(name, slotIndex);
        if (after != null) after.run();
    }

    void doTeleport(final User user, final String homeName) {
        doTeleport(user, user, homeName);
    }

    // Teleport viewer to targetUser's home (for read-only admin viewing)
    void doTeleport(final User viewer, final User targetUser, final String homeName) {
        if (!targetUser.hasHome(homeName)) { viewer.sendTl("invalidHome", homeName); return; }
        final Location homeLoc = targetUser.getHome(homeName);
        if (homeLoc == null || homeLoc.getWorld() == null) {
            viewer.sendTl("errorWithMessage", "Home location could not be loaded (world not available).");
            return;
        }
        if (viewer.getWorld() != homeLoc.getWorld() && ess.getSettings().isWorldHomePermissions()
                && !viewer.isAuthorized("essentials.worlds." + homeLoc.getWorld().getName())) {
            viewer.sendTl("noPerm", "essentials.worlds." + homeLoc.getWorld().getName());
            return;
        }
        viewer.getAsyncTeleport().teleport(homeLoc, null, TeleportCause.COMMAND, new CompletableFuture<>());
    }

    void doDeleteHome(final User user, final String homeName) {
        if (!user.hasHome(homeName)) { user.sendTl("invalidHome", homeName); return; }
        final HomeModifyEvent event = new HomeModifyEvent(user, user, homeName, user.getHome(homeName), false);
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        try {
            user.delHome(homeName);
            user.sendTl("deleteHome", homeName);
        } catch (final Exception ex) {
            user.sendTl("errorWithMessage", ex.getMessage());
        }
    }

    void doRenameHome(final User user, final String oldName, final String newName) {
        if (!user.hasHome(oldName) || newName == null || newName.trim().isEmpty()) return;
        final String trimmed = newName.trim();
        final String lowerNew = trimmed.toLowerCase(Locale.ENGLISH);
        if (!isValidHomeName(lowerNew) || NumberUtil.isInt(oldName)) { user.sendTl("invalidHomeName"); return; }
        final HomeModifyEvent event = new HomeModifyEvent(user, user, oldName, lowerNew, user.getHome(oldName));
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        try {
            user.renameHome(oldName, lowerNew);
            user.sendTl("homeRenamed", oldName, trimmed);
            user.setLastHomeConfirmation(null);
        } catch (final Exception ex) {
            user.sendTl("errorWithMessage", ex.getMessage());
        }
    }

    private void doSetHomeDirect(final User user, final String name) {
        if (!user.isAuthorized("essentials.sethome")) { user.sendTl("noPerm", "essentials.sethome"); return; }
        if (!isValidHomeName(name)) { user.sendTl("invalidHomeName"); return; }
        final Location loc = user.getLocation();
        if (!user.isAuthorized("essentials.sethome.multiple.unlimited")) {
            final int limit = ess.getSettings().getHomeLimit(user);
            if (user.getHomes().size() >= limit && !user.getHomes().contains(name)) { user.sendTl("maxHomes", limit); return; }
        }
        if ((!ess.getSettings().isTeleportSafetyEnabled() || !ess.getSettings().isForceDisableTeleportSafety())
                && LocationUtil.isBlockUnsafeForUser(ess, user, loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            user.sendTl("unsafeTeleportDestination", loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            return;
        }
        if (ess.getSettings().isConfirmHomeOverwrite() && user.hasHome(name)
                && (!name.equals(user.getLastHomeConfirmation())
                || name.equals(user.getLastHomeConfirmation()) && System.currentTimeMillis() - user.getLastHomeConfirmationTimestamp() > TimeUnit.MINUTES.toMillis(2))) {
            user.setLastHomeConfirmation(name);
            user.setLastHomeConfirmationTimestamp();
            user.sendTl("homeConfirmation", name);
            return;
        }
        final Location prevHomeLoc = user.getHome(name);
        final HomeModifyEvent event = prevHomeLoc == null
                ? new HomeModifyEvent(user, user, name, loc, true)
                : new HomeModifyEvent(user, user, name, prevHomeLoc, loc);
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        try {
            user.setHome(name, loc);
            user.sendTl("homeSet", loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), name);
            user.setLastHomeConfirmation(null);
        } catch (final Exception ex) {
            user.sendTl("errorWithMessage", ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Dialog helpers
    // ------------------------------------------------------------------
    private static void showDialog(final Player player, final Component title, final List<DialogBody> bodyList, final List<ActionButton> buttons) {
        final DialogType type = DialogType.multiAction(buttons).build();
        final DialogBase base = DialogBase.builder(title).body(bodyList).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(base).type(type)));
    }

    private static void showConfirmDialog(final Player player, final Component title, final List<DialogInput> inputs, final ActionButton yes, final ActionButton no) {
        final DialogType type = DialogType.multiAction(Arrays.asList(yes, no)).build();
        final DialogBase base = DialogBase.builder(title).inputs(inputs).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(base).type(type)));
    }

    // ------------------------------------------------------------------
    // Floodgate reflection
    // ------------------------------------------------------------------
    @SuppressWarnings("SameParameterValue")
    private static final class FloodgateForms {
        // Cumulus form classes
        private static Class<?> simpleFormClass, customFormClass;
        private static Class<?> simpleFormBuilderClass, customFormBuilderClass;
        private static Class<?> simpleFormResponseClass, customFormResponseClass;
        private static Class<?> formImageTypeClass;
        private static Class<?> formClass;
        private static Class<?> floodgateApiClass;

        // Methods
        private static java.lang.reflect.Method simpleBuilderMethod, customBuilderMethod;
        private static java.lang.reflect.Method titleMethod, contentMethod, buildMethod;
        private static java.lang.reflect.Method buttonWithImageMethod, buttonPlainMethod;
        private static java.lang.reflect.Method inputMethod;
        private static java.lang.reflect.Method validResultHandlerMethod;
        private static java.lang.reflect.Method sendFormMethod;
        private static java.lang.reflect.Method floodgateGetInstanceMethod;
        private static java.lang.reflect.Method getClickedIdMethod;
        private static java.lang.reflect.Method getInputMethod;

        // FormImage.Type.PATH enum value
        private static Object pathImageType;

        private static boolean loaded;
        private static boolean loadFailed;

        private static void ensureLoaded() throws Exception {
            if (loaded) return;
            if (loadFailed) throw new IllegalStateException("Floodgate Cumulus API not available");
            try {
                // Cumulus form classes (correct package: org.geysermc.cumulus, NOT org.geysermc.floodgate)
                simpleFormClass = Class.forName("org.geysermc.cumulus.form.SimpleForm");
                customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
                simpleFormBuilderClass = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder");
                customFormBuilderClass = Class.forName("org.geysermc.cumulus.form.CustomForm$Builder");
                simpleFormResponseClass = Class.forName("org.geysermc.cumulus.response.SimpleFormResponse");
                customFormResponseClass = Class.forName("org.geysermc.cumulus.response.CustomFormResponse");
                formClass = Class.forName("org.geysermc.cumulus.form.Form");

                // FormImage.Type enum
                formImageTypeClass = Class.forName("org.geysermc.cumulus.util.FormImage$Type");
                @SuppressWarnings({"unchecked", "rawtypes"})
                final Object pathVal = Enum.valueOf((Class<Enum>) formImageTypeClass, "PATH");
                pathImageType = pathVal;

                // FloodgateApi for sending forms
                floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateGetInstanceMethod = floodgateApiClass.getMethod("getInstance");
                sendFormMethod = floodgateApiClass.getMethod("sendForm", UUID.class, formClass);

                // SimpleForm.builder() and CustomForm.builder()
                simpleBuilderMethod = simpleFormClass.getMethod("builder");
                customBuilderMethod = customFormClass.getMethod("builder");

                // FormBuilder methods (inherited by SimpleForm.Builder and CustomForm.Builder)
                titleMethod = simpleFormBuilderClass.getMethod("title", String.class);
                buildMethod = simpleFormBuilderClass.getMethod("build");
                validResultHandlerMethod = simpleFormBuilderClass.getMethod("validResultHandler", java.util.function.Consumer.class);

                // SimpleForm.Builder-specific methods
                contentMethod = simpleFormBuilderClass.getMethod("content", String.class);
                buttonWithImageMethod = simpleFormBuilderClass.getMethod("button", String.class, formImageTypeClass, String.class);
                buttonPlainMethod = simpleFormBuilderClass.getMethod("button", String.class);

                // CustomForm.Builder-specific methods
                inputMethod = customFormBuilderClass.getMethod("input", String.class, String.class);

                // Response methods
                getClickedIdMethod = simpleFormResponseClass.getMethod("getClickedButtonId");
                getInputMethod = customFormResponseClass.getMethod("getInput", int.class);

                loaded = true;
            } catch (final Throwable t) {
                loadFailed = true;
                throw new Exception("Failed to load Floodgate Cumulus API: " + t.getMessage(), t);
            }
        }

        static boolean isAvailable() {
            try {
                ensureLoaded();
                return true;
            } catch (final Throwable ignored) {
                return false;
            }
        }

        static final class FormBuilder {
            private final Object builder;
            private final boolean isCustom;

            FormBuilder(final Object b, final boolean isCustom) {
                this.builder = b;
                this.isCustom = isCustom;
            }

            FormBuilder title(final String t) throws Exception {
                titleMethod.invoke(builder, t);
                return this;
            }

            FormBuilder content(final String c) throws Exception {
                contentMethod.invoke(builder, c);
                return this;
            }

            FormBuilder input(final String text, final String placeholder) throws Exception {
                inputMethod.invoke(builder, text, placeholder);
                return this;
            }

            FormBuilder button(final String text, final String imagePath) throws Exception {
                if (imagePath != null && !imagePath.isEmpty()) {
                    buttonWithImageMethod.invoke(builder, text, pathImageType, imagePath);
                } else {
                    buttonPlainMethod.invoke(builder, text);
                }
                return this;
            }

            FormBuilder validResultHandler(final java.util.function.Consumer<Object> handler) throws Exception {
                validResultHandlerMethod.invoke(builder, handler);
                return this;
            }

            Object build() throws Exception {
                return buildMethod.invoke(builder);
            }
        }

        static FormBuilder simpleForm() throws Exception {
            ensureLoaded();
            return new FormBuilder(simpleBuilderMethod.invoke(null), false);
        }

        static FormBuilder customForm() throws Exception {
            ensureLoaded();
            return new FormBuilder(customBuilderMethod.invoke(null), true);
        }

        interface ResponseHandler { void handle(FormResponse r); }

        static final class FormResponse {
            private final Object res;
            FormResponse(final Object r) { this.res = r; }

            int getClickedButtonId() {
                try {
                    return (int) getClickedIdMethod.invoke(res);
                } catch (final Throwable ignored) { return -1; }
            }

            String getInput(final int index) {
                try {
                    return (String) getInputMethod.invoke(res, index);
                } catch (final Throwable ignored) { return null; }
            }
        }

        static void send(final Object form, final Player player) {
            try {
                ensureLoaded();
                final Object api = floodgateGetInstanceMethod.invoke(null);
                sendFormMethod.invoke(api, player.getUniqueId(), form);
            } catch (final Throwable t) {
                player.sendMessage("Failed to open Floodgate form: " + t.getMessage());
            }
        }
    }
}
