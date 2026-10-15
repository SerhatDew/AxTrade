package com.artillexstudios.axtrade.commands.subcommands;

import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axtrade.AxTrade;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.bukkit.exception.SenderNotPlayerException;

import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public enum Toggle {
    INSTANCE;

    public void execute(CommandSender sender, Player other) {
        if (other != null) sender = other;
        if (!(sender instanceof Player player)) {
            throw new SenderNotPlayerException();
        }
        AxTrade.getPreferences().toggle(player.getUniqueId()).whenComplete((disabled, error) ->
                Scheduler.get().run(() -> {
                    if (error != null) {
                        MESSAGEUTILS.sendLang(player, "toggle.save-failed");
                    } else {
                        MESSAGEUTILS.sendLang(player, disabled ? "toggle.disabled" : "toggle.enabled");
                    }
                }));
    }
}
