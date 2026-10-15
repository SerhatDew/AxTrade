package com.artillexstudios.axtrade.currency;

import com.artillexstudios.axtrade.hooks.currency.CurrencyHook;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class CurrencyProcessorTest {

    @Test
    void exceptionalDeductionReversesSuccessfulDeductionAndFailsCleanly() {
        UUID uuid = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) return uuid;
                    return null;
                }
        );

        CurrencyHook hook1 = mock(CurrencyHook.class);
        when(hook1.takeBalance(uuid, 100.0)).thenReturn(CompletableFuture.completedFuture(true));
        when(hook1.giveBalance(uuid, 100.0)).thenReturn(CompletableFuture.completedFuture(true));

        CurrencyHook hook2 = mock(CurrencyHook.class);
        CompletableFuture<Boolean> exceptional = new CompletableFuture<>();
        exceptional.completeExceptionally(new RuntimeException("Economy service unavailable"));
        when(hook2.takeBalance(uuid, 50.0)).thenReturn(exceptional);

        Set<Map.Entry<CurrencyHook, Double>> currencies = Set.of(
                Map.entry(hook1, 100.0),
                Map.entry(hook2, 50.0)
        );

        CurrencyProcessor processor = new CurrencyProcessor(player, currencies);
        Boolean result = processor.run().join();

        assertFalse(result, "Processor should report failure when any currency deduction fails exceptionally");
        verify(hook1, times(1)).giveBalance(uuid, 100.0);
        verify(hook2, never()).giveBalance(eq(uuid), anyDouble());
    }
}
