package com.artillexstudios.axtrade.commands.subcommands;

import com.artillexstudios.axtrade.network.CrossServerManager;
import com.artillexstudios.axtrade.request.Requests;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public enum Deny {
    INSTANCE;

    public void execute(Player sender, @Nullable String targetName) {
        if (CrossServerManager.isEnabled()) {
            if (targetName == null && Requests.getIncomingRequests(sender).size()
                    + CrossServerManager.getInstance().getIncomingSenderNames(sender).size() > 1) {
                MESSAGEUTILS.sendLang(sender, "request.multiple", Map.of());
                return;
            }
            if (CrossServerManager.getInstance().denyRequest(sender, targetName)) {
                return;
            }
        }

        Player local = targetName != null ? Bukkit.getPlayerExact(targetName) : null;
        if (targetName != null && local == null) {
            MESSAGEUTILS.sendLang(sender, "request.no-request", Map.of("%player%", targetName));
            return;
        }
        Requests.deny(sender, local);
    }
}
