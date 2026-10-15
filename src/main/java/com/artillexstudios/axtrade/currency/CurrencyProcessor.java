package com.artillexstudios.axtrade.currency;

import com.artillexstudios.axtrade.hooks.currency.CurrencyHook;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class CurrencyProcessor {
    private final Player player;
    private final Set<Map.Entry<CurrencyHook, Double>> currencies;
    private final Map<Map.Entry<CurrencyHook, Double>, Boolean> states = new ConcurrentHashMap<>();

    public CurrencyProcessor(Player player, Set<Map.Entry<CurrencyHook, Double>> currencies) {
        this.player = player;
        this.currencies = currencies;
    }

    public CompletableFuture<Boolean> run() {
        if (currencies == null || currencies.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        List<CompletableFuture<Void>> steps = new ArrayList<>();
        AtomicBoolean anyErrors = new AtomicBoolean(false);

        for (Map.Entry<CurrencyHook, Double> entry : currencies) {
            CompletableFuture<Boolean> takeFuture;
            try {
                takeFuture = entry.getKey().takeBalance(player.getUniqueId(), entry.getValue());
                if (takeFuture == null) {
                    takeFuture = CompletableFuture.completedFuture(false);
                }
            } catch (Throwable t) {
                takeFuture = CompletableFuture.completedFuture(false);
            }

            CompletableFuture<Void> step = takeFuture.handle((success, throwable) -> {
                boolean ok = throwable == null && Boolean.TRUE.equals(success);
                states.put(entry, ok);
                if (!ok) {
                    anyErrors.set(true);
                }
                return null;
            });
            steps.add(step);
        }

        return CompletableFuture.allOf(steps.toArray(CompletableFuture[]::new)).thenApply(unused -> {
            if (anyErrors.get()) {
                reverse();
                return false;
            }
            return true;
        });
    }

    public CompletableFuture<Boolean> reverse() {
        List<CompletableFuture<Boolean>> refunds = new ArrayList<>();
        for (Map.Entry<Map.Entry<CurrencyHook, Double>, Boolean> entry : states.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            try {
                CompletableFuture<Boolean> give = entry.getKey().getKey().giveBalance(player.getUniqueId(), entry.getKey().getValue());
                if (give != null) {
                    refunds.add(give);
                }
            } catch (Throwable ignored) {
            }
        }
        if (refunds.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.allOf(refunds.toArray(CompletableFuture[]::new)).handle((v, t) -> t == null);
    }
}
