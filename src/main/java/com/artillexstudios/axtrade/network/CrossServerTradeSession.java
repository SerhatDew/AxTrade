package com.artillexstudios.axtrade.network;

import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axtrade.trade.Trade;
import com.artillexstudios.axtrade.trade.TradePlayer;
import com.artillexstudios.axtrade.trade.Trades;
import com.artillexstudios.axtrade.utils.HistoryUtils;
import com.artillexstudios.axtrade.utils.SoundUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public class CrossServerTradeSession extends Trade {
    private final String tradeId;
    private final String remoteServer;
    private final CrossServerManager manager;
    private final RemoteTradePlayer remoteTradePlayer;
    private final List<ItemStack> escrowItems = Collections.synchronizedList(new ArrayList<>());
    private final List<ItemStack> lastSentItems = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean localPrepared = new AtomicBoolean(false);
    private final AtomicBoolean remotePrepared = new AtomicBoolean(false);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private volatile boolean endedSession = false;

    public CrossServerTradeSession(String tradeId, Player localPlayer, UUID remoteUuid, String remoteName,
                                   String remoteServer, CrossServerManager manager) {
        super();
        this.tradeId = tradeId;
        this.remoteServer = remoteServer;
        this.manager = manager;

        this.player1 = new TradePlayer(this, localPlayer);
        this.remoteTradePlayer = new RemoteTradePlayer(this, remoteUuid, remoteName);
        this.player2 = this.remoteTradePlayer;

        this.player1.setOtherPlayer(this.remoteTradePlayer);
        this.remoteTradePlayer.setOtherPlayer(this.player1);

        HistoryUtils.writeToHistory(String.format("Cross-Server Started [%s]: %s (%s) - %s (%s)",
                tradeId, localPlayer.getName(), manager.getServerId(), remoteName, remoteServer));
    }

    public String getTradeId() {
        return tradeId;
    }

    public String getRemoteServer() {
        return remoteServer;
    }

    @Override
    public void update() {
        super.update();
        checkAndBroadcastItems();
    }

    public synchronized void checkAndBroadcastItems() {
        if (endedSession || finalized.get()) return;
        List<ItemStack> current = player1.getTradeGui().getItems(true);
        if (!itemsEqual(current, lastSentItems)) {
            lastSentItems.clear();
            for (ItemStack it : current) {
                lastSentItems.add(it != null ? it.clone() : null);
            }
            CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_ITEMS);
            packet.setTradeId(tradeId);
            packet.setSenderUuid(player1.getPlayer().getUniqueId());
            packet.setTargetServer(remoteServer);
            packet.setItems(ItemSerialization.serializeList(lastSentItems));
            manager.publish(packet);
        }
    }

    private boolean itemsEqual(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            ItemStack itA = a.get(i);
            ItemStack itB = b.get(i);
            if (!Objects.equals(itA, itB)) return false;
        }
        return true;
    }

    public void onRemoteItemsReceived(List<ItemStack> items) {
        if (endedSession || finalized.get()) return;
        remoteTradePlayer.getRemoteTradeGui().setRemoteItems(items);
        Scheduler.get().run(player1.getPlayer(), task -> {
            super.update();
        }, () -> {});
    }

    public void onRemoteConfirm(int seconds) {
        if (endedSession || finalized.get()) return;
        remoteTradePlayer.setConfirmed(seconds);
        Scheduler.get().run(player1.getPlayer(), task -> {
            player1.getTradeGui().updateTitle();
            super.update();
        }, () -> {});
    }

    public void onRemoteUnconfirm() {
        if (endedSession || finalized.get()) return;
        remoteTradePlayer.setConfirmed(null);
        Scheduler.get().run(player1.getPlayer(), task -> {
            player1.getTradeGui().updateTitle();
            super.update();
        }, () -> {});
    }

    public void broadcastConfirm(int seconds) {
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_CONFIRM);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setTargetServer(remoteServer);
        packet.setConfirmSeconds(seconds);
        manager.publish(packet);
    }

    public void broadcastUnconfirm() {
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_UNCONFIRM);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setTargetServer(remoteServer);
        manager.publish(packet);
    }

    @Override
    public void abort() {
        abort(false);
    }

    @Override
    public void abort(boolean force) {
        if (endedSession) return;
        endedSession = true;

        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_ABORT);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setTargetServer(remoteServer);
        packet.setReason("aborted");
        manager.publish(packet);

        handleAbortCleanup();
    }

    public void onRemoteAbortReceived() {
        if (endedSession) return;
        endedSession = true;
        handleAbortCleanup();
    }

    private void handleAbortCleanup() {
        Player player = player1.getPlayer();
        end().thenRun(() -> {
            // Return any items currently in local TradeGui
            player1.getTradeGui().getItems(false).forEach(itemStack -> {
                if (itemStack == null) return;
                addOrDrop(player.getInventory(), List.of(itemStack), player.getLocation());
            });
            // Return any items currently in escrow
            synchronized (escrowItems) {
                if (!escrowItems.isEmpty()) {
                    addOrDrop(player.getInventory(), List.copyOf(escrowItems), player.getLocation());
                    escrowItems.clear();
                }
            }

            HistoryUtils.writeToHistory(String.format("Cross-Server Aborted [%s]: %s - %s",
                    tradeId, player.getName(), remoteTradePlayer.getRemoteName()));
            MESSAGEUTILS.sendLang(player, "trade.aborted", Map.of("%player%", remoteTradePlayer.getRemoteName()));
            SoundUtils.playSound(player, "aborted");

            Trades.removeTrade(this);
            manager.removeSession(tradeId);
        });
    }

    @Override
    public CompletableFuture<Void> end() {
        endedSession = true;
        CompletableFuture<Void> task = new CompletableFuture<>();
        Player player = player1.getPlayer();
        execute(player, () -> {
            player.closeInventory();
            player.updateInventory();
            task.complete(null);
        });
        return task;
    }

    @Override
    public void complete() {
        if (endedSession || !localPrepared.compareAndSet(false, true)) return;

        Player player = player1.getPlayer();
        // Take local player's offered items into escrow before closing window
        synchronized (escrowItems) {
            escrowItems.clear();
            for (ItemStack it : player1.getTradeGui().getItems(false)) {
                if (it != null && !it.getType().isAir()) {
                    escrowItems.add(it.clone());
                }
            }
        }
        endedSession = true;

        end().thenRun(() -> {
            // Publish prepare packet to partner server
            CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.COMMIT_PREPARE);
            packet.setTradeId(tradeId);
            packet.setSenderUuid(player.getUniqueId());
            packet.setTargetServer(remoteServer);
            manager.publish(packet);

            checkAndFinalizeCommit();
        });
    }

    public void onRemoteCommitPrepare() {
        remotePrepared.set(true);
        checkAndFinalizeCommit();
    }

    private synchronized void checkAndFinalizeCommit() {
        if (!localPrepared.get() || !remotePrepared.get()) return;
        if (!finalized.compareAndSet(false, true)) return;
        endedSession = true;

        Player player = player1.getPlayer();
        // Give remote items to local player
        List<ItemStack> partnerItems = remoteTradePlayer.getRemoteTradeGui().getItems(false);
        addOrDrop(player.getInventory(), partnerItems, player.getLocation());

        // Escrow items are successfully consumed/traded
        escrowItems.clear();

        // Notify partner
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.COMMIT_FINALIZE);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player.getUniqueId());
        packet.setTargetServer(remoteServer);
        manager.publish(packet);

        HistoryUtils.writeToHistory(String.format("Cross-Server Completed [%s]: %s gave %d items, received %d items from %s",
                tradeId, player.getName(), lastSentItems.size(), partnerItems.size(), remoteTradePlayer.getRemoteName()));

        MESSAGEUTILS.sendLang(player, "trade.completed", Map.of("%player%", remoteTradePlayer.getRemoteName()));
        SoundUtils.playSound(player, "completed");

        Trades.removeTrade(this);
        manager.removeSession(tradeId);
    }
}
