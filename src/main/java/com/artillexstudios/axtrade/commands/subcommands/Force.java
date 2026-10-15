package com.artillexstudios.axtrade.commands.subcommands;

import com.artillexstudios.axtrade.trade.Trades;
import org.bukkit.entity.Player;

import java.util.Map;

import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public enum Force {
    INSTANCE;

    public void execute(Player sender, Player other) {
        if (sender.equals(other)) {
            MESSAGEUTILS.sendLang(sender, "request.cant-trade-self");
            return;
        }
        if (Trades.addTrade(sender, other) == null) {
            MESSAGEUTILS.sendLang(sender, "request.already-in-trade", Map.of("%player%", other.getName()));
        }
    }
}
