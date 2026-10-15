package com.artillexstudios.axtrade.network;

import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axtrade.currency.CurrencyProcessor;
import com.artillexstudios.axtrade.utils.TaxUtils;
import com.artillexstudios.axtrade.hooks.HookManager;
import com.artillexstudios.axtrade.hooks.currency.CurrencyHook;
import com.artillexstudios.axtrade.trade.Trade;
import com.artillexstudios.axtrade.trade.TradePlayer;
import com.artillexstudios.axtrade.trade.Trades;
import com.artillexstudios.axtrade.utils.HistoryUtils;
import com.artillexstudios.axtrade.utils.SoundUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public class CrossServerTradeSession extends Trade {
    private final String tradeId;
    private final String remoteServer;
    private final CrossServerManager manager;
    private final RemoteTradePlayer remoteTradePlayer;
    private final List<ItemStack> escrowItems = Collections.synchronizedList(new ArrayList<>());
    private final List<ItemStack> lastSentItems = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Double> lastSentCurrencies = new ConcurrentHashMap<>();
    private final Map<String, Double> localPreparedCurrencies = new ConcurrentHashMap<>();
    private final Map<CurrencyHook, Double> localPreparedCurrencyHooks = new ConcurrentHashMap<>();
    private final AtomicBoolean localPrepared = new AtomicBoolean(false);
    private final AtomicBoolean remotePrepared = new AtomicBoolean(false);
    private final AtomicBoolean settlementStarted = new AtomicBoolean(false);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private final AtomicBoolean abortCleanupStarted = new AtomicBoolean(false);
    private volatile String localPreparedDigest;
    private volatile String remotePreparedDigest;
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
    public boolean isEnded() {
        return endedSession || super.isEnded();
    }

    public Player getLocalPlayer() {
        return player1.getPlayer();
    }

    public boolean acceptsPeer(UUID senderUuid, String senderServer) {
        return remoteTradePlayer.getRemoteUuid().equals(senderUuid)
                && remoteServer.equalsIgnoreCase(senderServer);
    }

    @Override
    public void update() {
        if (isTerminal()) return;
        super.update();
        checkAndBroadcastItems();
        checkAndBroadcastCurrencies();
    }

    public synchronized void checkAndBroadcastItems() {
        if (isTerminal() || localPrepared.get()) return;
        List<ItemStack> current = player1.getTradeGui().getItems(true);
        if (itemsEqual(current, lastSentItems)) return;

        List<ItemStack> snapshot = cloneItems(current);
        List<String> encoded;
        try {
            encoded = ItemSerialization.serializeList(snapshot);
        } catch (RuntimeException exception) {
            manager.logSettlementFailure(tradeId, exception);
            return;
        }
        synchronized (lastSentItems) {
            lastSentItems.clear();
            lastSentItems.addAll(snapshot);
        }
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_ITEMS);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setSenderServer(manager.getServerId());
        packet.setTargetServer(remoteServer);
        packet.setItems(encoded);
        manager.publish(packet);
    }

    private synchronized void checkAndBroadcastCurrencies() {
        if (isTerminal() || localPrepared.get()) return;
        Map<String, Double> current = currencySnapshot(player1);
        if (current.equals(lastSentCurrencies)) return;

        for (Map.Entry<String, Double> entry : current.entrySet()) {
            publishCurrency(entry.getKey(), entry.getValue());
        }
        for (String removed : lastSentCurrencies.keySet()) {
            if (!current.containsKey(removed)) publishCurrency(removed, 0);
        }
        lastSentCurrencies.clear();
        lastSentCurrencies.putAll(current);
    }

    private void publishCurrency(String name, double amount) {
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_CURRENCY);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setSenderServer(manager.getServerId());
        packet.setTargetServer(remoteServer);
        packet.setCurrencyName(name);
        packet.setCurrencyAmount(amount);
        manager.publish(packet);
    }

    private boolean itemsEqual(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!Objects.equals(first.get(i), second.get(i))) return false;
        }
        return true;
    }

    public void onRemoteItemsReceived(List<ItemStack> items) {
        if (isTerminal()) return;
        List<ItemStack> snapshot = cloneItems(items == null ? List.of() : items);
        boolean changed = !itemsEqual(remoteTradePlayer.getRemoteTradeGui().getItems(true), snapshot);
        remoteTradePlayer.getRemoteTradeGui().setRemoteItems(snapshot);
        if (changed) {
            remotePrepared.set(false);
            remotePreparedDigest = null;
            resetConfirmations(true);
        }
        refreshLocalGui();
    }

    public void onRemoteCurrency(String currencyName, double amount) {
        if (isTerminal() || currencyName == null || currencyName.isBlank()
                || !Double.isFinite(amount) || amount < 0) return;
        CurrencyHook hook = HookManager.getCurrencyHook(currencyName);
        if (hook == null) return;

        Double previous = remoteTradePlayer.getCurrencies().get(hook);
        if (amount == 0) remoteTradePlayer.getCurrencies().remove(hook);
        else remoteTradePlayer.getCurrencies().put(hook, amount);
        if (!Objects.equals(previous, amount == 0 ? null : amount)) {
            remotePrepared.set(false);
            remotePreparedDigest = null;
            resetConfirmations(true);
            refreshLocalGui();
        }
    }

    public void onRemoteConfirm(int seconds) {
        if (isTerminal() || seconds <= 0) return;
        remoteTradePlayer.setConfirmed(seconds);
        refreshLocalGui();
    }

    public void onRemoteUnconfirm() {
        if (isTerminal()) return;
        player1.setConfirmed(null);
        remoteTradePlayer.setConfirmed(null);
        refreshLocalGui();
    }
    private void resetConfirmations(boolean notifyPeer) {
        boolean changed = player1.hasConfirmed() || remoteTradePlayer.hasConfirmed();
        player1.setConfirmed(null);
        remoteTradePlayer.setConfirmed(null);
        if (notifyPeer && changed) broadcastUnconfirm();
    }

    private void refreshLocalGui() {
        Scheduler.get().run(player1.getPlayer(), task -> {
            if (!isTerminal()) super.update();
        }, () -> {});
    }

    public void broadcastConfirm(int seconds) {
        if (isTerminal() || localPrepared.get() || seconds <= 0) return;
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_CONFIRM);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setSenderServer(manager.getServerId());
        packet.setTargetServer(remoteServer);
        packet.setConfirmSeconds(seconds);
        manager.publish(packet);
    }

    public void broadcastUnconfirm() {
        if (isTerminal()) return;
        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_UNCONFIRM);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setSenderServer(manager.getServerId());
        packet.setTargetServer(remoteServer);
        manager.publish(packet);
    }

    @Override
    public void abort() {
        abort(false);
    }

    @Override
    public void abort(boolean force) {
        if (isTerminal() || localPrepared.get()) return;
        if (!abortCleanupStarted.compareAndSet(false, true)) return;
        endedSession = true;

        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.TRADE_ABORT);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setSenderServer(manager.getServerId());
        packet.setTargetServer(remoteServer);
        packet.setReason(force ? "server-shutdown" : "aborted");
        manager.publish(packet);
        handleAbortCleanup();
    }

    public void onRemoteAbortReceived() {
        if (isTerminal() || localPrepared.get()) return;
        if (!abortCleanupStarted.compareAndSet(false, true)) return;
        endedSession = true;
        handleAbortCleanup();
    }

    private void handleAbortCleanup() {
        Player player = player1.getPlayer();
        end().thenRun(() -> {
            player1.getTradeGui().getItems(false).forEach(itemStack -> {
                if (itemStack != null) addOrDrop(player.getInventory(), List.of(itemStack), player.getLocation());
            });
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
        execute(player1.getPlayer(), () -> {
            player1.getPlayer().closeInventory();
            player1.getPlayer().updateInventory();
            task.complete(null);
        });
        return task;
    }

    @Override
    public void complete() {
        if (isTerminal() || localPrepared.get()) return;
        List<ItemStack> offeredItems = cloneItems(player1.getTradeGui().getItems(false));
        Map<String, Double> offeredCurrencies = currencySnapshot(player1);
        String preparedDigest;
        try {
            preparedDigest = offerDigest(offeredItems, offeredCurrencies);
        } catch (RuntimeException exception) {
            manager.logSettlementFailure(tradeId, exception);
            return;
        }
        if (!localPrepared.compareAndSet(false, true)) return;

        synchronized (escrowItems) {
            escrowItems.clear();
            escrowItems.addAll(offeredItems);
        }
        localPreparedCurrencies.clear();
        localPreparedCurrencies.putAll(offeredCurrencies);
        localPreparedCurrencyHooks.clear();
        for (Map.Entry<CurrencyHook, Double> entry : player1.getCurrencies().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null
                    && Double.isFinite(entry.getValue()) && entry.getValue() > 0) {
                localPreparedCurrencyHooks.put(entry.getKey(), entry.getValue());
            }
        }
        localPreparedDigest = preparedDigest;
        endedSession = true;

        Player player = player1.getPlayer();
        end().thenRun(() -> {
            CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.COMMIT_PREPARE);
            packet.setTradeId(tradeId);
            packet.setSenderUuid(player.getUniqueId());
            packet.setSenderServer(manager.getServerId());
            packet.setTargetServer(remoteServer);
            packet.setOfferDigest(localPreparedDigest);
            manager.publish(packet);
            checkAndFinalizeCommit();
        });
    }

    public void onRemoteCommitPrepare(String offerDigest) {
        if (isTerminal() || offerDigest == null) return;
        if (!offerDigest.equals(remoteOfferDigest())) return;
        remotePreparedDigest = offerDigest;
        remotePrepared.set(true);
        checkAndFinalizeCommit();
    }

    public void onRemoteCommitFinalize(String offerDigest) {
        if (isTerminal() || offerDigest == null || !offerDigest.equals(remoteOfferDigest())) return;
        remotePreparedDigest = offerDigest;
        remotePrepared.set(true);
        checkAndFinalizeCommit();
    }

    private synchronized void checkAndFinalizeCommit() {
        if (!localPrepared.get() || !remotePrepared.get() || settlementStarted.get()) return;
        if (!Objects.equals(localPreparedDigest, currentLocalOfferDigest())) return;
        if (!Objects.equals(remotePreparedDigest, remoteOfferDigest())) return;
        if (!settlementStarted.compareAndSet(false, true)) return;

        Map<String, Double> remoteCurrencies = currencySnapshot(remoteTradePlayer);
        List<ItemStack> partnerItems = cloneItems(remoteTradePlayer.getRemoteTradeGui().getItems(false));
        CurrencyProcessor processor = new CurrencyProcessor(player1.getPlayer(), localPreparedCurrencyHooks.entrySet());
        processor.run().thenCompose(debited -> {
            if (!debited) return CompletableFuture.completedFuture(false);
            return giveCurrencies(remoteCurrencies);
        }).whenComplete((success, error) -> {
            if (!Boolean.TRUE.equals(success) || error != null) {
                // CurrencyProcessor reverses failed debits. A prepared trade is intentionally
                // left unresolved here: refunding after a peer commit could duplicate items.
                manager.logSettlementFailure(tradeId, error);
                return;
            }
            execute(player1.getPlayer(), () -> finishCommit(partnerItems));
        });
    }

    private CompletableFuture<Boolean> giveCurrencies(Map<String, Double> currencies) {
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();
        for (Map.Entry<String, Double> entry : currencies.entrySet()) {
            CurrencyHook hook = HookManager.getCurrencyHook(entry.getKey());
            if (hook == null || !Double.isFinite(entry.getValue()) || entry.getValue() <= 0) {
                if (entry.getValue() > 0) return CompletableFuture.completedFuture(false);
                continue;
            }
            double amount = TaxUtils.getTotalAfterTax(entry.getValue(), hook);
            if (!Double.isFinite(amount) || amount <= 0) continue;
            try {
                CompletableFuture<Boolean> transfer = hook.giveBalance(player1.getPlayer().getUniqueId(), amount);
                transfers.add(transfer == null ? CompletableFuture.completedFuture(false) : transfer);
            } catch (Exception exception) {
                manager.logSettlementFailure(tradeId, exception);
                return CompletableFuture.completedFuture(false);
            }
        }
        if (transfers.isEmpty()) return CompletableFuture.completedFuture(true);
        return CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .thenApply(unused -> transfers.stream().allMatch(future -> Boolean.TRUE.equals(future.getNow(false))));
    }

    private void finishCommit(List<ItemStack> partnerItems) {
        if (finalized.get() || !player1.getPlayer().isOnline()) return;
        if (!finalized.compareAndSet(false, true)) return;

        int offeredItemCount;
        synchronized (escrowItems) {
            offeredItemCount = escrowItems.size();
        }
        // The local offer is now consumed. It must not remain in the closed GUI while the
        // partner offer is delivered, otherwise a GUI implementation could duplicate it.
        for (ItemStack item : player1.getTradeGui().getItems(false)) {
            if (item != null) item.setAmount(0);
        }
        addOrDrop(player1.getPlayer().getInventory(), partnerItems, player1.getPlayer().getLocation());
        synchronized (escrowItems) {
            escrowItems.clear();
        }

        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.COMMIT_FINALIZE);
        packet.setTradeId(tradeId);
        packet.setSenderUuid(player1.getPlayer().getUniqueId());
        packet.setSenderServer(manager.getServerId());
        packet.setTargetServer(remoteServer);
        packet.setOfferDigest(localPreparedDigest);
        manager.publish(packet);

        HistoryUtils.writeToHistory(String.format("Cross-Server Completed [%s]: %s gave %d items, received %d items from %s",
                tradeId, player1.getPlayer().getName(), offeredItemCount, partnerItems.size(), remoteTradePlayer.getRemoteName()));
        MESSAGEUTILS.sendLang(player1.getPlayer(), "trade.completed", Map.of("%player%", remoteTradePlayer.getRemoteName()));
        SoundUtils.playSound(player1.getPlayer(), "completed");
        Trades.removeTrade(this);
        manager.removeSession(tradeId);
    }

    private boolean isTerminal() {
        return finalized.get() || abortCleanupStarted.get();
    }

    private String currentLocalOfferDigest() {
        if (localPrepared.get()) return localPreparedDigest;
        return offerDigest(player1.getTradeGui().getItems(false), currencySnapshot(player1));
    }

    private String remoteOfferDigest() {
        return offerDigest(remoteTradePlayer.getRemoteTradeGui().getItems(false), currencySnapshot(remoteTradePlayer));
    }

    private static Map<String, Double> currencySnapshot(TradePlayer player) {
        Map<String, Double> result = new TreeMap<>();
        for (Map.Entry<CurrencyHook, Double> entry : player.getCurrencies().entrySet()) {
            CurrencyHook hook = entry.getKey();
            Double amount = entry.getValue();
            if (hook == null || hook.getName() == null || amount == null || !Double.isFinite(amount) || amount <= 0) continue;
            result.put(hook.getName(), amount);
        }
        return result;
    }

    private static List<ItemStack> cloneItems(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        List<ItemStack> result = new ArrayList<>(items.size());
        for (ItemStack item : items) result.add(item == null ? null : item.clone());
        return result;
    }

    private static String offerDigest(List<ItemStack> items, Map<String, Double> currencies) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String item : ItemSerialization.serializeList(items)) {
                digest.update((item == null ? "<air>" : item).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            for (Map.Entry<String, Double> entry : new TreeMap<>(currencies).entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(Double.toString(entry.getValue()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder result = new StringBuilder(digest.getDigestLength() * 2);
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
