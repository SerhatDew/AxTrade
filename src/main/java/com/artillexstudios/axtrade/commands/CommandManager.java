package com.artillexstudios.axtrade.commands;

import com.artillexstudios.axtrade.AxTrade;
import com.artillexstudios.axtrade.request.Request;
import com.artillexstudios.axtrade.request.Requests;
import com.artillexstudios.axtrade.utils.CommandMessages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import revxrsal.commands.bukkit.BukkitCommandHandler;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.orphan.Orphans;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.artillexstudios.axtrade.AxTrade.CONFIG;

public final class CommandManager {
    private static BukkitCommandHandler handler = null;

    public static void load() {
        handler = BukkitCommandHandler.create(AxTrade.getInstance());
        handler.getBrigadier().ifPresent(brigadier -> brigadier.disableNativePlayerCompletion());

        handler.getTranslator().add(new CommandMessages());
        handler.setLocale(Locale.of("en", "US"));

        reload();
    }

    public static void reload() {
        handler.unregisterAllCommands();

        handler.getAutoCompleter().registerSuggestion("trade-players", (arguments, actor, command) -> visiblePlayers(actor));
        handler.getAutoCompleter().registerSuggestion("incoming-trade-players", (arguments, actor, command) -> incomingPlayers(actor));

        List<String> aliases = CONFIG.getStringList("command-aliases");
        if (!aliases.isEmpty()) {
            handler.register(Orphans.path(aliases.toArray(String[]::new)).handler(new Commands()));
        }

        handler.registerBrigadier();
    }

    private static List<String> visiblePlayers(CommandActor actor) {
        Player viewer = Bukkit.getPlayer(actor.getUniqueId());
        if (viewer == null || !viewer.isOnline()) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(viewer.getUniqueId()) || !viewer.canSee(player)) {
                continue;
            }
            names.add(player.getName());
        }
        if (com.artillexstudios.axtrade.network.CrossServerManager.isEnabled()) {
            names.addAll(com.artillexstudios.axtrade.network.CrossServerManager.getInstance().getNetworkPlayerNames(viewer));
        }
        return names;
    }

    private static List<String> incomingPlayers(CommandActor actor) {
        Player receiver = Bukkit.getPlayer(actor.getUniqueId());
        if (receiver == null || !receiver.isOnline()) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (Request request : Requests.getIncomingRequests(receiver)) {
            Player sender = request.getSender();
            if (sender.isOnline() && receiver.canSee(sender)) {
                names.add(sender.getName());
            }
        }
        if (com.artillexstudios.axtrade.network.CrossServerManager.isEnabled()) {
            names.addAll(com.artillexstudios.axtrade.network.CrossServerManager.getInstance().getIncomingSenderNames(receiver));
        }
        return names;

    }
}
