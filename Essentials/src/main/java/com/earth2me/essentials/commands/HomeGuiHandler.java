package com.earth2me.essentials.commands;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.Trade;
import com.earth2me.essentials.User;
import com.earth2me.essentials.utils.LocationUtil;
import com.earth2me.essentials.utils.NumberUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.connection.PlayerGameConnection;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import java.util.function.Consumer;
import net.ess3.api.events.UserTeleportHomeEvent;
import net.essentialsx.api.v2.events.HomeModifyEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class HomeGuiHandler implements Listener {

    private final Essentials ess;

    // Cap GUI slot count to avoid OOM / absurdly large dialogs when an admin
    // configures a very large home limit. Players can still own more homes;
    // they just won't all render in the GUI at once.
    private static final int MAX_GUI_SLOTS = 54;

    // ---------------------------------------------------------------
    // Floodgate detection (Bedrock)
    // ---------------------------------------------------------------
    private Class<?> floodgateApiClass;
    private java.lang.reflect.Method floodgateGetInstance;
    private java.lang.reflect.Method floodgateIsPlayer;
    private boolean floodgateChecked;

    public HomeGuiHandler(final Essentials ess) {
        this.ess = ess;
    }

    private boolean isBedrockPlayer(final UUID uuid) {
        if (!floodgateChecked) {
            try {
                floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateGetInstance = floodgateApiClass.getMethod("getInstance");
                floodgateIsPlayer = floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class);
            } catch (final Throwable ignored) {
            }
            floodgateChecked = true;
        }
        if (floodgateGetInstance == null) {
            return false;
        }
        try {
            return (boolean) floodgateIsPlayer.invoke(floodgateGetInstance.invoke(null), uuid);
        } catch (final Throwable ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    // Public entry point from /home
    // ---------------------------------------------------------------
    public void openHomeGui(final User user) {
        final Player player = user.getBase();
        if (player == null) {
            return;
        }
        try {
            if (isBedrockPlayer(player.getUniqueId())) {
                openBedrockHomeList(player, user);
            } else {
                openJavaHomeList(user);
            }
        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to open home GUI: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ---------------------------------------------------------------
    // Shared home-name validation (mirrors Commandsethome/Commandrenamehome)
    // ---------------------------------------------------------------
    private static boolean isValidHomeName(final String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        // "bed" and pure-integer names are reserved/invalid in Essentials
        if ("bed".equals(name) || NumberUtil.isInt(name)) {
            return false;
        }
        return true;
    }

    /**
     * Reject home names that contain characters which could break the customClick
     * Key path parsing or the user's home map. The Key value is split on '/',
     * so a '/' inside a home name would let a malicious client forge a different
     * op (e.g. "foo/teleport/bar" to teleport to "bar"). We also reject names
     * that are not safe to store.
     */
    private static boolean isSafeGuiHomeName(final String name) {
        if (!isValidHomeName(name)) {
            return false;
        }
        // The customClick identifier value is split on '/' in onPlayerCustomClick.
        // A '/' in a home name would corrupt parsing, so reject it.
        if (name.indexOf('/') >= 0) {
            return false;
        }
        // Reject names that are clearly not real homes (the action dialog only
        // lists actual home names, so an unknown op/name means a forged click).
        return true;
    }

    // ---------------------------------------------------------------
    // Java - Paper Dialog API
    // ---------------------------------------------------------------
    void openJavaHomeList(final User user) {
        final Player player = user.getBase();
        if (player == null) {
            return;
        }
        try {
            final int maxHomes = Math.min(ess.getSettings().getHomeLimit(user), MAX_GUI_SLOTS);
            if (maxHomes <= 0) {
                user.sendTl("errorWithMessage", "Home limit is zero; cannot open home GUI.");
                return;
            }

            final List<DialogBody> bodyList = new ArrayList<>();
            bodyList.add(DialogBody.plainMessage(Component.text(
                    "You have " + user.getHomes().size() + "/" + maxHomes + " homes", NamedTextColor.GRAY)));

            final String[] slotHomeNames = new String[maxHomes];
            final List<String> unmappedHomes = new ArrayList<>(user.getHomes());

            // 1. Map slot-like homes to their target slots
            final java.util.Iterator<String> it = unmappedHomes.iterator();
            while (it.hasNext()) {
                final String name = it.next();
                if ("home".equals(name)) {
                    if (slotHomeNames[0] == null) {
                        slotHomeNames[0] = name;
                        it.remove();
                    }
                } else if (name.startsWith("home")) {
                    try {
                        final int slot = Integer.parseInt(name.substring(4)) - 1;
                        if (slot >= 0 && slot < maxHomes && slotHomeNames[slot] == null) {
                            slotHomeNames[slot] = name;
                            it.remove();
                        }
                    } catch (final NumberFormatException ignored) {
                    }
                }
            }

            // 2. Put custom-named homes in remaining slots
            for (final String customHome : unmappedHomes) {
                for (int i = 0; i < maxHomes; i++) {
                    if (slotHomeNames[i] == null) {
                        slotHomeNames[i] = customHome;
                        break;
                    }
                }
            }

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
        if (!user.hasHome(homeName)) {
            return;
        }
        final Player player = user.getBase();
        if (player == null) {
            return;
        }

        try {
            final List<DialogBody> bodyList = new ArrayList<>();
            bodyList.add(DialogBody.plainMessage(Component.text(homeName, NamedTextColor.GOLD)));
            bodyList.add(DialogBody.plainMessage(Component.text("Click an action below", NamedTextColor.GRAY)));

            final List<ActionButton> actions = new ArrayList<>();
            if (user.isAuthorized("essentials.home")) {
                actions.add(ActionButton.create(
                        Component.text("Teleport", NamedTextColor.GREEN), null, 100,
                        DialogAction.customClick(Key.key("essentials", "homegui/teleport/" + homeName), null)));
            }
            if (user.isAuthorized("essentials.delhome")) {
                actions.add(ActionButton.create(
                        Component.text("Delete", NamedTextColor.RED), null, 100,
                        DialogAction.customClick(Key.key("essentials", "homegui/delete/" + homeName), null)));
            }
            if (user.isAuthorized("essentials.renamehome")) {
                actions.add(ActionButton.create(
                        Component.text("Rename", NamedTextColor.AQUA), null, 100,
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
            if (!user.hasHome(homeName)) {
                return;
            }
            final DialogInput input = DialogInput.text("name", Component.text("New name for " + homeName)).build();

            final ActionButton yesBtn = ActionButton.create(
                    Component.text("Rename", NamedTextColor.GREEN), null, 100,
                    DialogAction.customClick(Key.key("essentials", "homegui/dorename/" + homeName), null));
            final ActionButton noBtn = ActionButton.create(
                    Component.text("Cancel", NamedTextColor.RED), null, 100, null);

            showConfirmDialog(user.getBase(), Component.text("Rename Home"),
                    Arrays.asList(input), yesBtn, noBtn);
        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to open Java rename dialog: " + t.getMessage());
            t.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerCustomClick(final PlayerCustomClickEvent event) {
        try {
            final Key identifier = event.getIdentifier();
            if (!"essentials".equals(identifier.namespace())) {
                return;
            }
            final String fullPath = identifier.value();
            if (!fullPath.startsWith("homegui/")) {
                return;
            }

            final Player player = ((PlayerGameConnection) event.getCommonConnection()).getPlayer();
            final User user = ess.getUser(player);
            if (user == null) {
                return;
            }

            final String action = fullPath.substring(8);
            final int slashIdx = action.indexOf('/');
            final String op = slashIdx > 0 ? action.substring(0, slashIdx) : action;
            final String arg = slashIdx > 0 ? action.substring(slashIdx + 1) : "";

            final DialogResponseView view = event.getDialogResponseView();

            if ("select".equals(op)) {
                // Validate the home name to prevent forged clicks targeting
                // non-existent or reserved names.
                if (isSafeGuiHomeName(arg) && user.hasHome(arg)) {
                    openJavaActionDialog(user, arg);
                } else {
                    user.sendTl("invalidHome", arg);
                    openJavaHomeList(user);
                }
            } else if ("set".equals(op)) {
                final int slot;
                try {
                    slot = Integer.parseInt(arg);
                } catch (final NumberFormatException ignored) {
                    return;
                }
                doSetHome(user, slot, () -> openJavaHomeList(user));
            } else if ("teleport".equals(op)) {
                if (isSafeGuiHomeName(arg) && user.hasHome(arg)) {
                    doTeleport(user, arg);
                } else {
                    user.sendTl("invalidHome", arg);
                    openJavaHomeList(user);
                }
            } else if ("delete".equals(op)) {
                if (isSafeGuiHomeName(arg) && user.hasHome(arg)) {
                    doDeleteHome(user, arg);
                } else {
                    user.sendTl("invalidHome", arg);
                }
                openJavaHomeList(user);
            } else if ("rename".equals(op)) {
                if (isSafeGuiHomeName(arg) && user.hasHome(arg)) {
                    openJavaRenameDialog(user, arg);
                } else {
                    user.sendTl("invalidHome", arg);
                    openJavaHomeList(user);
                }
            } else if ("dorename".equals(op)) {
                if (view != null) {
                    final String newName = view.getText("name");
                    if (isSafeGuiHomeName(arg) && user.hasHome(arg)) {
                        doRenameHome(user, arg, newName);
                    } else {
                        user.sendTl("invalidHome", arg);
                    }
                }
                openJavaHomeList(user);
            }

        } catch (final Throwable t) {
            ess.getLogger().severe("Failed to handle Java dialog action: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ---------------------------------------------------------------
    // Bedrock (Floodgate)
    // ---------------------------------------------------------------
    private void handleBedrockHomeClick(final Player player, final User user, final int slot, final List<String> homeList) {
        if (slot < 0) {
            return; // form closed / cancelled
        }
        if (slot < homeList.size()) {
            openBedrockActionForm(player, user, homeList.get(slot));
        } else {
            // Empty slot clicked -> set a new home at the current location.
            if (user.isAuthorized("essentials.sethome")) {
                doSetHomeDirect(user, "home" + (homeList.size() + 1));
            } else {
                user.sendTl("noPerm", "essentials.sethome");
            }
            openBedrockHomeList(player, user);
        }
    }

    private void openBedrockHomeList(final Player player, final User user) {
        try {
            final int maxHomes = Math.min(ess.getSettings().getHomeLimit(user), MAX_GUI_SLOTS);
            final List<String> homeList = user.getHomes();
            final Object fb = FloodgateForms.simpleForm(ess)
                    .title("Your Homes")
                    .content("Select a home slot:")
                    .build();
            for (int i = 0; i < maxHomes; i++) {
                if (i < homeList.size()) {
                    final Location loc = user.getHome(homeList.get(i));
                    final String tip = loc != null
                            ? loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                            : "?";
                    FloodgateForms.addButton(fb, homeList.get(i), "textures/blocks/chest_front", tip);
                } else {
                    FloodgateForms.addButton(fb, "Empty Slot #" + (i + 1), "textures/blocks/barrier", "Click to set home");
                }
            }
            FloodgateForms.send(fb, player, r -> handleBedrockHomeClick(player, user, r.getClickedButtonId(), homeList));
        } catch (final Throwable t) {
            ess.getLogger().severe("Bedrock home list failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private void openBedrockActionForm(final Player player, final User user, final String homeName) {
        try {
            final Object fb = FloodgateForms.simpleForm(ess)
                    .title("Home: " + homeName)
                    .content("Choose an action:")
                    .build();
            if (user.isAuthorized("essentials.home")) {
                FloodgateForms.addButton(fb, "Teleport", "textures/items/ender_pearl", "Teleport");
            }
            if (user.isAuthorized("essentials.delhome")) {
                FloodgateForms.addButton(fb, "Delete", "textures/blocks/barrier", "Delete");
            }
            if (user.isAuthorized("essentials.renamehome")) {
                FloodgateForms.addButton(fb, "Rename", "textures/items/sign", "Rename");
            }
            FloodgateForms.send(fb, player, r -> {
                final int id = r.getClickedButtonId();
                if (id < 0) {
                    return; // closed
                }
                // Map button id to action accounting for permission-filtered list
                int idx = 0;
                if (user.isAuthorized("essentials.home")) {
                    if (id == idx) {
                        doTeleport(user, homeName);
                        return;
                    }
                    idx++;
                }
                if (user.isAuthorized("essentials.delhome")) {
                    if (id == idx) {
                        doDeleteHome(user, homeName);
                        openBedrockHomeList(player, user);
                        return;
                    }
                    idx++;
                }
                if (user.isAuthorized("essentials.renamehome")) {
                    if (id == idx) {
                        showBedrockRename(player, user, homeName);
                        return;
                    }
                }
            });
        } catch (final Throwable t) {
            ess.getLogger().severe("Bedrock action form failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private void showBedrockRename(final Player player, final User user, final String homeName) {
        try {
            final Object fb = FloodgateForms.customForm(ess).title("Rename Home").input("New name:", homeName).build();
            FloodgateForms.send(fb, player, r -> {
                final String newName = r.getInput(0);
                if (newName != null && !newName.trim().isEmpty()) {
                    doRenameHome(user, homeName, newName.trim());
                }
                openBedrockHomeList(player, user);
            });
        } catch (final Throwable t) {
            ess.getLogger().severe("Bedrock rename form failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ---
    // Actions shared Java + Bedrock
    // ---------------------------------------------------------------
    void doSetHome(final User user, final int slotIndex) {
        doSetHome(user, slotIndex, null);
    }

    void doSetHome(final User user, final int slotIndex, final Runnable after) {
        final String name = "home" + (slotIndex + 1);
        doSetHomeDirect(user, name);
        if (after != null) {
            after.run();
        }
    }

    void doTeleport(final User user, final int slotIndex) {
        doTeleport(user, "home" + (slotIndex + 1));
    }

    void doTeleport(final User user, final String homeName) {
        if (!user.hasHome(homeName)) {
            user.sendTl("invalidHome", homeName);
            return;
        }
        final Location homeLoc = user.getHome(homeName);
        if (homeLoc == null || homeLoc.getWorld() == null) {
            user.sendTl("errorWithMessage", "Home location could not be loaded (world not available).");
            return;
        }
        // World-specific permission check (mirrors Commandhome.goHome)
        if (user.getWorld() != homeLoc.getWorld() && ess.getSettings().isWorldHomePermissions()
                && !user.isAuthorized("essentials.worlds." + homeLoc.getWorld().getName())) {
            user.sendTl("noPerm", "essentials.worlds." + homeLoc.getWorld().getName());
            return;
        }
        // Fire the teleport-home event so other plugins can cancel it
        final UserTeleportHomeEvent event = new UserTeleportHomeEvent(user, homeName, homeLoc, UserTeleportHomeEvent.HomeType.HOME);
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        final Trade charge = new Trade("home", ess);
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.thenAccept(s -> {
            if (s) {
                user.sendTl("teleportHome", homeName);
            }
        });
        user.getAsyncTeleport().teleport(homeLoc, charge, TeleportCause.COMMAND, future);
    }

    void doDeleteHome(final User user, final int slotIndex) {
        doDeleteHome(user, "home" + (slotIndex + 1));
    }

    void doDeleteHome(final User user, final String homeName) {
        if (!user.hasHome(homeName)) {
            return;
        }
        // Fire HomeModifyEvent (mirrors Commanddelhome)
        final HomeModifyEvent event = new HomeModifyEvent(user, user, homeName, user.getHome(homeName), false);
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        try {
            user.delHome(homeName);
            user.sendTl("deleteHome", homeName);
        } catch (final Exception ex) {
            user.sendTl("errorWithMessage", ex.getMessage());
        }
    }

    void doRenameHome(final User user, final int slotIndex, final String newName) {
        doRenameHome(user, "home" + (slotIndex + 1), newName);
    }

    void doRenameHome(final User user, final String oldName, final String newName) {
        if (!user.hasHome(oldName) || newName == null || newName.trim().isEmpty()) {
            return;
        }
        final String trimmed = newName.trim();
        final String lowerNew = trimmed.toLowerCase(Locale.ENGLISH);
        // Validate names (mirrors Commandrenamehome)
        if (!isValidHomeName(lowerNew) || NumberUtil.isInt(oldName)) {
            user.sendTl("invalidHomeName");
            return;
        }
        // Fire HomeModifyEvent (mirrors Commandrenamehome)
        final HomeModifyEvent event = new HomeModifyEvent(user, user, oldName, lowerNew, user.getHome(oldName));
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        try {
            user.renameHome(oldName, lowerNew);
            user.sendTl("homeRenamed", oldName, trimmed);
            user.setLastHomeConfirmation(null);
        } catch (final Exception ex) {
            user.sendTl("errorWithMessage", ex.getMessage());
        }
    }

    private void doSetHomeDirect(final User user, final String name) {
        // Permission check (mirrors /sethome)
        if (!user.isAuthorized("essentials.sethome")) {
            user.sendTl("noPerm", "essentials.sethome");
            return;
        }
        // Validate name (mirrors Commandsethome)
        if (!isValidHomeName(name)) {
            user.sendTl("invalidHomeName");
            return;
        }
        final Location loc = user.getLocation();
        // Home limit check (mirrors Commandsethome.checkHomeLimit)
        if (!user.isAuthorized("essentials.sethome.multiple.unlimited")) {
            final int limit = ess.getSettings().getHomeLimit(user);
            if (user.getHomes().size() >= limit && !user.getHomes().contains(name)) {
                user.sendTl("maxHomes", limit);
                return;
            }
        }
        // Unsafe location check (mirrors Commandsethome)
        if ((!ess.getSettings().isTeleportSafetyEnabled() || !ess.getSettings().isForceDisableTeleportSafety())
                && LocationUtil.isBlockUnsafeForUser(ess, user, loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            user.sendTl("unsafeTeleportDestination", loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            return;
        }
        // Confirm overwrite (mirrors Commandsethome)
        if (ess.getSettings().isConfirmHomeOverwrite() && user.hasHome(name)
                && (!name.equals(user.getLastHomeConfirmation())
                || name.equals(user.getLastHomeConfirmation()) && System.currentTimeMillis() - user.getLastHomeConfirmationTimestamp() > TimeUnit.MINUTES.toMillis(2))) {
            user.setLastHomeConfirmation(name);
            user.setLastHomeConfirmationTimestamp();
            user.sendTl("homeConfirmation", name);
            return;
        }
        // Fire HomeModifyEvent (mirrors Commandsethome)
        final Location prevHomeLoc = user.getHome(name);
        final HomeModifyEvent event;
        if (prevHomeLoc == null) {
            event = new HomeModifyEvent(user, user, name, loc, true);
        } else {
            event = new HomeModifyEvent(user, user, name, prevHomeLoc, loc);
        }
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        try {
            user.setHome(name, loc);
            user.sendTl("homeSet", loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), name);
            user.setLastHomeConfirmation(null);
        } catch (final Exception ex) {
            user.sendTl("errorWithMessage", ex.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static void showDialog(final Player player, final Component title, final List<DialogBody> bodyList, final List<ActionButton> buttons) {
        final DialogType type = DialogType.multiAction(buttons).build();
        final DialogBase base = DialogBase.builder(title).body(bodyList).build();
        player.showDialog(Dialog.create(factory -> {
            final DialogRegistryEntry.Builder empty = factory.empty();
            empty.base(base).type(type);
        }));
    }

    private static void showConfirmDialog(final Player player, final Component title, final List<DialogInput> inputs, final ActionButton yes, final ActionButton no) {
        final DialogType type = DialogType.multiAction(Arrays.asList(yes, no)).build();
        final DialogBase base = DialogBase.builder(title).inputs(inputs).build();
        player.showDialog(Dialog.create(factory -> {
            final DialogRegistryEntry.Builder empty = factory.empty();
            empty.base(base).type(type);
        }));
    }

    // ---------------------------------------------------------------
    // Floodgate reflective wrapper (Bedrock)
    // ---------------------------------------------------------------
    @SuppressWarnings("SameParameterValue")
    private static final class FloodgateForms {
        private static Class<?> simpleFormClass;
        private static Class<?> customFormClass;
        private static Class<?> formBuilderClass;
        private static Class<?> formResponseClass;
        private static Class<?> formCallbackClass;
        private static java.lang.reflect.Method simpleBuilder;
        private static java.lang.reflect.Method customBuilder;
        private static java.lang.reflect.Method titleMethod;
        private static java.lang.reflect.Method contentMethod;
        private static java.lang.reflect.Method inputMethod;
        private static java.lang.reflect.Method buttonMethod;
        private static java.lang.reflect.Method buildMethod;
        private static java.lang.reflect.Method sendMethod;
        private static java.lang.reflect.Method getClickedId;
        private static java.lang.reflect.Method getInput0;
        private static boolean loaded;

        private static void ensureLoaded() throws Exception {
            if (loaded) {
                return;
            }
            simpleFormClass = Class.forName("org.geysermc.floodgate.api.form.SimpleForm");
            customFormClass = Class.forName("org.geysermc.floodgate.api.form.CustomForm");
            formBuilderClass = Class.forName("org.geysermc.floodgate.api.form.FormBuilder");
            formResponseClass = Class.forName("org.geysermc.floodgate.api.form.FormResponse");
            formCallbackClass = Class.forName("org.geysermc.floodgate.api.callback.FormCallback");
            simpleBuilder = simpleFormClass.getMethod("builder");
            customBuilder = customFormClass.getMethod("builder");
            titleMethod = formBuilderClass.getMethod("title", String.class);
            contentMethod = formBuilderClass.getMethod("content", String.class);
            buildMethod = formBuilderClass.getMethod("build");
            buttonMethod = formBuilderClass.getMethod("button", String.class, String.class, String.class);
            inputMethod = formBuilderClass.getMethod("input", String.class, String.class);
            sendMethod = formBuilderClass.getMethod("send", Player.class, formCallbackClass);
            getClickedId = formResponseClass.getMethod("getClickedButtonId");
            getInput0 = formResponseClass.getMethod("getInput", int.class);
            loaded = true;
        }

        static final class FormBuilder {
            private final Object builder;

            FormBuilder(final Object b) {
                this.builder = b;
            }

            FormBuilder title(final String t) throws Exception {
                titleMethod.invoke(builder, t);
                return this;
            }

            FormBuilder content(final String c) throws Exception {
                contentMethod.invoke(builder, c);
                return this;
            }

            FormBuilder input(final String l, final String d) throws Exception {
                inputMethod.invoke(builder, l, d);
                return this;
            }

            Object build() throws Exception {
                return buildMethod.invoke(builder);
            }
        }

        static FormBuilder simpleForm(final Essentials ess) throws Exception {
            ensureLoaded();
            return new FormBuilder(simpleBuilder.invoke(null));
        }

        static FormBuilder customForm(final Essentials ess) throws Exception {
            ensureLoaded();
            return new FormBuilder(customBuilder.invoke(null));
        }

        static void addButton(final Object fb, final String label, final String icon, final String tip) throws Exception {
            buttonMethod.invoke(fb, label, icon, tip);
        }

        static void send(final Object form, final Player player, final Consumer<FormResponse> cb) throws Exception {
            final Object proxy = java.lang.reflect.Proxy.newProxyInstance(formCallbackClass.getClassLoader(), new Class<?>[]{formCallbackClass},
                    (obj, method, args) -> {
                        if ("onResponse".equals(method.getName()) && args.length >= 2 && args[1] != null) {
                            cb.accept(new FormResponse(args[1]));
                        }
                        return null;
                    });
            sendMethod.invoke(form, player, proxy);
        }

        static final class FormResponse {
            private final Object response;

            FormResponse(final Object r) {
                this.response = r;
            }

            int getClickedButtonId() {
                try {
                    return (int) getClickedId.invoke(response);
                } catch (final Exception e) {
                    return -1;
                }
            }

            String getInput(final int idx) {
                try {
                    return (String) getInput0.invoke(response, idx);
                } catch (final Exception e) {
                    return null;
                }
            }
        }
    }
}
