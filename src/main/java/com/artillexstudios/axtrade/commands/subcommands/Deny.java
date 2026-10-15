package com.artillexstudios.axtrade.commands.subcommands;

import com.artillexstudios.axtrade.request.Requests;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public enum Deny {
    INSTANCE;

    public void execute(Player sender, @Nullable Player other) {
        Requests.deny(sender, other);
    }
}
