package com.earth2me.essentials.commands;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.User;
import org.bukkit.Server;

public class Commandtpatoggle extends EssentialsToggleCommand {
    public Commandtpatoggle() {
        super("tpatoggle", "essentials.tpatoggle.others");
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
            enabled = !user.isTpaRequestsEnabled();
        }

        user.setTpaRequestsEnabled(enabled);

        user.sendTl(enabled ? "tpaRequestsEnabled" : "tpaRequestsDisabled");
        if (!sender.isPlayer() || !user.getBase().equals(sender.getPlayer())) {
            sender.sendTl(enabled ? "tpaRequestsEnabledFor" : "tpaRequestsDisabledFor", user.getDisplayName());
        }
    }
}
