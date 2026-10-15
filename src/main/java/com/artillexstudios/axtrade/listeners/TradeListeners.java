package com.artillexstudios.axtrade.listeners;

import com.artillexstudios.axtrade.request.Requests;
import com.artillexstudios.axtrade.trade.Trade;
import com.artillexstudios.axtrade.trade.Trades;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.bukkit.event.player.PlayerJoinEvent;

import static com.artillexstudios.axtrade.AxTrade.CONFIG;

public class TradeListeners implements Listener {

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        handleQuitTrade(event);
        handleQuitRequest(event);
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (com.artillexstudios.axtrade.network.CrossServerManager.isEnabled()) {
            com.artillexstudios.axtrade.network.CrossServerManager.getInstance().registerPlayer(event.getPlayer());
        }
    }

    public void handleQuitTrade(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Trade trade = Trades.getTrade(player);
        if (trade == null) return;
        trade.abort();
    }

    public void handleQuitRequest(@NotNull PlayerQuitEvent event) {
        Requests.removeFor(event.getPlayer());
        if (com.artillexstudios.axtrade.network.CrossServerManager.isEnabled()) {
            com.artillexstudios.axtrade.network.CrossServerManager.getInstance().unregisterPlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        cancelIfTrading(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(@NotNull EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        cancelIfTrading(player, event);
    }

    @EventHandler
    public void onArrowPickup(@NotNull PlayerPickupArrowEvent event) {
        cancelIfTrading(event.getPlayer(), event);
    }

    @EventHandler
    public void onMove(@NotNull PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Trade trade = Trades.getTrade(event.getPlayer());
        if (trade == null) return;
        if (!CONFIG.getBoolean("abort.move", true)) return;
        if (System.currentTimeMillis() - trade.getPrepTime() < 1_000L) return;
        if (event.getFrom().distanceSquared(event.getTo()) == 0) return;
        trade.abort();
    }

    @EventHandler
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Trade trade = Trades.getTrade(event.getPlayer());
        if (trade == null) return;
        if (!CONFIG.getBoolean("abort.interact", true)) return;
        if (System.currentTimeMillis() - trade.getPrepTime() < 1_000L) return;
        event.setCancelled(true);
        trade.abort();
    }

    @EventHandler
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        Trade trade = Trades.getTrade(event.getPlayer());
        if (trade == null) return;
        if (!CONFIG.getBoolean("abort.command", true)) return;
        event.setCancelled(true);
        trade.abort();
    }

    private void cancelIfTrading(Player player, Cancellable event) {
        Trade trade = Trades.getTrade(player);
        if (trade == null) return;
        event.setCancelled(true);
    }
}
