package com.earth2me.essentials.commands;

import com.earth2me.essentials.User;
import org.bukkit.Server;

public class Commandtogglehomegui extends EssentialsCommand {
    public Commandtogglehomegui() {
        super("togglehomegui");
    }

    @Override
    public void run(final Server server, final User user, final String commandLabel, final String[] args) throws Exception {
        if (!ess.getSettings().isHomeGuiEnabled()) {
            user.sendTl("homeGuiDisabled");
            return;
        }
        final boolean current = user.isHomeGuiEnabled();
        user.setHomeGuiEnabled(!current);
        user.sendTl(current ? "homeGuiTurnedOff" : "homeGuiTurnedOn");
    }
}
