package com.artillexstudios.axtrade.network;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossServerTest {

    @Test
    void packetSerializationRoundtripPreservesAllFields() {
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.REQUEST);
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        packet.setTradeId("trade-123");
        packet.setSenderUuid(senderId);
        packet.setSenderName("Serhatdew");
        packet.setSenderServer("leaf-1");
        packet.setReceiverUuid(receiverId);
        packet.setReceiverName("SerhatdewTest");
        packet.setTargetServer("leaf-2");
        packet.setConfirmSeconds(5);
        packet.setItems(List.of("itemA", "itemB"));

        String json = packet.toJson();
        assertNotNull(json);

        CrossServerPacket restored = CrossServerPacket.fromJson(json);
        assertEquals(CrossServerPacket.Type.REQUEST, restored.getType());
        assertEquals("trade-123", restored.getTradeId());
        assertEquals(senderId, restored.getSenderUuid());
        assertEquals("Serhatdew", restored.getSenderName());
        assertEquals("leaf-1", restored.getSenderServer());
        assertEquals(receiverId, restored.getReceiverUuid());
        assertEquals("SerhatdewTest", restored.getReceiverName());
        assertEquals("leaf-2", restored.getTargetServer());
        assertEquals(5, restored.getConfirmSeconds());
        assertEquals(List.of("itemA", "itemB"), restored.getItems());
    }

    @Test
    void networkPlayerPropertiesAndEquality() {
        UUID uuid = UUID.randomUUID();
        NetworkPlayer player = new NetworkPlayer(uuid, "PlayerOne", "leaf-1", 12345L);

        assertEquals(uuid, player.uuid());
        assertEquals("PlayerOne", player.name());
        assertEquals("leaf-1", player.serverId());
        assertEquals(12345L, player.lastSeen());
    }

    @Test
    void remoteTradePlayerProxyImplementsPlayerCorrectly() {
        UUID uuid = UUID.randomUUID();
        RemoteTradePlayer remotePlayer = new RemoteTradePlayer(null, uuid, "RemoteUser");

        assertEquals(uuid, remotePlayer.getRemoteUuid());
        assertEquals("RemoteUser", remotePlayer.getRemoteName());
        assertEquals(uuid, remotePlayer.getPlayer().getUniqueId());
        assertEquals("RemoteUser", remotePlayer.getPlayer().getName());
        assertNotNull(remotePlayer.getPlayer().getPlayer());
        assertEquals("RemoteUser", remotePlayer.getPlayer().getPlayer().getName());
        assertTrue(remotePlayer.getPlayer().isOnline());
    }

    @Test
    void remoteTradeGuiStoresAndFiltersItems() {
        RemoteTradePlayer remotePlayer = new RemoteTradePlayer(null, UUID.randomUUID(), "Test");
        RemoteTradeGui gui = remotePlayer.getRemoteTradeGui();

        gui.setRemoteItems(List.of(new ItemStack(Material.DIAMOND, 5)));
        List<ItemStack> items = gui.getItems(false);

        assertEquals(1, items.size());
        assertEquals(Material.DIAMOND, items.get(0).getType());
        assertEquals(5, items.get(0).getAmount());
    }
}
