package com.earth2me.essentials;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.ess3.provider.SchedulingProvider;
import net.essentialsx.api.v2.events.TeleportWarmupCancelledEvent;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import net.ess3.api.IEssentials;
import net.ess3.api.IUser;
import net.essentialsx.api.v2.events.TeleportWarmupCancelledEvent.CancelReason;
import com.earth2me.essentials.adventure.ComponentHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AsyncTimedTeleport implements Runnable {
    private static final double MOVE_CONSTANT = 0.3;
    private final IUser teleportOwner;
    private final IEssentials ess;
    private final AsyncTeleport teleport;
    private final UUID timer_teleportee;
    private final long timer_started; // time this task was initiated
    private final long timer_delay; // how long to delay the teleportPlayer
    private final CompletableFuture<Boolean> parentFuture;
    // note that I initially stored a clone of the location for reference, but...
    // when comparing locations, I got incorrect mismatches (rounding errors, looked like)
    // so, the X/Y/Z values are stored instead and rounded off
    private final long timer_initX;
    private final long timer_initY;
    private final long timer_initZ;
    private final ITarget timer_teleportTarget;
    private final boolean timer_respawn;
    private final boolean timer_canMove;
    private final Trade timer_chargeFor;
    private final TeleportCause timer_cause;
    private SchedulingProvider.EssentialsTask timer_task;
    private double timer_health;

    AsyncTimedTeleport(final IUser user, final IEssentials ess, final AsyncTeleport teleport, final long delay, final IUser teleportUser, final ITarget target, final Trade chargeFor, final TeleportCause cause, final boolean respawn) {
        this(user, ess, teleport, delay, null, teleportUser, target, chargeFor, cause, respawn);
    }

    AsyncTimedTeleport(final IUser user, final IEssentials ess, final AsyncTeleport teleport, final long delay, final CompletableFuture<Boolean> future, final IUser teleportUser, final ITarget target, final Trade chargeFor, final TeleportCause cause, final boolean respawn) {
        this.teleportOwner = user;
        this.ess = ess;
        this.teleport = teleport;
        this.timer_started = System.currentTimeMillis();
        this.timer_delay = delay;
        this.timer_health = teleportUser.getBase().getHealth();
        this.timer_initX = Math.round(teleportUser.getBase().getLocation().getX() * MOVE_CONSTANT);
        this.timer_initY = Math.round(teleportUser.getBase().getLocation().getY() * MOVE_CONSTANT);
        this.timer_initZ = Math.round(teleportUser.getBase().getLocation().getZ() * MOVE_CONSTANT);
        this.timer_teleportee = teleportUser.getBase().getUniqueId();
        this.timer_teleportTarget = target;
        this.timer_chargeFor = chargeFor;
        this.timer_cause = cause;
        this.timer_respawn = respawn;
        this.timer_canMove = user.isAuthorized("essentials.teleport.timer.move");

        timer_task = ess.runTaskTimerAsynchronously(this, 20, 20);

        if (future != null) {
            this.parentFuture = future;
            return;
        }

        final CompletableFuture<Boolean> cFuture = new CompletableFuture<>();
        cFuture.exceptionally(e -> {
            ess.showError(teleportOwner.getSource(), e, "\\ teleport");
            return false;
        });
        this.parentFuture = cFuture;
    }

    @Override
    public void run() {

        if (teleportOwner == null || !teleportOwner.getBase().isOnline() || teleportOwner.getBase().getLocation() == null) {
            cancelTimer(false);
            return;
        }

        if (!ess.isEnabled()) {
            cancelTimer(false);
            return;
        }

        final IUser teleportUser = ess.getUser(this.timer_teleportee);

        if (teleportUser == null || !teleportUser.getBase().isOnline()) {
            cancelTimer(false);
            return;
        }

        final Location currLocation = teleportUser.getBase().getLocation();
        if (currLocation == null) {
            cancelTimer(false);
            return;
        }

        if (!timer_canMove && (Math.round(currLocation.getX() * MOVE_CONSTANT) != timer_initX || Math.round(currLocation.getY() * MOVE_CONSTANT) != timer_initY || Math.round(currLocation.getZ() * MOVE_CONSTANT) != timer_initZ || teleportUser.getBase().getHealth() < timer_health)) {
            // user moved, cancelTimer teleportPlayer
            cancelTimer(true);
            return;
        }

        class DelayedTeleportTask implements Runnable {
            @Override
            public void run() {

                timer_health = teleportUser.getBase().getHealth(); // in case user healed, then later gets injured
                final long now = System.currentTimeMillis();
                if (now > timer_started + timer_delay) {
                    try {
                        teleport.cooldown(false);
                    } catch (final Throwable ex) {
                        teleportOwner.sendTl("cooldownWithMessage", ex.getMessage());
                        if (teleportOwner != teleportUser) {
                            teleportUser.sendTl("cooldownWithMessage", ex.getMessage());
                        }
                    }
                    try {
                        cancelTimer(false);
                        teleportUser.sendTl("teleportationCommencing");

                        final ISettings settings = ess.getSettings();
                        if (settings.isTeleportFeedbackSoundsEnabled()) {
                            final String successSound = settings.getTeleportFeedbackSoundSuccess();
                            final float successVol = settings.getTeleportFeedbackSoundSuccessVolume();
                            final float successPitch = settings.getTeleportFeedbackSoundSuccessPitch();
                            if (successSound != null && !successSound.isEmpty()) {
                                ess.scheduleEntityDelayedTask(teleportUser.getBase(), () -> {
                                    if (teleportUser.getBase().isOnline()) {
                                        teleportUser.getBase().playSound(teleportUser.getBase().getLocation(), successSound, successVol, successPitch);
                                    }
                                }, 2L);
                            }
                        }

                        if (timer_chargeFor != null) {
                            timer_chargeFor.isAffordableFor(teleportOwner);
                        }

                        if (timer_respawn) {
                            teleport.respawnNow(teleportUser, timer_cause, parentFuture);
                        } else {
                            teleport.nowAsync(teleportUser, timer_teleportTarget, timer_cause, parentFuture);
                        }
                        parentFuture.thenAccept(success -> {
                            if (timer_chargeFor != null) {
                                try {
                                    timer_chargeFor.charge(teleportOwner);
                                } catch (final ChargeException ex) {
                                    ess.showError(teleportOwner.getSource(), ex, "\\ teleport");
                                }
                            }
                        });

                    } catch (final Exception ex) {
                        ess.showError(teleportOwner.getSource(), ex, "\\ teleport");
                    }
                } else {
                    final long remainingMillis = (timer_started + timer_delay) - now;
                    final long remainingSecs = Math.max(1, (remainingMillis + 999) / 1000);
                    final ISettings settings = ess.getSettings();
                    if (settings.isTeleportFeedbackActionBarEnabled()) {
                        final String format = settings.getTeleportFeedbackActionBarFormat();
                        ess.getAdventureFacet().sendActionBar(teleportUser.getBase(), parseFormat(format, String.valueOf(remainingSecs)));
                    }
                    if (settings.isTeleportFeedbackSoundsEnabled()) {
                        final String warmupSound = settings.getTeleportFeedbackSoundWarmup();
                        final float warmupVol = settings.getTeleportFeedbackSoundWarmupVolume();
                        final float warmupPitch = settings.getTeleportFeedbackSoundWarmupPitch();
                        if (warmupSound != null && !warmupSound.isEmpty()) {
                            teleportUser.getBase().playSound(teleportUser.getBase().getLocation(), warmupSound, warmupVol, warmupPitch);
                        }
                    }
                }
            }
        }

        ess.scheduleEntityDelayedTask(teleportOwner.getBase(), new DelayedTeleportTask());
    }

    //If we need to cancelTimer a pending teleportPlayer call this method
    void cancelTimer(final boolean notifyUser) {
        if (timer_task == null) {
            return;
        }
        try {
            timer_task.cancel();

            final IUser teleportUser = ess.getUser(this.timer_teleportee);
            if (teleportUser != null && teleportUser.getBase() != null) {
                final TeleportWarmupCancelledEvent.CancelReason cancelReason = teleportUser.getBase().isOnline() ? CancelReason.MOVE : CancelReason.LEAVE;
                final TeleportWarmupCancelledEvent event = new TeleportWarmupCancelledEvent(teleportUser.getBase(), this.teleport.getTpType(), cancelReason, notifyUser);
                ess.getServer().getPluginManager().callEvent(event);
            }

            if (notifyUser) {
                teleportOwner.sendTl("pendingTeleportCancelled");
                if (timer_teleportee != null && !timer_teleportee.equals(teleportOwner.getBase().getUniqueId())) {
                    ess.getUser(timer_teleportee).sendTl("pendingTeleportCancelled");
                }
                final IUser tUser = ess.getUser(this.timer_teleportee);
                if (tUser != null && tUser.getBase() != null && tUser.getBase().isOnline()) {
                    final ISettings settings = ess.getSettings();
                    if (settings.isTeleportFeedbackSoundsEnabled()) {
                        final String cancelSound = settings.getTeleportFeedbackSoundCancel();
                        final float cancelVol = settings.getTeleportFeedbackSoundCancelVolume();
                        final float cancelPitch = settings.getTeleportFeedbackSoundCancelPitch();
                        if (cancelSound != null && !cancelSound.isEmpty()) {
                            tUser.getBase().playSound(tUser.getBase().getLocation(), cancelSound, cancelVol, cancelPitch);
                        }
                    }
                }
            }
        } finally {
            timer_task = null;
        }
    }

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private ComponentHolder parseFormat(final String format, final String remainingSecs) {
        final String replacedTime = format.replace("{time}", remainingSecs);
        final Matcher matcher = TAG_PATTERN.matcher(replacedTime);
        final List<String> tags = new ArrayList<>();
        final StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            tags.add(matcher.group());
            matcher.appendReplacement(sb, "%%TAG_" + (tags.size() - 1) + "%%");
        }
        matcher.appendTail(sb);
        final String legacyReplaced = com.earth2me.essentials.utils.FormatUtil.replaceFormat(sb.toString());
        String miniMessageStr = ess.getAdventureFacet().legacyToMini(legacyReplaced);
        for (int i = 0; i < tags.size(); i++) {
            miniMessageStr = miniMessageStr.replace("%%TAG_" + i + "%%", tags.get(i));
        }
        return ess.getAdventureFacet().deserializeMiniMessage(miniMessageStr);
    }
}
