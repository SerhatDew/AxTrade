package com.artillexstudios.axtrade.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;
import java.util.UUID;

public class CrossServerPacket {
    private static final Gson GSON = new GsonBuilder().create();

    public enum Type {
        REQUEST,
        ACCEPT,
        DENY,
        TRADE_ITEMS,
        TRADE_CURRENCY,
        TRADE_CONFIRM,
        TRADE_UNCONFIRM,
        TRADE_ABORT,
        COMMIT_PREPARE,
        COMMIT_FINALIZE
    }

    private Type type;
    private String tradeId;
    private UUID senderUuid;
    private String senderName;
    private String senderServer;
    private UUID receiverUuid;
    private String receiverName;
    private String targetServer;
    private String receiverServer;
    private List<String> items;
    private String currencyName;
    private double currencyAmount;
    private int confirmSeconds;
    private String reason;
    private String offerDigest;
    private long timestamp;

    public CrossServerPacket() {
    }

    public CrossServerPacket(Type type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public boolean isWellFormed() {
        if (type == null || senderUuid == null || isBlank(senderServer) || isBlank(targetServer)
                || timestamp <= 0) {
            return false;
        }
        return switch (type) {
            case REQUEST -> receiverUuid != null && !isBlank(senderName) && !isBlank(receiverName);
            case ACCEPT -> !isBlank(tradeId) && receiverUuid != null && !isBlank(receiverName)
                    && !isBlank(receiverServer);
            case DENY -> receiverUuid != null && !isBlank(receiverName);
            case TRADE_ITEMS -> !isBlank(tradeId) && items != null && items.size() <= 128
                    && items.stream().allMatch(item -> item == null || item.length() <= 1_000_000);
            case TRADE_CURRENCY -> !isBlank(tradeId) && !isBlank(currencyName)
                    && Double.isFinite(currencyAmount) && currencyAmount >= 0;
            case TRADE_CONFIRM -> !isBlank(tradeId) && confirmSeconds > 0;
            case TRADE_UNCONFIRM, TRADE_ABORT -> !isBlank(tradeId);
            case COMMIT_PREPARE, COMMIT_FINALIZE -> !isBlank(tradeId) && !isBlank(offerDigest);
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    public static CrossServerPacket fromJson(String json) {
        return GSON.fromJson(json, CrossServerPacket.class);
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getTradeId() {
        return tradeId;
    }

    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }

    public UUID getSenderUuid() {
        return senderUuid;
    }

    public void setSenderUuid(UUID senderUuid) {
        this.senderUuid = senderUuid;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getSenderServer() {
        return senderServer;
    }

    public void setSenderServer(String senderServer) {
        this.senderServer = senderServer;
    }

    public UUID getReceiverUuid() {
        return receiverUuid;
    }

    public void setReceiverUuid(UUID receiverUuid) {
        this.receiverUuid = receiverUuid;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getTargetServer() {
        return targetServer;
    }

    public void setTargetServer(String targetServer) {
        this.targetServer = targetServer;
    }

    public String getReceiverServer() {
        return receiverServer;
    }

    public void setReceiverServer(String receiverServer) {
        this.receiverServer = receiverServer;
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items;
    }

    public String getCurrencyName() {
        return currencyName;
    }

    public void setCurrencyName(String currencyName) {
        this.currencyName = currencyName;
    }

    public double getCurrencyAmount() {
        return currencyAmount;
    }

    public void setCurrencyAmount(double currencyAmount) {
        this.currencyAmount = currencyAmount;
    }

    public int getConfirmSeconds() {
        return confirmSeconds;
    }

    public void setConfirmSeconds(int confirmSeconds) {
        this.confirmSeconds = confirmSeconds;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOfferDigest() {
        return offerDigest;
    }

    public void setOfferDigest(String offerDigest) {
        this.offerDigest = offerDigest;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}

