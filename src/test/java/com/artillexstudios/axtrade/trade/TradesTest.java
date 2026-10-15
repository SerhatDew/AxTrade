package com.artillexstudios.axtrade.trade;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradesTest {
    @Test
    void crossServerRegistrationRejectsEitherParticipantAlreadyTrading() {
        Player alice = player();
        Player bob = player();
        Player charlie = player();
        Trade active = new UnopenedTrade(alice, bob);
        Trade duplicateLocal = new UnopenedTrade(alice, charlie);
        Trade duplicateRemote = new UnopenedTrade(charlie, bob);
        try {
            assertTrue(Trades.addCrossServerTrade(active));
            assertFalse(Trades.addCrossServerTrade(duplicateLocal));
            assertFalse(Trades.addCrossServerTrade(duplicateRemote));
            assertSame(active, Trades.getTrade(alice));
            assertSame(active, Trades.getTrade(bob));
        } finally {
            Trades.removeTrade(active);
            Trades.removeTrade(duplicateLocal);
            Trades.removeTrade(duplicateRemote);
        }
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static class UnopenedTrade extends Trade {
        private UnopenedTrade(Player first, Player second) {
            player1 = new TradePlayer(this, first);
            player2 = new TradePlayer(this, second);
        }
    }
}
