package com.earth2me.essentials.commands;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.User;
import net.ess3.api.events.FlyStatusChangeEvent;
import org.bukkit.Server;

public class Commandfly extends EssentialsToggleCommand {
    public Commandfly() {
        super("fly", "essentials.fly.others");
    }

    @Override
    protected void run(final Server server, final CommandSource sender, final String commandLabel, final String[] args) throws Exception {
        toggleOtherPlayers(server, sender, args);
    }

    @Override
    protected void run(final Server server, final User user, final String commandLabel, final String[] args) throws Exception {
        handleToggleWithArgs(server, user, args);
    }

    @Override
    protected void togglePlayer(final CommandSource sender, final User user, Boolean enabled) {
        if (enabled == null) {
            enabled = !user.getBase().getAllowFlight();
        }

        final FlyStatusChangeEvent event = new FlyStatusChangeEvent(user, sender.isPlayer() ? ess.getUser(sender.getPlayer()) : null, enabled);
        ess.getServer().getPluginManager().callEvent(event);

        if (!event.isCancelled()) {
            user.getBase().setFallDistance(0f);
            user.getBase().setAllowFlight(enabled);

            user.setFlyModeEnabled(enabled);

            if (!user.getBase().getAllowFlight()) {
                user.getBase().setFlying(false);
            }

            final boolean isSelf = sender.isPlayer() && sender.getPlayer().equals(user.getBase());
            if (isSelf) {
                if (enabled) {
                    user.sendTl("flyEnabled");
                } else {
                    user.sendTl("flyDisabled");
                }
            } else {
                if (enabled) {
                    user.sendTl("flyEnabled");
                    sender.sendTl("flyEnabledOthers", user.getDisplayName());
                } else {
                    user.sendTl("flyDisabled");
                    sender.sendTl("flyDisabledOthers", user.getDisplayName());
                }
            }
        }
    }
}
