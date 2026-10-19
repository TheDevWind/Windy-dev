package fr.windydev.clans.dungeon;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Boutique d'Éclats (v1.2) : les Éclats de donjon servent de monnaie. */
public class EclatShop {
   private static final int[] GRID = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
   private final WindyClans plugin;
   private final Map<String, Offer> offers = new LinkedHashMap<>();
   private final Map<String, Integer> resell = new LinkedHashMap<>();

   record Offer(String id, String item, int amount, Map<String, Integer> price) {
   }

   public EclatShop(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "boutique.yml");
      if (!f.exists()) {
         this.plugin.saveResource("boutique.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

      try (InputStream in = this.plugin.getResource("boutique.yml")) {
         if (in != null) {
            y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
         }
      } catch (Exception ignored) {
      }

      this.offers.clear();
      ConfigurationSection os = y.getConfigurationSection("offers");
      if (os != null) {
         for (String k : os.getKeys(false)) {
            ConfigurationSection s = os.getConfigurationSection(k);
            if (s != null) {
               Map<String, Integer> price = new LinkedHashMap<>();
               ConfigurationSection ps = s.getConfigurationSection("price");
               if (ps != null) {
                  for (String pk : ps.getKeys(false)) {
                     price.put(pk, Math.max(1, ps.getInt(pk)));
                  }
               }

               this.offers.put(k, new Offer(k, s.getString("item", "windy:eclat_1"), Math.max(1, s.getInt("amount", 1)), price));
            }
         }
      }

      this.resell.clear();
      ConfigurationSection rs = y.getConfigurationSection("resell-grimoire");
      if (rs != null) {
         for (String k : rs.getKeys(false)) {
            this.resell.put(k, rs.getInt(k));
         }
      }
   }

   private ItemStack create(String spec, int amount) {
      ItemStack it;
      if (spec.startsWith("windy:")) {
         it = this.plugin.items().create(spec.substring(6));
      } else {
         Material m = Material.matchMaterial(spec);
         it = m == null ? null : new ItemStack(m);
      }

      if (it != null) {
         it.setAmount(Math.min(it.getMaxStackSize(), amount));
      }

      return it;
   }

   public int count(Player p, String itemId) {
      int n = 0;
      for (ItemStack it : p.getInventory().getStorageContents()) {
         if (it != null && itemId.equals(this.plugin.items().idOf(it))) {
            n += it.getAmount();
         }
      }

      return n;
   }

   private void take(Player p, String itemId, int n) {
      ItemStack[] c = p.getInventory().getStorageContents();
      for (int i = 0; i < c.length && n > 0; i++) {
         ItemStack it = c[i];
         if (it != null && itemId.equals(this.plugin.items().idOf(it))) {
            int t = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - t);
            n -= t;
         }
      }

      p.getInventory().setStorageContents(c);
   }

   private String priceText(Map<String, Integer> price) {
      List<String> l = new ArrayList<>();
      for (Map.Entry<String, Integer> e : price.entrySet()) {
         l.add("&f" + e.getValue() + "x " + this.plugin.items().displayName("windy:" + e.getKey()));
      }

      return String.join("&7, ", l);
   }

   public void menu(Player p, int page) {
      List<Offer> list = new ArrayList<>(this.offers.values());
      int pages = Math.max(1, (list.size() + GRID.length - 1) / GRID.length);
      int pg = Math.max(0, Math.min(pages - 1, page));
      Menu m = new Menu(p, "list", 6, "&5Boutique d'Éclats" + (pages > 1 ? " &8(" + (pg + 1) + "/" + pages + ")" : ""));
      m.set(4, ItemBuilder.of(Material.ECHO_SHARD)
         .name("&dTes Éclats")
         .lore("&aÉclat I &7: &f" + this.count(p, "eclat_1"), "&bÉclat II &7: &f" + this.count(p, "eclat_2"), "&dÉclat III &7: &f" + this.count(p, "eclat_3"), "",
            "&8Les Éclats se gagnent dans les donjons.")
         .glow(true)
         .build());
      int i = 0;
      for (Offer o : list.subList(pg * GRID.length, Math.min(list.size(), (pg + 1) * GRID.length))) {
         ItemStack icon = this.create(o.item(), o.amount());
         if (icon == null) {
            continue;
         }

         boolean afford = true;
         for (Map.Entry<String, Integer> e : o.price().entrySet()) {
            if (this.count(p, e.getKey()) < e.getValue()) {
               afford = false;
            }
         }

         List<net.kyori.adventure.text.Component> lore = new ArrayList<>(icon.getItemMeta().hasLore() ? icon.getItemMeta().lore() : List.of());
         lore.add(Text.item(""));
         lore.add(Text.item("&6Prix : " + this.priceText(o.price())));
         lore.add(Text.item(afford ? "&eClic : acheter" : "&cPas assez d'Éclats"));
         var meta = icon.getItemMeta();
         meta.lore(lore);
         icon.setItemMeta(meta);
         final boolean ok = afford;
         m.set(GRID[i++], icon, e -> {
            if (ok) {
               this.buy(p, o);
            } else {
               p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6F, 0.7F);
            }

            this.menu(p, pg);
         });
      }

      if (!this.resell.isEmpty()) {
         m.set(46, ItemBuilder.of(Material.ENCHANTED_BOOK)
            .name("&dRevendre un grimoire en double")
            .lore("&7Reprend un grimoire dont tu connais", "&7déjà le sort, contre " + this.priceText(this.resell) + "&7.", "", "&eClic : revendre")
            .build(), e -> {
               this.resell(p);
               this.menu(p, pg);
            });
      }

      if (pg > 0) {
         m.set(48, ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7← Page précédente").build(), e -> this.menu(p, pg - 1));
      }

      if (pg < pages - 1) {
         m.set(50, ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7Page suivante →").build(), e -> this.menu(p, pg + 1));
      }

      m.set(49, ItemBuilder.of(Material.ARROW).name("&7Retour").build(), e -> this.plugin.dungeons().menu(p));
      m.fillBorder();
      m.open(p);
   }

   private void buy(Player p, Offer o) {
      for (Map.Entry<String, Integer> e : o.price().entrySet()) {
         if (this.count(p, e.getKey()) < e.getValue()) {
            return;
         }
      }

      ItemStack it = this.create(o.item(), o.amount());
      if (it == null) {
         return;
      }

      o.price().forEach((k, v) -> this.take(p, k, v));
      p.getInventory().addItem(it).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
      p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.8F, 1.1F);
      p.sendMessage(Text.c("&5✦ &7Acheté : &f" + it.getAmount() + "x ").append(it.getItemMeta().displayName()));
   }

   private void resell(Player p) {
      ItemStack[] c = p.getInventory().getStorageContents();
      for (int i = 0; i < c.length; i++) {
         ItemStack it = c[i];
         String spell = this.plugin.magic().spellOfGrimoire(this.plugin.items().idOf(it));
         if (spell != null && this.plugin.magic().knows(p.getUniqueId(), spell)) {
            it.setAmount(it.getAmount() - 1);
            p.getInventory().setStorageContents(c);
            for (Map.Entry<String, Integer> e : this.resell.entrySet()) {
               ItemStack r = this.create("windy:" + e.getKey(), e.getValue());
               if (r != null) {
                  p.getInventory().addItem(r).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
               }
            }

            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.8F, 1.3F);
            this.plugin.msg(p, "&dGrimoire revendu contre " + this.priceText(this.resell) + "&d.");
            return;
         }
      }

      this.plugin.msg(p, "&7Aucun grimoire en double dans ton inventaire (il faut déjà connaître le sort).");
   }
}
