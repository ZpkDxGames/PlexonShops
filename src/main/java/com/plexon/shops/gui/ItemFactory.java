package com.plexon.shops.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Adventure-component item builder shared by every GUI. */
public final class ItemFactory {
    public ItemStack create(Material material, Component name, List<Component> lore, boolean glow) {
        return decorate(new ItemStack(material), name, lore, glow);
    }

    public ItemStack decorate(ItemStack base, Component name, List<Component> lore, boolean glow) {
        ItemStack item = base.clone();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.setEnchantmentGlintOverride(glow ? Boolean.TRUE : null);
        item.setItemMeta(meta);
        return item;
    }
}
