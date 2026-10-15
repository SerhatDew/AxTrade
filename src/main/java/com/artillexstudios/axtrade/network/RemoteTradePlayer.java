package com.artillexstudios.axtrade.network;

import com.artillexstudios.axtrade.trade.Trade;
import com.artillexstudios.axtrade.trade.TradePlayer;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

import java.lang.reflect.Proxy;
import java.util.UUID;

public class RemoteTradePlayer extends TradePlayer {
    private final UUID uuid;
    private final String name;
    private final RemoteTradeGui remoteGui;

    public RemoteTradePlayer(Trade trade, UUID uuid, String name) {
        super(trade, createProxy(uuid, name));
        this.uuid = uuid;
        this.name = name;
        this.remoteGui = new RemoteTradeGui(trade, this);
        this.tradeGui = this.remoteGui;
    }

    private static Player createProxy(UUID uuid, String name) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getPlayer".equals(m)) return proxy;
                    if ("getUniqueId".equals(m)) return uuid;
                    if ("getName".equals(m)) return name;
                    if ("isOnline".equals(m)) return true;
                    if ("isValid".equals(m)) return true;
                    if ("canSee".equals(m)) return true;
                    if ("hasPermission".equals(m)) return true;
                    if ("getWorld".equals(m)) return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                    if ("getLocation".equals(m)) {
                        var worlds = Bukkit.getWorlds();
                        return worlds.isEmpty() ? null : new org.bukkit.Location(worlds.get(0), 0, 0, 0);
                    }
                    if ("equals".equals(m) && args != null && args.length == 1) {
                        if (args[0] instanceof Player p) {
                            return uuid.equals(p.getUniqueId());
                        }
                        return false;
                    }
                    if ("hashCode".equals(m)) return uuid.hashCode();
                    if ("toString".equals(m)) return "RemotePlayer{" + name + ", " + uuid + "}";
                    Class<?> ret = method.getReturnType();
                    if (ret.isAssignableFrom(Player.class)) return proxy;
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    if (ret == double.class) return 0.0;
                    if (ret == float.class) return 0.0f;
                    return null;
                }
        );
    }

    @Override
    public void setOtherPlayer(TradePlayer otherPlayer) {
        this.otherPlayer = otherPlayer;
        this.tradeGui = this.remoteGui;
    }
    @Override
    public int getEmptySlots() {
        return 36;
    }


    public RemoteTradeGui getRemoteTradeGui() {
        return remoteGui;
    }

    public UUID getRemoteUuid() {
        return uuid;
    }

    public String getRemoteName() {
        return name;
    }
}
