package fr.windydev.clans.ritual;

import com.google.common.collect.Multimap;
import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class CustomItems {
   private final WindyClans plugin;
   private final NamespacedKey key;
   private final Map<String, CustomItems.Def> defs = new LinkedHashMap<>();

   public CustomItems(WindyClans plugin) {
      this.plugin = plugin;
      this.key = new NamespacedKey(plugin, "item_id");
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "items.yml");
      if (!f.exists()) {
         this.plugin.saveResource("items.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

      try (InputStream in = this.plugin.getResource("items.yml")) {
         YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
         boolean changed = false;

         for (String k : def.getKeys(false)) {
            if (!y.contains(k) || def.getInt(k + ".version", 0) > y.getInt(k + ".version", 0)) {
               y.set(k, def.get(k));
               changed = true;
            }
         }

         if (changed) {
            y.save(f);
         }
      } catch (Exception var10) {
      }

      this.defs.clear();

      for (String id : y.getKeys(false)) {
         ConfigurationSection s = y.getConfigurationSection(id);
         if (s != null) {
            Material m = Material.matchMaterial(s.getString("material", "STONE"));
            if (m == null) {
               this.plugin.getLogger().warning("items.yml : matériau inconnu pour " + id);
            } else {
               this.defs
                  .put(
                     id,
                     new CustomItems.Def(
                        id,
                        m,
                        s.getString("name", id),
                        s.getStringList("lore"),
                        s.getInt("model", 0),
                        s.getConfigurationSection("ability"),
                        s.getDouble("damage-bonus", 0.0),
                        s.getDouble("speed-bonus", 0.0),
                        s.getBoolean("glint", false),
                        s.getInt("tier", 0),
                        s.getDouble("armor.armor", 0.0),
                        s.getDouble("armor.toughness", 0.0),
                        s.getDouble("armor.knockback", 0.0),
                        s.getString("armor.look", null),
                        rarity(id, s.getString("rarity", null))
                     )
                  );
            }
         }
      }

      this.plugin.getLogger().info(this.defs.size() + " objet(s) personnalisé(s) chargé(s).");
   }

   public Collection<CustomItems.Def> all() {
      return this.defs.values();
   }

   public CustomItems.Def def(String id) {
      return this.defs.get(id);
   }

   public ItemStack create(String id) {
      CustomItems.Def d = this.defs.get(id);
      if (d == null) {
         return null;
      } else {
         ItemStack it = new ItemStack(d.material());
         this.apply(it, d);
         return it;
      }
   }

   public ItemStack transform(ItemStack base, String id, boolean keepEnchants) {
      CustomItems.Def d = this.defs.get(id);
      if (d == null) {
         return null;
      } else {
         ItemStack it = new ItemStack(d.material());
         if (keepEnchants && base != null) {
            it.addUnsafeEnchantments(base.getEnchantments());
         }

         this.apply(it, d);
         return it;
      }
   }

   private void apply(ItemStack it, CustomItems.Def d) {
      ItemMeta meta = it.getItemMeta();
      meta.displayName(Text.item(d.name()));
      meta.lore(Text.lore(this.fullLore(d, List.of())));

      if (d.model() > 0) {
         meta.setCustomModelData(d.model());
      }

      if (d.glint()) {
         meta.setEnchantmentGlintOverride(true);
      }

      if (d.ability() != null && "bag".equals(d.ability().getString("type"))) {
         meta.setMaxStackSize(1);
      }

      if (d.isArmor()) {
         EquipmentSlotGroup group = d.material().getEquipmentSlot().getGroup();
         String slot = d.material().getEquipmentSlot().name().toLowerCase(Locale.ROOT);
         meta.setAttributeModifiers(null);
         meta.addAttributeModifier(Attribute.GENERIC_ARMOR, new AttributeModifier(new NamespacedKey(this.plugin, "armor_" + slot), d.armor(), Operation.ADD_NUMBER, group));
         if (d.toughness() > 0.0) {
            meta.addAttributeModifier(Attribute.GENERIC_ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(this.plugin, "toughness_" + slot), d.toughness(), Operation.ADD_NUMBER, group));
         }

         if (d.knockback() > 0.0) {
            meta.addAttributeModifier(Attribute.GENERIC_KNOCKBACK_RESISTANCE, new AttributeModifier(new NamespacedKey(this.plugin, "knockback_" + slot), d.knockback(), Operation.ADD_NUMBER, group));
         }

         if (d.look() != null) {
            equipLook(meta, d.material().getEquipmentSlot(), NamespacedKey.fromString(d.look()));
         }
      } else if (d.damageBonus() == 0.0 && d.speedBonus() == 0.0) {
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
      } else {
         Multimap<Attribute, AttributeModifier> defaults = d.material().getDefaultAttributeModifiers(EquipmentSlot.HAND);
         meta.setAttributeModifiers(null);

         for (Entry<Attribute, AttributeModifier> en : defaults.entries()) {
            AttributeModifier mod = en.getValue();
            double extra = en.getKey() == Attribute.GENERIC_ATTACK_DAMAGE
               ? d.damageBonus()
               : (en.getKey() == Attribute.GENERIC_ATTACK_SPEED ? d.speedBonus() : 0.0);
            meta.addAttributeModifier(en.getKey(), new AttributeModifier(mod.getKey(), mod.getAmount() + extra, mod.getOperation(), mod.getSlotGroup()));
         }

         if (!defaults.containsKey(Attribute.GENERIC_ATTACK_DAMAGE) && d.damageBonus() != 0.0) {
            meta.addAttributeModifier(
               Attribute.GENERIC_ATTACK_DAMAGE,
               new AttributeModifier(new NamespacedKey(this.plugin, "bonus_damage"), d.damageBonus(), Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND)
            );
         }
      }

      meta.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, d.id());
      it.setItemMeta(meta);
   }

   /** Apparence portée (serveur 1.21.2+ : composant "equippable"). Ignoré sur les versions plus anciennes. */
   private static void equipLook(ItemMeta meta, EquipmentSlot slot, NamespacedKey model) {
      if (model == null) {
         return;
      }

      try {
         Class<?> comp = Class.forName("org.bukkit.inventory.meta.components.EquippableComponent");
         Object eq = ItemMeta.class.getMethod("getEquippable").invoke(meta);
         comp.getMethod("setSlot", EquipmentSlot.class).invoke(eq, slot);
         java.lang.reflect.Method setModel;
         try {
            setModel = comp.getMethod("setAssetId", NamespacedKey.class);
         } catch (NoSuchMethodException ex) {
            setModel = comp.getMethod("setModel", NamespacedKey.class);
         }

         setModel.invoke(eq, model);
         ItemMeta.class.getMethod("setEquippable", comp).invoke(meta, eq);
      } catch (Throwable ignored) {
      }
   }

   // ------------------------------------------------------------------ raretés (v1.2)

   public static final List<String> RARITIES = List.of("commun", "peu_commun", "rare", "epique", "legendaire", "mythique");

   public static String rarityLabel(String r) {
      return switch (r) {
         case "commun" -> "&fCommun";
         case "peu_commun" -> "&aPeu commun";
         case "rare" -> "&9Rare";
         case "epique" -> "&5Épique";
         case "legendaire" -> "&6Légendaire";
         case "mythique" -> "&d&lMythique";
         default -> "&7" + r;
      };
   }

   /** Rareté : "rarity" dans items.yml, sinon déduite de l'id. */
   static String rarity(String id, String explicit) {
      if (explicit != null && RARITIES.contains(explicit.toLowerCase(Locale.ROOT))) {
         return explicit.toLowerCase(Locale.ROOT);
      } else if (id.equals("grimoire_meteore")) {
         return "mythique";
      } else if (id.equals("grimoire_chaine") || id.equals("grimoire_ombre") || id.equals("grimoire_egide")) {
         return "epique";
      } else if (id.startsWith("grimoire_")) {
         return "rare";
      } else if (id.startsWith("arcanite_") || id.equals("baton_3")) {
         return "legendaire";
      } else if (id.startsWith("argent_")) {
         return "epique";
      } else if (id.startsWith("acier_")) {
         return "rare";
      } else if (id.equals("rune_vide")) {
         return "epique";
      } else if (id.equals("rune_glace") || id.equals("rune_foudre")) {
         return "rare";
      } else if (id.equals("piece")) {
         return "commun";
      } else {
         boolean gear = id.matches("(epee|arc|trident|hache|hache_guerre|baton)_[0-9]") || id.startsWith("marteau_");
         if (id.equals("marteau_titan") || gear && id.endsWith("_3")) {
            return "legendaire";
         } else if (gear && id.endsWith("_2") || id.endsWith("_3")) {
            return "epique";
         } else if (gear || id.endsWith("_2") || id.equals("marteau_runique")) {
            return "rare";
         } else {
            return "peu_commun";
         }
      }
   }

   /** Description complète : lignes de items.yml, lignes ajoutées (glyphes) puis la rareté. */
   public List<String> fullLore(CustomItems.Def d, List<String> extra) {
      List<String> out = new java.util.ArrayList<>(d.lore());
      out.addAll(extra);
      if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
         out.add("");
      }

      out.add(rarityLabel(d.rarity()));
      return out;
   }

   public String idOf(ItemStack it) {
      return it != null && it.hasItemMeta() ? (String)it.getItemMeta().getPersistentDataContainer().get(this.key, PersistentDataType.STRING) : null;
   }

   public CustomItems.Def defOf(ItemStack it) {
      String id = this.idOf(it);
      return id == null ? null : this.defs.get(id);
   }

   public boolean matches(ItemStack it, String spec) {
      if (it == null || it.getType().isAir() || spec == null) {
         return false;
      } else if (spec.startsWith("windy:")) {
         return spec.substring(6).equals(this.idOf(it));
      } else {
         Material m = Material.matchMaterial(spec);
         return m == it.getType() && this.idOf(it) == null;
      }
   }

   public String displayName(String spec) {
      if (spec.startsWith("windy:")) {
         CustomItems.Def d = this.defs.get(spec.substring(6));
         return d == null ? spec : d.name();
      } else {
         return "&f" + prettify(spec);
      }
   }

   public static String prettify(String material) {
      String s = material.toLowerCase(Locale.ROOT).replace('_', ' ');
      return Character.toUpperCase(s.charAt(0)) + s.substring(1);
   }

   public record Def(
      String id,
      Material material,
      String name,
      List<String> lore,
      int model,
      ConfigurationSection ability,
      double damageBonus,
      double speedBonus,
      boolean glint,
      int tier,
      double armor,
      double toughness,
      double knockback,
      String look,
      String rarity
   ) {
      public boolean isArmor() {
         return this.armor > 0.0 || this.toughness > 0.0;
      }
   }
}
