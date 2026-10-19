package fr.windydev.clans.forge;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.ritual.CustomItems;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Gravure runique (v1.2) : glyphes gravés à l'autel sur les objets magiques, et leurs effets.
 * Un objet porte soit des enchantements vanilla, soit des glyphes.
 */
public class GlyphManager implements Listener {
   private final WindyClans plugin;
   private final NamespacedKey key;
   private final NamespacedKey bastionKey;
   private final Map<String, Glyph> glyphs = new LinkedHashMap<>();
   private final Map<String, Integer> slotOverride = new HashMap<>();
   private final int[] fluid = new int[]{0, 500, 1200, 800};
   private final int[] duration = new int[]{0, 30, 60, 90};
   private double repairPerPoint = 2.0;

   public record Glyph(String id, String name, String color, int max, Pattern targets, String reagent, String effect) {
      public String colored() {
         return this.color + this.name;
      }
   }

   /** Une gravure ou un effacement proposé par l'autel pour l'objet posé. */
   public record Option(String id, String name, String result, List<String> offerings, int tier, int fluid, int duration) {
   }

   public GlyphManager(WindyClans plugin) {
      this.plugin = plugin;
      this.key = new NamespacedKey(plugin, "glyphs");
      this.bastionKey = new NamespacedKey(plugin, "bastion");
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "gravure.yml");
      if (!f.exists()) {
         this.plugin.saveResource("gravure.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

      try (InputStream in = this.plugin.getResource("gravure.yml")) {
         if (in != null) {
            y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
         }
      } catch (Exception ignored) {
      }

      for (int t = 1; t <= 3; t++) {
         this.fluid[t] = Math.max(0, y.getInt("settings.fluid-cost." + t, this.fluid[t]));
         this.duration[t] = Math.max(3, y.getInt("settings.duration." + t, this.duration[t]));
      }

      this.repairPerPoint = Math.max(0.0, y.getDouble("settings.repair-fluid-per-point", 2.0));
      this.slotOverride.clear();
      ConfigurationSection so = y.getConfigurationSection("settings.slots");
      if (so != null) {
         for (String k : so.getKeys(false)) {
            this.slotOverride.put(k, so.getInt(k));
         }
      }

      this.glyphs.clear();
      ConfigurationSection gs = y.getConfigurationSection("glyphs");
      if (gs != null) {
         for (String id : gs.getKeys(false)) {
            ConfigurationSection s = gs.getConfigurationSection(id);
            if (s != null) {
               try {
                  this.glyphs.put(id, new Glyph(id, s.getString("name", id), s.getString("color", "&d"), Math.max(1, Math.min(3, s.getInt("max", 3))),
                     Pattern.compile(s.getString("targets", "$^")), s.getString("reagent", "AMETHYST_SHARD:8"), s.getString("effect", "")));
               } catch (Exception ex) {
                  this.plugin.getLogger().warning("gravure.yml : glyphe " + id + " invalide : " + ex.getMessage());
               }
            }
         }
      }

      this.plugin.getLogger().info("Gravure : " + this.glyphs.size() + " glyphe(s).");
   }

   public Map<String, Glyph> all() {
      return this.glyphs;
   }

   // ------------------------------------------------------------------ données d'un objet

   public Map<String, Integer> read(ItemStack it) {
      Map<String, Integer> out = new LinkedHashMap<>();
      if (it == null || !it.hasItemMeta()) {
         return out;
      } else {
         String raw = it.getItemMeta().getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
         if (raw != null && !raw.isEmpty()) {
            for (String part : raw.split(",")) {
               String[] kv = part.split(":");
               if (kv.length == 2) {
                  try {
                     out.put(kv[0], Integer.parseInt(kv[1]));
                  } catch (NumberFormatException ignored) {
                  }
               }
            }
         }

         return out;
      }
   }

   public int level(ItemStack it, String glyph) {
      return this.read(it).getOrDefault(glyph, 0);
   }

   public boolean engraved(ItemStack it) {
      return !this.read(it).isEmpty();
   }

   public int slots(ItemStack it) {
      CustomItems.Def d = this.plugin.items().defOf(it);
      if (d == null) {
         return 0;
      } else {
         Integer o = this.slotOverride.get(d.id());
         return o != null ? o : Math.max(1, Math.min(3, d.tier()));
      }
   }

   public boolean eligible(ItemStack it) {
      String id = this.plugin.items().idOf(it);
      if (id == null) {
         return false;
      } else {
         for (Glyph g : this.glyphs.values()) {
            if (g.targets().matcher(id).matches()) {
               return true;
            }
         }

         return false;
      }
   }

   private void write(ItemStack it, Map<String, Integer> g) {
      ItemMeta meta = it.getItemMeta();
      if (g.isEmpty()) {
         meta.getPersistentDataContainer().remove(this.key);
      } else {
         StringBuilder sb = new StringBuilder();
         for (Map.Entry<String, Integer> e : g.entrySet()) {
            if (sb.length() > 0) {
               sb.append(',');
            }

            sb.append(e.getKey()).append(':').append(e.getValue());
         }

         meta.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, sb.toString());
      }

      it.setItemMeta(meta);
      this.render(it);
   }

   /** Réécrit la description et l'armure bonus (Bastion) selon les glyphes gravés. */
   public void render(ItemStack it) {
      CustomItems.Def d = this.plugin.items().defOf(it);
      if (d == null) {
         return;
      }

      Map<String, Integer> g = this.read(it);
      ItemMeta meta = it.getItemMeta();
      List<String> lore = new ArrayList<>();
      if (!g.isEmpty()) {
         lore.add("");
         lore.add("&5✦ Glyphes &8(" + g.size() + "/" + this.slots(it) + ")");
         for (Map.Entry<String, Integer> e : g.entrySet()) {
            Glyph gl = this.glyphs.get(e.getKey());
            lore.add("&8 • " + (gl == null ? "&7" + e.getKey() : gl.colored()) + " &f" + roman(e.getValue()));
         }
      }

      meta.lore(Text.lore(this.plugin.items().fullLore(d, lore)));
      if (d.isArmor()) {
         EquipmentSlotGroup group = d.material().getEquipmentSlot().getGroup();
         if (meta.hasAttributeModifiers() && meta.getAttributeModifiers(Attribute.GENERIC_ARMOR) != null) {
            for (AttributeModifier m : new ArrayList<>(meta.getAttributeModifiers(Attribute.GENERIC_ARMOR))) {
               if (m.getKey().equals(this.bastionKey)) {
                  meta.removeAttributeModifier(Attribute.GENERIC_ARMOR, m);
               }
            }
         }

         int b = g.getOrDefault("bastion", 0);
         if (b > 0) {
            meta.addAttributeModifier(Attribute.GENERIC_ARMOR, new AttributeModifier(this.bastionKey, b, Operation.ADD_NUMBER, group));
         }
      }

      it.setItemMeta(meta);
   }

   /** Garde les glyphes quand un objet évolue à l'autel (ex. Lame I -> Lame II). */
   public void carry(ItemStack from, ItemStack to) {
      if (from != null && to != null) {
         Map<String, Integer> g = this.read(from);
         if (!g.isEmpty() && this.eligible(to)) {
            this.write(to, g);
         }
      }
   }

   public static String roman(int n) {
      return switch (n) {
         case 1 -> "I";
         case 2 -> "II";
         case 3 -> "III";
         default -> String.valueOf(n);
      };
   }

   // ------------------------------------------------------------------ autel

   public List<Option> options(ItemStack focus) {
      List<Option> out = new ArrayList<>();
      String id = this.plugin.items().idOf(focus);
      if (id == null) {
         return out;
      } else {
         Map<String, Integer> cur = this.read(focus);
         int dmg = focus.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm ? dm.getDamage() : 0;
         if (dmg > 0 && this.eligible(focus)) {
            int cost = Math.max(100, (int)Math.ceil(dmg * this.repairPerPoint / 50.0) * 50);
            out.add(new Option("reparation", "&aRéparation &7(" + dmg + " points d'usure)", "@repare", List.of(), 1, cost, 20));
         }

         for (Glyph g : this.glyphs.values()) {
            if (g.targets().matcher(id).matches()) {
               int lvl = cur.getOrDefault(g.id(), 0);
               if (lvl < g.max()) {
                  int next = lvl + 1;
                  out.add(new Option("gravure:" + g.id() + ":" + next, "&5Gravure : " + g.colored() + " " + roman(next), "@gravure:" + g.id() + ":" + next,
                     List.of("windy:glyphe_" + next + ":1", g.reagent()), next, this.fluid[next], this.duration[next]));
               }
            }
         }

         for (String g : cur.keySet()) {
            Glyph gl = this.glyphs.get(g);
            out.add(new Option("efface:" + g, "&7Effacement : " + (gl == null ? g : gl.colored()), "@efface:" + g,
               List.of("windy:parchemin_effacement:1"), 1, 0, 20));
         }

         return out;
      }
   }

   public String describe(String result) {
      if (result.equals("@repare")) {
         return "remet l'objet à neuf (fluide magique I)";
      }

      String[] p = result.split(":");
      Glyph g = p.length > 1 ? this.glyphs.get(p[1]) : null;
      return g == null ? "" : g.effect();
   }

   /** Conditions propres à la gravure (en plus du tier, des offrandes et du fluide). */
   public List<String> missing(ItemStack focus, String result) {
      List<String> out = new ArrayList<>();
      if (focus == null) {
         return out;
      } else {
         if (result.startsWith("@gravure:")) {
            if (!vanillaEnchants(focus).isEmpty()) {
               out.add("&cRetirer les enchantements vanilla (meule) : glyphes OU enchantements");
            }

            String[] p = result.split(":");
            Map<String, Integer> cur = this.read(focus);
            int want = Integer.parseInt(p[2]);
            int have = cur.getOrDefault(p[1], 0);
            if (have != want - 1) {
               out.add("&cCe glyphe a changé de niveau : repose l'objet");
            }

            if (have == 0 && cur.size() >= this.slots(focus)) {
               out.add("&cPlus d'emplacement libre (" + cur.size() + "/" + this.slots(focus) + ") : parchemin d'effacement");
            }
         }

         return out;
      }
   }

   public ItemStack apply(ItemStack focus, String result) {
      ItemStack it = focus.clone();
      Map<String, Integer> g = this.read(it);
      String[] p = result.split(":");
      if (result.equals("@repare")) {
         ItemMeta meta = it.getItemMeta();
         if (meta instanceof org.bukkit.inventory.meta.Damageable dm) {
            dm.setDamage(0);
            it.setItemMeta(meta);
         }

         return it;
      } else if (result.startsWith("@gravure:") && p.length == 3) {
         g.put(p[1], Integer.parseInt(p[2]));
      } else if (result.startsWith("@efface:") && p.length == 2) {
         g.remove(p[1]);
      }

      this.write(it, g);
      return it;
   }

   private static Map<Enchantment, Integer> vanillaEnchants(ItemStack it) {
      return it.getEnchantments();
   }

   // ------------------------------------------------------------------ enchantements : l'un OU l'autre

   @EventHandler(ignoreCancelled = true)
   public void onPrepareEnchant(PrepareItemEnchantEvent e) {
      if (this.engraved(e.getItem()) || "tome_arcanes".equals(this.plugin.items().idOf(e.getItem()))) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onEnchant(EnchantItemEvent e) {
      if (this.engraved(e.getItem())) {
         e.setCancelled(true);
         e.getEnchanter().sendActionBar(Text.c("&cUn objet gravé ne peut pas recevoir d'enchantements vanilla."));
      }
   }

   @EventHandler
   public void onAnvil(PrepareAnvilEvent e) {
      ItemStack a = e.getInventory().getFirstItem();
      ItemStack b = e.getInventory().getSecondItem();
      ItemStack r = e.getResult();
      if (a != null && r != null && this.engraved(a)) {
         boolean addsEnchant = !r.getEnchantments().isEmpty()
            || b != null && (b.getItemMeta() instanceof EnchantmentStorageMeta || !b.getEnchantments().isEmpty());
         if (addsEnchant) {
            e.setResult(null);
         }
      }
   }

   // ------------------------------------------------------------------ effets

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onMelee(EntityDamageByEntityEvent e) {
      if (e.getDamager() instanceof Player p && e.getEntity() instanceof LivingEntity) {
         ItemStack hand = p.getInventory().getItemInMainHand();
         int t = this.level(hand, "tranchant");
         if (t > 0) {
            e.setDamage(e.getDamage() + t);
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onMeleeDone(EntityDamageByEntityEvent e) {
      if (e.getDamager() instanceof Player p && e.getEntity() instanceof LivingEntity) {
         int s = this.level(p.getInventory().getItemInMainHand(), "soif");
         if (s > 0 && e.getFinalDamage() > 0.0) {
            double max = p.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            p.setHealth(Math.min(max, p.getHealth() + e.getFinalDamage() * 0.03 * s));
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onWear(PlayerItemDamageEvent e) {
      int s = this.level(e.getItem(), "solidite");
      if (s > 0 && ThreadLocalRandom.current().nextDouble() < 0.25 * s) {
         e.setCancelled(true);
      }
   }

   /** Réduction de recharge des capacités d'arme (Écho). */
   public double echoFactor(Player p) {
      return 1.0 - 0.1 * this.level(p.getInventory().getItemInMainHand(), "echo");
   }

   /** Portée de guidage des arcs / force du harpon (Œil du chasseur). */
   public int eye(ItemStack weapon) {
      return this.level(weapon, "oeil");
   }

   public int canal(ItemStack catalyst) {
      return this.level(catalyst, "canal");
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onDrops(BlockDropItemEvent e) {
      Player p = e.getPlayer();
      ItemStack tool = p.getInventory().getItemInMainHand();
      int fonte = this.level(tool, "fonte");
      boolean aimant = this.level(tool, "aimant") > 0;
      if (fonte > 0 || aimant) {
         for (Item drop : e.getItems()) {
            ItemStack st = drop.getItemStack();
            if (fonte > 0) {
               st = this.smelt(st, fonte);
               drop.setItemStack(st);
            }
         }

         if (aimant) {
            for (Item drop : new ArrayList<>(e.getItems())) {
               Map<Integer, ItemStack> left = p.getInventory().addItem(drop.getItemStack());
               if (left.isEmpty()) {
                  e.getItems().remove(drop);
               } else {
                  drop.setItemStack(left.values().iterator().next());
               }
            }
         }
      }
   }

   /** Casse un bloc pour une capacité de zone (marteau, hache du bûcheron) en tenant compte d'Aimant et de Fonte. */
   public void breakBlock(Player p, ItemStack tool, Block b) {
      int fonte = this.level(tool, "fonte");
      boolean aimant = this.level(tool, "aimant") > 0;
      if ((fonte <= 0 && !aimant) || p.getGameMode() == GameMode.CREATIVE) {
         b.breakNaturally(tool, true);
      } else {
         List<ItemStack> drops = new ArrayList<>(b.getDrops(tool, p));
         b.getWorld().playEffect(b.getLocation(), org.bukkit.Effect.STEP_SOUND, b.getType());
         b.setType(Material.AIR);
         for (ItemStack st : drops) {
            ItemStack x = fonte > 0 ? this.smelt(st, fonte) : st;
            if (aimant) {
               p.getInventory().addItem(x).values().forEach(r -> b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), r));
            } else {
               b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), x);
            }
         }
      }
   }

   private ItemStack smelt(ItemStack st, int level) {
      Material out = switch (st.getType()) {
         case RAW_IRON -> Material.IRON_INGOT;
         case RAW_GOLD -> Material.GOLD_INGOT;
         case RAW_COPPER -> Material.COPPER_INGOT;
         case ANCIENT_DEBRIS -> Material.NETHERITE_SCRAP;
         case COBBLESTONE -> Material.STONE;
         case COBBLED_DEEPSLATE -> Material.DEEPSLATE;
         case SAND -> Material.GLASS;
         case RED_SAND -> Material.GLASS;
         default -> null;
      };
      if (out == null || ThreadLocalRandom.current().nextDouble() >= level / 3.0) {
         return st;
      } else {
         ItemStack r = new ItemStack(out, st.getAmount());
         return r;
      }
   }

   public String listNames() {
      List<String> l = new ArrayList<>();
      for (Glyph g : this.glyphs.values()) {
         l.add(g.id().toLowerCase(Locale.ROOT));
      }

      return String.join(", ", l);
   }
}
