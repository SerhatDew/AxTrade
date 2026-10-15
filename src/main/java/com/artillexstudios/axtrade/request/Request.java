package com.artillexstudios.axtrade.request;

import org.bukkit.entity.Player;

public final class Request {
    private final long time;
    private final Player sender;
    private final Player receiver;
    private volatile boolean active = true;

    public Request(Player sender, Player receiver) {
        this(sender, receiver, System.currentTimeMillis());
    }

    public Request(Player sender, Player receiver, long time) {
        this.time = time;
        this.sender = sender;
        this.receiver = receiver;
    }

    public long getTime() {
        return time;
    }

    public Player getReceiver() {
        return receiver;
    }

    public Player getSender() {
        return sender;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public boolean isExpired(long now, long expiryMillis) {
        return expiryMillis >= 0 && now >= time && now - time >= expiryMillis;
    }
}
