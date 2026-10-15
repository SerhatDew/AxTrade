package com.artillexstudios.axtrade.network;

import com.artillexstudios.axtrade.trade.Trade;
import com.artillexstudios.axtrade.trade.TradeGui;
import com.artillexstudios.axtrade.trade.TradePlayer;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RemoteTradeGui extends TradeGui {
    private final List<ItemStack> items = Collections.synchronizedList(new ArrayList<>());

    public RemoteTradeGui(Trade trade, TradePlayer player) {
        super(trade, player, true);
    }

    public void setRemoteItems(List<ItemStack> newItems) {
        synchronized (items) {
            items.clear();
            if (newItems != null) {
                items.addAll(newItems);
            }
        }
    }

    @Override
    public List<ItemStack> getItems(boolean includeAir) {
        synchronized (items) {
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack item : items) {
                if (!includeAir && (item == null || item.getType().isAir())) continue;
                result.add(item);
            }
            return result;
        }
    }

    @Override
    public void update() {
    }

    @Override
    public void updateTitle() {
    }
}
