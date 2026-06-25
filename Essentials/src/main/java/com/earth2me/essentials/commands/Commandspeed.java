package com.earth2me.essentials.commands;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.User;
import com.earth2me.essentials.utils.FloatUtil;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.ess3.api.TranslatableException;

public class Commandspeed extends EssentialsCommand {
    private static final List<String> types = Arrays.asList("walk", "fly", "1", "1.5", "1.75", "2");
    private static final List<String> speeds = Arrays.asList("1", "1.5", "1.75", "2");

    public Commandspeed() {
        super("speed");
    }

    @Override
    protected void run(final Server server, final CommandSource sender, final String commandLabel, final String[] args) throws Exception {
        if (args.length < 2) {
            throw new NotEnoughArgumentsException();
        }
        speedOtherPlayers(server, sender, isFlyMode(args[0]), true, getMoveSpeed(args[1]), args[2]);
    }

    @Override
    protected void run(final Server server, final User user, final String commandLabel, final String[] args) throws Exception {
        if (args.length < 1) {
            throw new NotEnoughArgumentsException();
        }

        final boolean isFly;
        final float speed;
        final boolean isBypass = user.isAuthorized("essentials.speed.bypass");
        if (args.length == 1) {
            final boolean inferredFly = isFlyAlias(commandLabel) ? true : isWalkAlias(commandLabel) ? false : user.getBase().isFlying();
            isFly = flyPermCheck(user, inferredFly);
            speed = getMoveSpeed(args[0]);
        } else {
            isFly = flyPermCheck(user, isFlyMode(args[0]));
            speed = getMoveSpeed(args[1]);
            if (args.length > 2 && user.isAuthorized("essentials.speed.others")) {
                if (args[2].trim().length() < 2) {
                    throw new PlayerNotFoundException();
                }
                speedOtherPlayers(server, user.getSource(), isFly, isBypass, speed, args[2]);
                return;
            }
        }

        final boolean isReset = speed == 1f;
        if (isFly) {
            user.getBase().setFlySpeed(getRealMoveSpeed(speed, true, isBypass));
            if (isReset) {
                user.sendTl("speedReset", user.playerTl("flying"));
            } else {
                user.sendTl("speedFly", speed);
            }
            return;
        }
        user.getBase().setWalkSpeed(getRealMoveSpeed(speed, false, isBypass));
        if (isReset) {
            user.sendTl("speedReset", user.playerTl("walking"));
        } else {
            user.sendTl("speedWalk", speed);
        }
    }

    private void speedOtherPlayers(final Server server, final CommandSource sender, final boolean isFly, final boolean isBypass, final float speed, final String name) throws PlayerNotFoundException {
        final boolean skipHidden = sender.isPlayer() && !ess.getUser(sender.getPlayer()).canInteractVanished();
        boolean foundUser = false;
        final List<Player> matchedPlayers = server.matchPlayer(name);
        final boolean isReset = speed == 1f;
        for (final Player matchPlayer : matchedPlayers) {
            final User player = ess.getUser(matchPlayer);
            if (skipHidden && player.isHidden(sender.getPlayer()) && player.isHiddenFrom(sender.getPlayer())) {
                continue;
            }
            foundUser = true;
            if (isFly) {
                matchPlayer.setFlySpeed(getRealMoveSpeed(speed, true, isBypass));
                if (isReset) {
                    sender.sendTl("speedReset", sender.tl("flying"));
                } else {
                    sender.sendTl("speedFly", speed);
                }
            } else {
                matchPlayer.setWalkSpeed(getRealMoveSpeed(speed, false, isBypass));
                if (isReset) {
                    sender.sendTl("speedReset", sender.tl("walking"));
                } else {
                    sender.sendTl("speedWalk", speed);
                }
            }
        }
        if (!foundUser) {
            throw new PlayerNotFoundException();
        }
    }

    private Boolean flyPermCheck(final User user, final boolean input) {
        final boolean canFly = user.isAuthorized("essentials.speed.fly");
        final boolean canWalk = user.isAuthorized("essentials.speed.walk");
        if (input && canFly || !input && canWalk || !canFly && !canWalk) {
            return input;
        } else return !canWalk;
    }

    private boolean isFlyAlias(final String label) {
        return label.contains("fly") || label.equalsIgnoreCase("fspeed") || label.equalsIgnoreCase("efspeed");
    }

    private boolean isWalkAlias(final String label) {
        return label.contains("walk") || label.equalsIgnoreCase("wspeed") || label.equalsIgnoreCase("ewspeed");
    }

    private boolean isFlyMode(final String modeString) throws Exception {
        if (modeString.contains("fly") || modeString.equalsIgnoreCase("f")) {
            return true;
        } else if (modeString.contains("walk") || modeString.contains("run") || modeString.equalsIgnoreCase("w") || modeString.equalsIgnoreCase("r")) {
            return false;
        } else {
            throw new TranslatableException("speedInvalidType");
        }
    }

    private float getMoveSpeed(final String moveSpeed) throws Exception {
        final float userSpeed;
        try {
            userSpeed = FloatUtil.parseFloat(moveSpeed);
            if (userSpeed > 10f || userSpeed < 0.0001f) {
                throw new TranslatableException("speedInvalidRange", 10);
            }
        } catch (final NumberFormatException e) {
            throw new TranslatableException("speedInvalidRange", 10);
        }
        return userSpeed;
    }

    private float getRealMoveSpeed(final float userSpeed, final boolean isFly, final boolean isBypass) {
        final float defaultSpeed = isFly ? 0.1f : 0.2f;
        float maxSpeed = 1f;
        if (!isBypass) {
            maxSpeed = (float) (isFly ? ess.getSettings().getMaxFlySpeed() : ess.getSettings().getMaxWalkSpeed());
        }

        if (userSpeed < 1f) {
            return defaultSpeed * userSpeed;
        } else {
            final float ratio = ((userSpeed - 1) / 9) * (maxSpeed - defaultSpeed);
            return ratio + defaultSpeed;
        }
    }

    @Override
    protected List<String> getTabCompleteOptions(final Server server, final CommandSource sender, final String commandLabel, final String[] args) {
        if (args.length == 1) {
            return types;
        } else if (args.length == 2) {
            return speeds;
        } else if (args.length == 3 && sender.isAuthorized("essentials.speed.others")) {
            return getPlayers(sender);
        } else {
            return Collections.emptyList();
        }
    }
}
