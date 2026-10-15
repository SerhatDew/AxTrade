package com.artillexstudios.axtrade.network;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class ItemSerialization {

    private ItemSerialization() {
    }

    public static String serialize(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
                dataOutput.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception ex) {
            return null;
        }
    }

    public static ItemStack deserialize(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                return (ItemStack) dataInput.readObject();
            }
        } catch (Exception ex) {
            return null;
        }
    }

    public static List<String> serializeList(List<ItemStack> items) {
        if (items == null) return List.of();
        List<String> result = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            result.add(serialize(item));
        }
        return result;
    }

    public static List<ItemStack> deserializeList(List<String> encoded) {
        if (encoded == null) return List.of();
        List<ItemStack> result = new ArrayList<>(encoded.size());
        for (String s : encoded) {
            result.add(deserialize(s));
        }
        return result;
    }
}
