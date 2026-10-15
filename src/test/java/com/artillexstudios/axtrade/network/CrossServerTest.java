package com.artillexstudios.axtrade.network;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossServerTest {


    @Test
    void packetValidationRejectsMissingPeerIdentity() {
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.REQUEST);
        packet.setSenderUuid(UUID.randomUUID());
        packet.setSenderName("sender");
        packet.setReceiverUuid(UUID.randomUUID());
        packet.setReceiverName("receiver");
        packet.setTargetServer("leaf-2");
        packet.setSenderServer("leaf-1");
        assertTrue(packet.isWellFormed());

        packet.setSenderServer(null);
        assertFalse(packet.isWellFormed());

    }

    @Test
    void strictItemDecodingRejectsCorruptPayloadInsteadOfTreatingItAsAir() {
        assertNull(ItemSerialization.deserializeListStrict(List.of("not-base64")));
        assertTrue(ItemSerialization.deserializeListStrict(List.of()).isEmpty());
    }

    @Test
    void settlementPacketsRequireThePreparedOfferDigest() {
        CrossServerPacket packet = sessionPacket(CrossServerPacket.Type.COMMIT_PREPARE);
        assertFalse(packet.isWellFormed());
        packet.setOfferDigest("prepared-offer");
        assertTrue(packet.isWellFormed());
        packet.setTradeId(null);
        assertFalse(packet.isWellFormed());
    }

    @Test
    void currencyUpdatesAllowRemovalButRejectNonFiniteBalances() {
        CrossServerPacket packet = sessionPacket(CrossServerPacket.Type.TRADE_CURRENCY);
        packet.setCurrencyName("vault");
        packet.setCurrencyAmount(0);
        assertTrue(packet.isWellFormed());
        packet.setCurrencyAmount(Double.POSITIVE_INFINITY);
        assertFalse(packet.isWellFormed());
    }

    @Test
    @SuppressWarnings("unchecked")
    void remoteRequestCannotBeConsumedByAnotherReceiver() throws Exception {
        CrossServerManager manager = new CrossServerManager(null, "leaf-2", "test", null);
        Player recipient = mock(Player.class);
        Player unrelated = mock(Player.class);
        UUID recipientId = UUID.randomUUID();
        when(recipient.getUniqueId()).thenReturn(recipientId);
        when(unrelated.getUniqueId()).thenReturn(UUID.randomUUID());
        CrossServerPacket request = new CrossServerPacket(CrossServerPacket.Type.REQUEST);
        request.setSenderUuid(UUID.randomUUID());
        request.setSenderName("sender");
        request.setReceiverUuid(recipientId);
        request.setReceiverName("recipient");
        request.setSenderServer("leaf-1");
        request.setTargetServer("leaf-2");
        var requestsField = CrossServerManager.class.getDeclaredField("incomingRequests");
        requestsField.setAccessible(true);
        Map<String, CrossServerPacket> incoming =
                (Map<String, CrossServerPacket>) requestsField.get(manager);
        incoming.put(request.getSenderUuid() + ":" + recipientId, request);

        try {
            assertFalse(manager.acceptRequest(unrelated, "sender"));
            assertFalse(manager.denyRequest(unrelated, null));
            assertEquals(List.of("sender"), manager.getIncomingSenderNames(recipient));
        } finally {
            var executorField = CrossServerManager.class.getDeclaredField("publishExecutor");
            executorField.setAccessible(true);
            ((ExecutorService) executorField.get(manager)).shutdownNow();
        }
    }

    private static CrossServerPacket sessionPacket(CrossServerPacket.Type type) {
        CrossServerPacket packet = new CrossServerPacket(type);
        packet.setSenderUuid(UUID.randomUUID());
        packet.setSenderServer("leaf-1");
        packet.setTargetServer("leaf-2");
        packet.setTradeId("trade-1");
        return packet;
    }

}
