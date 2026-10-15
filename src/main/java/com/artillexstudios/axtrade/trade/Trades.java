package com.artillexstudios.axtrade.trade;

import com.artillexstudios.axtrade.utils.SoundUtils;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public final class Trades {
    private static final List<Trade> trades = Collections.synchronizedList(new ArrayList<>());

    private Trades() {
    }

    @Nullable
    public static Trade addTrade(Player p1, Player p2) {
        synchronized (trades) {
            if (getTradeLocked(p1) != null || getTradeLocked(p2) != null) {
                return null;
            }

            Trade trade = new Trade(p1, p2);
            trades.add(trade);
            MESSAGEUTILS.sendLang(p1, "trade.started", Map.of("%player%", p2.getName()));
            MESSAGEUTILS.sendLang(p2, "trade.started", Map.of("%player%", p1.getName()));
            SoundUtils.playSound(p1, "started");
            SoundUtils.playSound(p2, "started");
            return trade;
        }
    }

    public static boolean addCrossServerTrade(Trade trade) {
        if (trade == null) return false;
        synchronized (trades) {
            trades.add(trade);
            return true;
        }
    }

    public static void removeTrade(Trade trade) {
        if (trade == null) {
            return;
        }
        trades.remove(trade);
    }

    public static List<Trade> getTrades() {
        synchronized (trades) {
            return List.copyOf(trades);
        }
    }

    public static void abortAll() {
        List<Trade> active;
        synchronized (trades) {
            active = List.copyOf(trades);
            trades.clear();
        }
        for (Trade trade : active) {
            if (!trade.isEnded()) trade.abort();
        }
    }

    public static boolean isTrading(Player player) {
        return getTrade(player) != null;
    }

    @Nullable
    public static Trade getTrade(Player player) {
        synchronized (trades) {
            return getTradeLocked(player);
        }
    }

    @Nullable
    private static Trade getTradeLocked(Player player) {
        for (Trade trade : trades) {
            if (trade.player1.getPlayer().getUniqueId().equals(player.getUniqueId())
                    || trade.player2.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                return trade;
            }
        }
        return null;
    }
}
