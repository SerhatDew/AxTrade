package com.artillexstudios.axtrade.network;

import java.util.UUID;

public record NetworkPlayer(UUID uuid, String name, String serverId, long lastSeen) {
}
