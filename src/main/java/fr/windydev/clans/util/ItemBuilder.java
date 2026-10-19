package fr.windydev.clans.util;

import com.google.common.collect.ImmutableMultimap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public class ItemBuilder {
   private final ItemStack item;
   private String name;
   private final List<String> lore = new ArrayList<>();
   private boolean glow;
   private OfflinePlayer skullOwner;

   public ItemBuilder(Material m) {
      this.item = new ItemStack(m);
   }

   public static ItemBuilder of(Material m) {
      return new ItemBuilder(m);
   }

   public ItemBuilder name(String n) {
      this.name = n;
      return this;
   }

   public ItemBuilder lore(String... l) {
      this.lore.addAll(Arrays.asList(l));
      return this;
   }

   public ItemBuilder lore(List<String> l) {
      this.lore.addAll(l);
      return this;
   }

   public ItemBuilder amount(int a) {
      this.item.setAmount(Math.max(1, Math.min(64, a)));
      return this;
   }

   public ItemBuilder glow(boolean g) {
      this.glow = g;
      return this;
   }

   public ItemBuilder model(int cmd) {
      if (cmd > 0) {
         ItemMeta m = this.item.getItemMeta();
         m.setCustomModelData(cmd);
         this.item.setItemMeta(m);
      }

      return this;
   }

   public ItemBuilder skull(OfflinePlayer p) {
      this.skullOwner = p;
      return this;
   }

   public ItemStack build() {
      ItemMeta meta = this.item.getItemMeta();
      if (this.name != null) {
         meta.displayName(Text.item(this.name));
      }

      if (!this.lore.isEmpty()) {
         meta.lore(Text.lore(this.lore));
      }

      meta.addItemFlags(ItemFlag.values());
      if (this.item.getType().isItem() && meta.getAttributeModifiers() == null) {
         meta.setAttributeModifiers(ImmutableMultimap.of());
      }

      if (this.glow) {
         meta.setEnchantmentGlintOverride(true);
      }

      if (this.skullOwner != null && meta instanceof SkullMeta sm) {
         sm.setOwningPlayer(this.skullOwner);
      }

      this.item.setItemMeta(meta);
      return this.item;
   }
}
