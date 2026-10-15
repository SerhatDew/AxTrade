package com.artillexstudios.axtrade.commands.subcommands;

import com.artillexstudios.axtrade.network.CrossServerManager;
import com.artillexstudios.axtrade.request.Requests;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public enum Accept {
    INSTANCE;

    public void execute(Player sender, @Nullable String targetName) {
        if (CrossServerManager.isEnabled()) {
            if (CrossServerManager.getInstance().acceptRequest(sender, targetName)) {
                return;
            }
        }

        Player local = targetName != null ? Bukkit.getPlayerExact(targetName) : null;
        Requests.accept(sender, local);
    }
}
