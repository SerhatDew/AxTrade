package com.artillexstudios.axtrade.commands.subcommands;

import com.artillexstudios.axtrade.network.CrossServerManager;
import com.artillexstudios.axtrade.network.NetworkPlayer;
import com.artillexstudios.axtrade.request.Requests;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public enum Request {
    INSTANCE;

    public void execute(Player sender, @Nullable String targetName) {
        if (targetName == null || targetName.isBlank()) {
            Help.INSTANCE.execute(sender);
            return;
        }

        Player local = Bukkit.getPlayerExact(targetName);
        if (local != null) {
            Requests.addRequest(sender, local);
            return;
        }

        if (CrossServerManager.isEnabled()) {
            NetworkPlayer target = CrossServerManager.getInstance().findPlayer(targetName);
            if (target != null) {
                if (target.uuid().equals(sender.getUniqueId())) {
                    MESSAGEUTILS.sendLang(sender, "request.cant-trade-self");
                    return;
                }
                CrossServerManager.getInstance().sendRequest(sender, target);
                return;
            }
        }

        MESSAGEUTILS.sendLang(sender, "request.not-accepting", Map.of("%player%", targetName));
    }
}
