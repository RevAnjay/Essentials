package com.earth2me.essentials;

import net.ess3.api.IUser;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncTeleportTest {
    @Test
    void deadPlayerCannotOverwriteDeathLocation() {
        final IUser user = mock(IUser.class);
        final Player player = mock(Player.class);
        when(user.getBase()).thenReturn(player);
        when(user.isAuthorized("essentials.back.onteleport")).thenReturn(true);

        when(player.isDead()).thenReturn(true);
        assertFalse(AsyncTeleport.canRegisterBackLocation(user));

        when(player.isDead()).thenReturn(false);
        assertTrue(AsyncTeleport.canRegisterBackLocation(user));

        when(user.isAuthorized("essentials.back.onteleport")).thenReturn(false);
        assertFalse(AsyncTeleport.canRegisterBackLocation(user));
    }
}
