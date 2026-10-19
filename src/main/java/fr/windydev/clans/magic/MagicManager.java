package fr.windydev.clans.magic;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Relation;
import fr.windydev.clans.profile.Profile;
import fr.windydev.clans.util.Glyphs;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Magie (v1.2) : catalyseurs, mana, 3 emplacements de sorts, 6 sorts appris avec les grimoires,
 * HUD au-dessus de la barre d'objets et onglet « Grimoire ».
 */
public class MagicManager implements Listener, CommandExecutor {
   public static final Key FONT = Key.key("windy", "magic");
   public static final List<String> ORDER = List.of("trait", "nova", "chaine", "ombre", "egide", "meteore");
   private static final Map<String, Material> ICON = Map.of(
      "trait", Material.AMETHYST_SHARD,
      "nova", Material.BLUE_ICE,
      "chaine", Material.LIGHTNING_ROD,
      "ombre", Material.ENDER_PEARL,
      "egide", Material.SHIELD,
      "meteore", Material.FIRE_CHARGE
   );
   private static final Map<String, String> SYMBOL = Map.of(
      "trait", "✦", "nova", "❄", "chaine", "⚡", "ombre", "☾", "egide", "⛨", "meteore", "☄"
   );

   /** élément posé par chaque sort (modifiable dans magie.yml : element) */
   private static final Map<String, String> ELEMENT = Map.of(
      "trait", "arcane", "nova", "givre", "chaine", "foudre", "meteore", "feu", "ombre", "ombre", "egide", ""
   );
   static final Map<String, Integer> EL_RGB = Map.of(
      "arcane", 0xB06BFF, "givre", 0x7FDBFF, "foudre", 0xFFE45C, "feu", 0xFF8A2A, "ombre", 0x8A5CC8
   );
   private static final Map<String, String> EL_LABEL = Map.of(
      "arcane", "&dArcane", "givre", "&bGivre", "foudre", "&eFoudre", "feu", "&6Feu", "ombre", "&5Ombre"
   );
   private static final Map<String, String> EL_STATE = Map.of(
      "arcane", "&dMarque arcanique", "givre", "&bGelé", "foudre", "&eÉlectrisé", "feu", "&6Embrasé", "ombre", "&5Marque d'ombre"
   );

   private final WindyClans plugin;
   private final Fx fx;
   private final Map<UUID, Channel> channels = new HashMap<>();
   private final Map<UUID, Combo> combos = new HashMap<>();
   private final Map<UUID, State> states = new HashMap<>();
   private final Map<String, Reaction> reactions = new LinkedHashMap<>();
   private final Map<UUID, Long> lastDeny = new HashMap<>();
   private int tick;
   private boolean chargeEnabled = true;
   private boolean chargeBar = true;
   private boolean chargeSlow = true;
   private int releaseTicks = 6;
   private int maxHold = 60;
   private int perfectTicks = 10;
   private double perfectBonus = 0.2;
   private double chargeMana = 0.5;
   private int comboMax = 3;
   private long comboMs = 2500L;
   private double comboDamage = 0.1;
   private double comboMana = 0.1;
   private long stateMs = 6000L;
   private double bossReaction = 0.5;
   private double refund = 10.0;
   private int fragmentsNeeded = 3;
   private final Map<UUID, Long> hudUntil = new HashMap<>();
   private final NamespacedKey riftBossKey;
   private final Map<String, Spell> spells = new LinkedHashMap<>();
   private final Map<String, Catalyst> catalysts = new HashMap<>();
   private final Map<String, String> grimoireToSpell = new HashMap<>();
   private final Map<UUID, Data> data = new HashMap<>();
   private final Map<UUID, Double> mana = new HashMap<>();
   private final Map<UUID, Map<String, Long>> ready = new HashMap<>();
   private final Map<UUID, Long> lastCast = new HashMap<>();
   private final Set<UUID> hudShown = new HashSet<>();
   private final NamespacedKey bossKey;
   private final NamespacedKey dungeonKey;
   private File dataFile;
   private boolean dirty;
   private int slotCount = 3;
   private double baseMana = 100.0;
   private double regen = 2.0;
   private double pvpMult = 0.5;
   private double arcManaBonus = 20.0;
   private double arcManaPerRank = 10.0;
   private double arcRegen = 1.0;

   record Spell(String id, String name, String grimoire, int mana, int cooldown, ConfigurationSection cfg, List<String> description) {
      // get(k) lit aussi les valeurs par défaut livrées dans le jar (nouvelles clés d'une mise à jour)
      double d(String k, double def) {
         return this.cfg.get(k) instanceof Number n ? n.doubleValue() : def;
      }

      int i(String k, int def) {
         return this.cfg.get(k) instanceof Number n ? n.intValue() : def;
      }

      String s(String k, String def) {
         return this.cfg.get(k) instanceof String v ? v : def;
      }
   }

   record Catalyst(String itemId, double mana, double cooldown, double power) {
   }

   static final class Data {
      final Set<String> learned = new LinkedHashSet<>();
      String[] slots;
      int selected;
      int fragments;
      boolean innate;
      boolean innateOff;

      Data(int n) {
         this.slots = new String[n];
      }
   }

   public MagicManager(WindyClans plugin) {
      this.plugin = plugin;
      this.fx = new Fx(plugin);
      this.bossKey = new NamespacedKey(plugin, "boss");
      this.dungeonKey = new NamespacedKey(plugin, "dungeon");
      this.riftBossKey = new NamespacedKey(plugin, "rift_boss");
   }

   // ------------------------------------------------------------------ configuration

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "magie.yml");
      if (!f.exists()) {
         this.plugin.saveResource("magie.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

      try (InputStream in = this.plugin.getResource("magie.yml")) {
         if (in != null) {
            y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
         }
      } catch (Exception ignored) {
      }

      y = fr.windydev.clans.util.YamlMerge.addNew(this.plugin, f, y, "magie.yml", "catalysts");
      this.slotCount = Math.max(1, Math.min(6, y.getInt("settings.slots", 3)));
      this.baseMana = y.getDouble("settings.mana.base", 100.0);
      this.regen = y.getDouble("settings.mana.regen-per-second", 2.0);
      this.pvpMult = y.getDouble("settings.pvp-multiplier", 0.5);
      this.arcManaBonus = y.getDouble("settings.arcanien.mana-bonus", 20.0);
      this.arcManaPerRank = y.getDouble("settings.arcanien.mana-bonus-per-rank", 10.0);
      this.arcRegen = y.getDouble("settings.arcanien.regen-bonus", 1.0);
      y = fr.windydev.clans.util.YamlMerge.addNew(this.plugin, f, y, "magie.yml", "reactions");
      this.chargeEnabled = y.getBoolean("settings.charge.enabled", true);
      this.chargeBar = y.getBoolean("settings.charge.bar", true);
      this.chargeSlow = y.getBoolean("settings.charge.slow", true);
      this.releaseTicks = Math.max(5, y.getInt("settings.charge.release-ticks", 6));
      this.maxHold = Math.max(0, y.getInt("settings.charge.max-hold-ticks", 60));
      this.perfectTicks = Math.max(0, y.getInt("settings.charge.perfect-window-ticks", 10));
      this.perfectBonus = y.getDouble("settings.charge.perfect-bonus", 0.2);
      this.chargeMana = Math.max(0.0, y.getDouble("settings.charge.mana-surcharge", 0.5));
      this.comboMax = Math.max(0, y.getInt("settings.combo.max", 3));
      this.comboMs = Math.round(y.getDouble("settings.combo.window-seconds", 2.5) * 1000.0);
      this.comboDamage = y.getDouble("settings.combo.damage-per-level", 0.1);
      this.comboMana = Math.min(0.3, Math.max(0.0, y.getDouble("settings.combo.mana-discount-per-level", 0.1)));
      this.stateMs = Math.round(y.getDouble("settings.reactions.state-seconds", 6.0) * 1000.0);
      this.bossReaction = y.getDouble("settings.reactions.boss-factor", 0.5);
      this.refund = y.getDouble("settings.reactions.refund-mana", 10.0);
      this.fragmentsNeeded = Math.max(1, y.getInt("settings.innate.fragments", 3));
      this.fx.displays = y.getBoolean("settings.effects.displays", true);
      this.fx.numbers = y.getBoolean("settings.effects.damage-numbers", true);
      this.reactions.clear();
      ConfigurationSection rs = y.getConfigurationSection("reactions");
      if (rs != null && y.getBoolean("settings.reactions.enabled", true)) {
         for (String k : rs.getKeys(false)) {
            String[] ab = k.toLowerCase(Locale.ROOT).split("\\+");
            if (ab.length == 2) {
               this.reactions.put(ab[0] + "+" + ab[1], new Reaction(ab[0], ab[1], rs.getString(k + ".name", k), rs.getDouble(k + ".multiplier", 1.0),
                  rs.getDouble(k + ".radius", 0.0), rs.getDouble(k + ".splash", 0.0), rs.getString(k + ".effect", "none").toLowerCase(Locale.ROOT)));
            }
         }
      }

      this.catalysts.clear();
      ConfigurationSection cs = y.getConfigurationSection("catalysts");
      if (cs != null) {
         for (String k : cs.getKeys(false)) {
            this.catalysts.put(k, new Catalyst(k, cs.getDouble(k + ".mana"), cs.getDouble(k + ".cooldown"), cs.getDouble(k + ".power")));
         }
      }

      this.spells.clear();
      this.grimoireToSpell.clear();
      ConfigurationSection ss = y.getConfigurationSection("spells");
      if (ss != null) {
         for (String id : ORDER) {
            ConfigurationSection s = ss.getConfigurationSection(id);
            if (s != null) {
               Spell sp = new Spell(
                  id, s.getString("name", id), s.getString("grimoire", "grimoire_" + id), s.getInt("mana", 20), s.getInt("cooldown", 10), s, s.getStringList("description")
               );
               this.spells.put(id, sp);
               this.grimoireToSpell.put(sp.grimoire(), id);
            }
         }
      }

      this.dataFile = new File(this.plugin.getDataFolder(), "magie-data.yml");
      this.data.clear();
      YamlConfiguration d = YamlConfiguration.loadConfiguration(this.dataFile);
      ConfigurationSection ps = d.getConfigurationSection("players");
      if (ps != null) {
         for (String k : ps.getKeys(false)) {
            try {
               UUID id = UUID.fromString(k);
               Data x = new Data(this.slotCount);
               for (String l : ps.getStringList(k + ".learned")) {
                  if (this.spells.containsKey(l)) {
                     x.learned.add(l);
                  }
               }

               List<String> sl = ps.getStringList(k + ".slots");
               for (int i = 0; i < this.slotCount && i < sl.size(); i++) {
                  String v = sl.get(i);
                  x.slots[i] = v != null && x.learned.contains(v) ? v : null;
               }

               x.selected = Math.max(0, Math.min(this.slotCount - 1, ps.getInt(k + ".selected")));
               x.fragments = Math.max(0, ps.getInt(k + ".fragments"));
               x.innate = ps.getBoolean(k + ".innate");
               x.innateOff = ps.getBoolean(k + ".innate-off");
               this.data.put(id, x);
            } catch (IllegalArgumentException ignored) {
            }
         }
      }

      this.plugin.getLogger().info("Magie : " + this.spells.size() + " sort(s), " + this.catalysts.size() + " catalyseur(s), " + this.reactions.size() + " réaction(s).");
   }

   public void save() {
      YamlConfiguration y = new YamlConfiguration();
      for (Map.Entry<UUID, Data> e : this.data.entrySet()) {
         Data x = e.getValue();
         if (x.learned.isEmpty() && x.fragments == 0 && !x.innate) {
            continue;
         }

         String b = "players." + e.getKey() + ".";
         y.set(b + "learned", new ArrayList<>(x.learned));
         List<String> sl = new ArrayList<>();
         for (String s : x.slots) {
            sl.add(s == null ? "" : s);
         }

         y.set(b + "slots", sl);
         y.set(b + "selected", x.selected);
         if (x.fragments > 0) {
            y.set(b + "fragments", x.fragments);
         }

         if (x.innate) {
            y.set(b + "innate", true);
         }

         if (x.innateOff) {
            y.set(b + "innate-off", true);
         }
      }

      try {
         y.save(this.dataFile);
         this.dirty = false;
      } catch (IOException ex) {
         this.plugin.getLogger().warning("magie-data.yml : sauvegarde impossible : " + ex.getMessage());
      }
   }

   public void start() {
      // régénération du mana
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         for (Player p : Bukkit.getOnlinePlayers()) {
            double max = this.maxMana(p);
            double cur = this.mana(p);
            if (cur < max) {
               double r = this.regen + (this.isArcanien(p) ? this.arcRegen : 0.0);
               this.mana.put(p.getUniqueId(), Math.min(max, cur + r / 2.0));
            }
         }
      }, 10L, 10L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickChannels, 1L, 1L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickStates, 5L, 5L);
      // HUD
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         for (Player p : Bukkit.getOnlinePlayers()) {
            Long hu = this.hudUntil.get(p.getUniqueId());
            if (this.catalyst(p) != null || hu != null && hu > System.currentTimeMillis()) {
               p.sendActionBar(this.hud(p));
               this.hudShown.add(p.getUniqueId());
            } else if (this.hudShown.remove(p.getUniqueId())) {
               p.sendActionBar(Component.empty());
            }
         }
      }, 5L, 4L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         if (this.dirty) {
            this.save();
         }
      }, 1200L, 1200L);
   }

   public void shutdown() {
      for (Map.Entry<UUID, Channel> en : new ArrayList<>(this.channels.entrySet())) {
         this.endChannel(en.getKey(), en.getValue());
      }

      this.fx.shutdown();
      if (this.dirty) {
         this.save();
      }
   }

   // ------------------------------------------------------------------ état joueur

   private Data data(UUID id) {
      return this.data.computeIfAbsent(id, k -> new Data(this.slotCount));
   }

   public boolean isArcanien(Player p) {
      Profile pr = this.plugin.profiles().of(p);
      return pr != null && "arcanien".equals(pr.race());
   }

   public Catalyst catalyst(Player p) {
      ItemStack it = p.getInventory().getItemInMainHand();
      String id = this.plugin.items().idOf(it);
      return id == null ? null : this.catalysts.get(id);
   }

   public double maxMana(Player p) {
      double m = this.baseMana;
      Catalyst c = this.catalyst(p);
      if (c != null) {
         m += c.mana() + 10.0 * this.plugin.glyphs().canal(p.getInventory().getItemInMainHand());
      }

      if (this.isArcanien(p)) {
         Profile pr = this.plugin.profiles().of(p);
         m += this.arcManaBonus + this.arcManaPerRank * Math.max(0, pr.rank() - 1);
      }

      return m;
   }

   public double mana(Player p) {
      return this.mana.computeIfAbsent(p.getUniqueId(), k -> this.maxMana(p));
   }

   private double cooldownFactor(Player p, Catalyst c) {
      double red = c == null ? 0.0 : c.cooldown();
      if (this.isArcanien(p)) {
         red += Math.max(0.0, this.plugin.profiles().stat(p, "ability-cooldown"));
      }

      return Math.max(0.4, 1.0 - red);
   }

   private long cooldownLeft(Player p, String spell) {
      Map<String, Long> m = this.ready.get(p.getUniqueId());
      if (m == null) {
         return 0L;
      } else {
         Long t = m.get(spell);
         return t == null ? 0L : Math.max(0L, t - System.currentTimeMillis());
      }
   }

   private long cooldownTotal(Player p, String spell) {
      Map<String, Long> m = this.ready.get(p.getUniqueId());
      Long t = m == null ? null : m.get(spell + "#total");
      return t == null ? 1L : Math.max(1L, t);
   }

   public boolean learn(UUID id, String spell) {
      if (!this.spells.containsKey(spell)) {
         return false;
      } else {
         Data d = this.data(id);
         if (!d.learned.add(spell)) {
            return false;
         } else {
            for (int i = 0; i < d.slots.length; i++) {
               if (d.slots[i] == null) {
                  d.slots[i] = spell;
                  break;
               }
            }

            this.dirty = true;
            return true;
         }
      }
   }

   public void forget(UUID id, String spell) {
      Data d = this.data(id);
      if (spell == null) {
         d.learned.clear();
         d.slots = new String[this.slotCount];
      } else {
         d.learned.remove(spell);
         for (int i = 0; i < d.slots.length; i++) {
            if (spell.equals(d.slots[i])) {
               d.slots[i] = null;
            }
         }
      }

      this.dirty = true;
   }

   public String spellOfGrimoire(String itemId) {
      return itemId == null ? null : this.grimoireToSpell.get(itemId);
   }

   public boolean knows(UUID id, String spell) {
      Data d = this.data.get(id);
      return d != null && d.learned.contains(spell);
   }

   public int learnedCount(UUID id) {
      Data d = this.data.get(id);
      return d == null ? 0 : d.learned.size();
   }

   // ------------------------------------------------------------------ événements

   @EventHandler(priority = EventPriority.HIGH)
   public void onInteract(PlayerInteractEvent e) {
      if (e.getHand() != EquipmentSlot.HAND) {
         return;
      }

      Action a = e.getAction();
      if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
         return;
      }

      Player p = e.getPlayer();
      ItemStack it = e.getItem();
      String id = this.plugin.items().idOf(it);
      if (id == null) {
         return;
      }

      String fromGrimoire = this.grimoireToSpell.get(id);
      if (fromGrimoire != null) {
         e.setCancelled(true);
         this.useGrimoire(p, it, fromGrimoire);
         return;
      }

      if (!this.catalysts.containsKey(id)) {
         return;
      }

      if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable() && !p.isSneaking()) {
         return; // coffre, porte... : interaction normale
      }

      e.setCancelled(true);
      Channel ch = this.channels.get(p.getUniqueId());
      if (ch != null) {
         ch.last = this.tick; // clic maintenu : la charge continue
         return;
      }

      Long last = this.lastCast.get(p.getUniqueId());
      long now = System.currentTimeMillis();
      if (last != null && now - last < 150L) {
         return; // double évènement (bloc + air)
      }

      this.lastCast.put(p.getUniqueId(), now);
      if (p.isSneaking()) {
         this.cycle(p);
      } else {
         this.press(p);
      }
   }

   // ---- incantation innée (récompense de la Faille arcanique) : sans catalyseur, avec n'importe quel objet en main
   //      F = lancer le sort sélectionné, accroupi + clic gauche dans le vide = sort suivant

   public int fragments(UUID id) {
      Data d = this.data.get(id);
      return d == null ? 0 : d.fragments;
   }

   public int fragmentsNeeded() {
      return this.fragmentsNeeded;
   }

   public boolean innate(UUID id) {
      Data d = this.data.get(id);
      return d != null && d.innate;
   }

   private boolean innateActive(Player p) {
      Data d = this.data.get(p.getUniqueId());
      return d != null && d.innate && !d.innateOff && this.catalyst(p) == null;
   }

   /** Un rituel réussi : +1 fragment ; au dernier, l'incantation innée est acquise pour toujours. */
   public void grantFragment(Player p) {
      Data d = this.data(p.getUniqueId());
      this.dirty = true;
      if (d.innate) {
         p.sendMessage(Text.c("&5✦ &7Tu maîtrises déjà l'incantation innée : ce fragment se dissipe."));
         return;
      }

      d.fragments++;
      if (d.fragments < this.fragmentsNeeded) {
         p.sendMessage(Text.c("&5✦ &dFragment d'éveil &7obtenu : &f" + d.fragments + "&7/&f" + this.fragmentsNeeded + "&7."));
         p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&d✦ Fragment d'éveil"), Text.c("&f" + d.fragments + " &7/ &f" + this.fragmentsNeeded)));
         return;
      }

      d.innate = true;
      d.innateOff = false;
      Location l = p.getLocation().add(0.0, 1.0, 0.0);
      p.getWorld().spawnParticle(Particle.END_ROD, l, 120, 0.6, 1.0, 0.6, 0.15);
      p.getWorld().spawnParticle(Particle.ENCHANT, l, 200, 0.8, 1.2, 0.8, 1.5);
      p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.8F);
      p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0F, 1.4F);
      p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&5✦ Incantation innée"), Text.c("&7La magie coule en toi, sans catalyseur")));
      p.sendMessage(Text.c("&5✦ &dTu as acquis l'incantation innée !"));
      p.sendMessage(Text.c("&7Sans catalyseur, avec n'importe quel objet en main : &fF &7lance le sort, &faccroupi + clic gauche dans le vide &7change de sort."));
      p.sendMessage(Text.c("&7Le catalyseur garde ses avantages : charge, mana et puissance en plus. &8(réglage : /grimoire)"));
      Bukkit.broadcast(Text.c("&5✦ &f" + p.getName() + " &da acquis l'&5incantation innée &d!"));
   }

   @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
   public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
      Player p = e.getPlayer();
      if (p.isSneaking() || !this.innateActive(p) || this.selected(p) == null) {
         return; // accroupi + F reste le pouvoir de race
      }

      e.setCancelled(true);
      Spell sp = this.spells.get(this.selected(p));
      this.hudUntil.put(p.getUniqueId(), System.currentTimeMillis() + 6000L);
      if (sp != null && p.getGameMode() != GameMode.SPECTATOR && this.precheck(p, sp)) {
         this.cast(p, sp, 0.0, false);
      }
   }

   @EventHandler
   public void onLeftClick(PlayerInteractEvent e) {
      if (e.getAction() == Action.LEFT_CLICK_AIR && e.getHand() == EquipmentSlot.HAND && e.getPlayer().isSneaking() && this.innateActive(e.getPlayer())) {
         this.cycle(e.getPlayer());
         this.hudUntil.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + 6000L);
      }
   }

   @EventHandler
   public void onHeld(PlayerItemHeldEvent e) {
      Player p = e.getPlayer();
      ItemStack it = p.getInventory().getItem(e.getNewSlot());
      String id = this.plugin.items().idOf(it);
      if (id != null && this.catalysts.containsKey(id)) {
         Data d = this.data(p.getUniqueId());
         if (d.learned.isEmpty()) {
            p.sendMessage(Text.c("&5✦ &7Ce bâton canalise la magie, mais tu ne connais aucun sort. &dLes grimoires se trouvent dans les donjons."));
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      this.mana.remove(e.getPlayer().getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      UUID id = e.getPlayer().getUniqueId();
      this.mana.remove(id);
      this.hudShown.remove(id);
      this.lastCast.remove(id);
      this.lastDeny.remove(id);
      this.hudUntil.remove(id);
      this.combos.remove(id);
      Channel ch = this.channels.get(id);
      if (ch != null) {
         this.endChannel(id, ch);
      }
   }

   private void useGrimoire(Player p, ItemStack it, String spellId) {
      Spell sp = this.spells.get(spellId);
      if (sp == null) {
         return;
      }

      Data d = this.data(p.getUniqueId());
      if (d.learned.contains(spellId)) {
         p.sendMessage(Text.c("&7Tu connais déjà " + sp.name() + "&7."));
         p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6F, 0.8F);
         return;
      }

      it.setAmount(it.getAmount() - 1);
      this.learn(p.getUniqueId(), spellId);
      Location l = p.getLocation().add(0.0, 1.0, 0.0);
      p.getWorld().spawnParticle(Particle.ENCHANT, l, 80, 0.6, 0.8, 0.6, 1.0);
      p.getWorld().spawnParticle(Particle.END_ROD, l, 20, 0.4, 0.6, 0.4, 0.05);
      p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.2F);
      p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0F, 1.0F);
      p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&5✦ Sort appris"), Text.c(sp.name())));
      boolean slotted = false;
      for (String s : d.slots) {
         if (spellId.equals(s)) {
            slotted = true;
         }
      }

      p.sendMessage(Text.c("&5✦ &7Tu as appris " + sp.name() + "&7 !"
         + (slotted ? " &8(placé dans un emplacement)" : " &8(Profil > Grimoire pour l'équiper)")));
      p.sendMessage(Text.c("&7Bâton en main : &fclic droit &7lance, &fclic maintenu &7charge, &faccroupi + clic droit &7change de sort."));
   }

   // ------------------------------------------------------------------ sélection et lancement

   /** Un lancer : qui, quel sort, sa puissance, sa charge (0 à 1). */
   record Cast(Player p, Spell sp, String element, double power, double charge, boolean perfect) {
      boolean full() {
         return this.charge >= 0.999;
      }
   }

   static final class Channel {
      final String spell;
      final int start;
      final int need;
      int last;
      boolean dinged;
      Fx.Decal circle;
      BossBar bar;

      Channel(String spell, int start, int need) {
         this.spell = spell;
         this.start = start;
         this.last = start;
         this.need = need;
      }
   }

   static final class Combo {
      int level;
      long until;
      String last;
   }

   record State(String element, long until) {
   }

   record Reaction(String state, String trigger, String name, double mult, double radius, double splash, String effect) {
   }

   private void cycle(Player p) {
      Data d = this.data(p.getUniqueId());
      int n = d.slots.length;
      for (int k = 1; k <= n; k++) {
         int i = (d.selected + k) % n;
         if (d.slots[i] != null) {
            d.selected = i;
            this.dirty = true;
            Spell sp = this.spells.get(d.slots[i]);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5F, 1.6F);
            if (!this.plugin.pack().has(p) && sp != null) {
               p.sendActionBar(Text.c("&7Sort : " + sp.name()));
            }

            return;
         }
      }

      p.sendActionBar(Text.c("&7Aucun sort équipé &8(Profil > Grimoire)"));
   }

   private String selected(Player p) {
      Data d = this.data(p.getUniqueId());
      if (d.slots[d.selected] == null) {
         for (int i = 0; i < d.slots.length; i++) {
            if (d.slots[i] != null) {
               d.selected = i;
               return d.slots[i];
            }
         }
      }

      return d.slots[d.selected];
   }

   /** Son de refus, au plus toutes les 0,6 s (le clic maintenu répète l'évènement). */
   private boolean deny(Player p, Sound s, float pitch) {
      long now = System.currentTimeMillis();
      Long l = this.lastDeny.get(p.getUniqueId());
      if (l != null && now - l < 600L) {
         return false;
      }

      this.lastDeny.put(p.getUniqueId(), now);
      p.playSound(p.getLocation(), s, 0.5F, pitch);
      return true;
   }

   private boolean precheck(Player p, Spell sp) {
      if (this.cooldownLeft(p, sp.id()) > 0L) {
         this.deny(p, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6F);
         return false;
      }

      double cur = this.mana(p);
      if (cur < sp.mana() * (1.0 - this.comboMana * this.comboLevel(p, sp.id()))) {
         if (this.deny(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6F) && !this.plugin.pack().has(p)) {
            p.sendActionBar(Text.c("&cPas assez de mana &8(" + (int)cur + "/" + sp.mana() + ")"));
         }

         return false;
      }

      return true;
   }

   /** Clic droit avec le catalyseur : lance, ou commence à charger si le sort se charge. */
   private void press(Player p) {
      UUID u = p.getUniqueId();
      String id = this.selected(p);
      if (id == null) {
         if (this.deny(p, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6F)) {
            p.sendMessage(Text.c("&7Aucun sort équipé. &dApprends un sort avec un grimoire &7(butin de donjon)."));
         }

         return;
      }

      Spell sp = this.spells.get(id);
      if (sp == null || p.getGameMode() == GameMode.SPECTATOR || !this.precheck(p, sp)) {
         return;
      }

      int need = this.chargeEnabled ? (int)Math.round(sp.d("charge-seconds", 0.0) * 20.0) : 0;
      if (need <= 0) {
         this.cast(p, sp, 0.0, false);
         return;
      }

      Channel ch = new Channel(id, this.tick, need);
      ch.circle = this.fx.decal(p.getLocation(), this.circleOf(sp), 1.2F, 0, 0.1F);
      if (this.chargeBar) {
         ch.bar = BossBar.bossBar(Text.c(sp.name()), 0.0F, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
         p.showBossBar(ch.bar);
      }

      this.channels.put(u, ch);
   }

   private void endChannel(UUID u, Channel ch) {
      this.channels.remove(u);
      if (ch.circle != null) {
         ch.circle.remove();
      }

      if (ch.bar != null) {
         Player p = Bukkit.getPlayer(u);
         if (p != null) {
            p.hideBossBar(ch.bar);
         }
      }
   }

   private Location hand(Player p) {
      Location e = p.getEyeLocation();
      Vector d = e.getDirection();
      Vector right = new Vector(-d.getZ(), 0.0, d.getX());
      if (right.lengthSquared() > 1.0E-4) {
         right.normalize();
      }

      return e.add(d.multiply(0.9)).add(right.multiply(0.4)).subtract(0.0, 0.35, 0.0);
   }

   /** Chaque tick : fait avancer les charges en cours et lance le sort quand le clic est relâché. */
   private void tickChannels() {
      this.tick++;
      if (this.channels.isEmpty()) {
         return;
      }

      for (Map.Entry<UUID, Channel> en : new ArrayList<>(this.channels.entrySet())) {
         UUID u = en.getKey();
         Channel ch = en.getValue();
         Player p = Bukkit.getPlayer(u);
         Spell sp = this.spells.get(ch.spell);
         if (p == null || p.isDead() || sp == null || this.catalyst(p) == null || !ch.spell.equals(this.data(u).slots[this.data(u).selected])) {
            this.endChannel(u, ch);
            continue;
         }

         int elapsed = this.tick - ch.start;
         int held = ch.last - ch.start;
         boolean released = this.tick - ch.last > this.releaseTicks;
         if (released || elapsed > ch.need + this.maxHold) {
            this.endChannel(u, ch);
            // le clic maintenu arrive toutes les 4 ticks : 3 ticks de tolérance
            boolean full = held + 3 >= ch.need;
            double f = full ? 1.0 : (double)held / ch.need;
            if (f < 0.15) {
               f = 0.0;
            }

            boolean perfect = released && full && ch.dinged && held <= ch.need + this.perfectTicks;
            if (this.precheck(p, sp)) {
               this.cast(p, sp, f, perfect);
            }

            continue;
         }

         double f = Math.min(1.0, (double)elapsed / ch.need);
         int rgb = EL_RGB.getOrDefault(this.elementOf(sp), 0xB06BFF);
         Location h = this.hand(p);
         World w = p.getWorld();
         if (elapsed % 2 == 0) {
            w.spawnParticle(Particle.DUST, h, 1 + (int)(f * 3), 0.05 + f * 0.08, 0.05 + f * 0.08, 0.05 + f * 0.08, 0.0,
               new Particle.DustOptions(Color.fromRGB(rgb), (float)(0.6 + f * 0.9)));
            w.spawnParticle(Particle.ENCHANT, h, 3, 0.4, 0.4, 0.4, 0.6);
         }

         if (elapsed % 4 == 0) {
            w.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6F, (float)(0.6 + f * 1.3));
            if (this.chargeSlow) {
               p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 8, 0, false, false, false));
            }
         }

         if (!ch.dinged && elapsed >= ch.need) {
            ch.dinged = true;
            w.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9F, 1.8F);
            w.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6F, 2.0F);
            w.spawnParticle(Particle.END_ROD, h, 14, 0.1, 0.1, 0.1, 0.08);
         }

         if (ch.circle != null) {
            ch.circle.size((float)(1.2 + f * 1.8));
            ch.circle.move(p.getLocation());
         }

         if (ch.bar != null) {
            ch.bar.progress((float)f);
            if (ch.dinged && elapsed <= ch.need + this.perfectTicks) {
               ch.bar.color(BossBar.Color.YELLOW);
               ch.bar.name(Text.c("&e&lRelâche !"));
            } else if (ch.dinged) {
               ch.bar.color(BossBar.Color.WHITE);
               ch.bar.name(Text.c(sp.name() + " &7(chargé)"));
            }
         }
      }
   }

   /** Niveau d'enchaînement qu'aurait ce sort s'il était lancé maintenant. */
   private int comboLevel(Player p, String spell) {
      Combo cb = this.combos.get(p.getUniqueId());
      if (this.comboMax <= 0 || cb == null || System.currentTimeMillis() >= cb.until) {
         return 0;
      }

      return spell.equals(cb.last) ? cb.level : Math.min(this.comboMax, cb.level + 1);
   }

   private void cast(Player p, Spell sp, double f, boolean perfect) {
      UUID u = p.getUniqueId();
      String id = sp.id();
      Catalyst c = this.catalyst(p);
      int lvl = this.comboLevel(p, id);
      double cur = this.mana(p);
      double base = sp.mana() * (1.0 - this.comboMana * lvl);
      double extra = perfect ? 0.0 : this.chargeMana * f;
      if (cur < base * (1.0 + extra) && this.chargeMana > 0.0) {
         // pas assez de mana pour toute la charge : elle est réduite
         f = Math.max(0.0, Math.min(f, (cur / Math.max(1.0, base) - 1.0) / this.chargeMana));
         extra = this.chargeMana * f;
      }

      double cost = Math.min(cur, base * (1.0 + extra));
      double power = (1.0 + (c == null ? 0.0 : c.power())) * (1.0 + this.comboDamage * lvl) * (perfect ? 1.0 + this.perfectBonus : 1.0);
      Cast ctx = new Cast(p, sp, this.elementOf(sp), power, f, perfect);
      boolean ok = switch (id) {
         case "trait" -> this.trait(ctx);
         case "nova" -> this.nova(ctx);
         case "chaine" -> this.chaine(ctx);
         case "ombre" -> this.ombre(ctx);
         case "egide" -> this.egide(ctx);
         case "meteore" -> this.meteore(ctx);
         default -> false;
      };
      if (!ok) {
         return;
      }

      this.mana.put(u, Math.max(0.0, cur - cost));
      long total = Math.round(sp.cooldown() * 1000.0 * this.cooldownFactor(p, c));
      Map<String, Long> m = this.ready.computeIfAbsent(u, k -> new HashMap<>());
      m.put(id, System.currentTimeMillis() + total);
      m.put(id + "#total", total);
      Combo cb = this.combos.get(u);
      if (cb != null) {
         cb.level = lvl;
      }

      World w = p.getWorld();
      if (!id.equals("ombre") && !id.equals("egide")) {
         this.fx.decal(p.getLocation(), this.circleOf(sp), (float)(2.0 + f * 1.4), 10, 0.3F);
      }

      if (perfect) {
         w.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8F, 2.0F);
         this.fx.text(p.getLocation().add(0.0, 2.3, 0.0), "Parfait !", 0xFFD75E, 1.1F, true);
      }

      if (lvl > 0) {
         w.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7F, 1.0F + 0.3F * lvl);
      }
   }

   // ------------------------------------------------------------------ cibles

   private boolean validTarget(Player caster, Entity e) {
      if (!(e instanceof LivingEntity le) || e == caster || le.isDead() || e instanceof ArmorStand || le.isInvulnerable()) {
         return false;
      } else if (e instanceof Player t) {
         if (t.getGameMode() == GameMode.CREATIVE || t.getGameMode() == GameMode.SPECTATOR) {
            return false;
         }

         Clan tc = this.plugin.clans().of(t);
         Relation r = tc == null ? Relation.NEUTRAL : this.plugin.clans().relation(caster, tc);
         return r != Relation.SELF && r != Relation.ALLY;
      } else {
         return e instanceof Enemy
            || e.getPersistentDataContainer().has(this.bossKey)
            || e.getPersistentDataContainer().has(this.dungeonKey)
            || le instanceof org.bukkit.entity.Mob m && m.getTarget() == caster;
      }
   }

   private boolean friendly(Player caster, Player other) {
      if (other == caster) {
         return true;
      } else {
         Clan oc = this.plugin.clans().of(other);
         if (oc == null) {
            return false;
         } else {
            Relation r = this.plugin.clans().relation(caster, oc);
            return r == Relation.SELF || r == Relation.ALLY;
         }
      }
   }

   private boolean isBoss(Entity e) {
      return e.getPersistentDataContainer().has(this.bossKey) || e.getPersistentDataContainer().has(this.riftBossKey);
   }

   private static Location chest(LivingEntity t) {
      return t.getLocation().add(0.0, t.getHeight() * 0.6, 0.0);
   }

   /** Dégâts bruts : pas d'état posé, pas de réaction (zones, éclaboussures). */
   private void damage(Player caster, LivingEntity t, double dmg, int rgb, boolean big) {
      if (t instanceof Player) {
         dmg *= this.pvpMult;
      }

      t.setNoDamageTicks(0);
      t.damage(dmg, caster);
      this.fx.text(t.getLocation().add(0.0, t.getHeight() + 0.2, 0.0), trim(Math.round(dmg * 10.0) / 10.0), rgb, big ? 1.5F : 1.0F, big);
   }

   private void setState(LivingEntity t, String element) {
      if (element != null && !element.isEmpty() && this.stateMs > 0L && !t.isDead()) {
         this.states.put(t.getUniqueId(), new State(element, System.currentTimeMillis() + this.stateMs));
      }
   }

   private State stateOf(LivingEntity t) {
      State s = this.states.get(t.getUniqueId());
      if (s != null && System.currentTimeMillis() >= s.until()) {
         this.states.remove(t.getUniqueId());
         return null;
      }

      return s;
   }

   /** Un sort touche : état élémentaire, réaction éventuelle, dégâts, enchaînement. */
   private void hit(Cast ctx, LivingEntity t, double dmg) {
      Player p = ctx.p();
      String el = ctx.element();
      int rgb = EL_RGB.getOrDefault(el, 0xFFFFFF);
      Reaction r = null;
      State s = this.stateOf(t);
      if (s != null && el != null && !s.element().equals(el)) {
         r = this.reactions.get(s.element() + "+" + el);
      }

      double mult = 1.0;
      if (r != null) {
         this.states.remove(t.getUniqueId());
         mult = r.mult();
         if (this.isBoss(t)) {
            mult = 1.0 + (mult - 1.0) * this.bossReaction;
         }
      } else {
         this.setState(t, el);
      }

      this.damage(p, t, dmg * mult, rgb, r != null);
      if (r != null) {
         this.react(ctx, t, r, dmg);
      }

      if (this.comboMax > 0) {
         Combo cb = this.combos.computeIfAbsent(p.getUniqueId(), k -> new Combo());
         cb.until = System.currentTimeMillis() + this.comboMs;
         cb.last = ctx.sp().id();
      }
   }

   private void react(Cast ctx, LivingEntity t, Reaction r, double base) {
      Player p = ctx.p();
      World w = t.getWorld();
      String el = ctx.element();
      int rgb = EL_RGB.getOrDefault(el, 0xFFFFFF);
      Location c = chest(t);
      this.fx.text(t.getLocation().add(0.0, t.getHeight() + 0.75, 0.0), Text.strip(r.name()) + " !", rgb, 1.3F, true);
      w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
      switch (el) {
         case "givre" -> {
            w.playSound(c, Sound.BLOCK_GLASS_BREAK, 1.0F, 0.5F);
            w.spawnParticle(Particle.SNOWFLAKE, c, 40, 0.4, 0.5, 0.4, 0.12);
            this.fx.burst(c, 8, 1.4F, 0.16F, 9, Material.BLUE_ICE, Material.PACKED_ICE);
         }
         case "foudre" -> {
            w.playSound(c, Sound.ITEM_TRIDENT_THUNDER, 0.6F, 1.6F);
            w.spawnParticle(Particle.ELECTRIC_SPARK, c, 50, 0.5, 0.6, 0.5, 0.3);
            w.spawnParticle(Particle.FLASH, c, 1);
         }
         case "feu" -> {
            w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.8F, 1.4F);
            w.spawnParticle(Particle.FLAME, c, 50, 0.4, 0.5, 0.4, 0.1);
            w.spawnParticle(Particle.EXPLOSION, c, 2, 0.3, 0.3, 0.3, 0.0);
            this.fx.burst(c, 8, 1.6F, 0.16F, 9, Material.MAGMA_BLOCK, Material.BLACKSTONE);
         }
         default -> {
            w.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 1.4F);
            w.playSound(c, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7F, 1.6F);
            w.spawnParticle(Particle.WITCH, c, 40, 0.4, 0.5, 0.4, 0.1);
            this.fx.burst(c, 8, 1.4F, 0.14F, 9, Material.AMETHYST_BLOCK, Material.PURPLE_STAINED_GLASS);
         }
      }

      boolean boss = this.isBoss(t);
      if (r.radius() > 0.0 && r.splash() > 0.0) {
         Fx.ring(w, t.getLocation().add(0.0, 0.2, 0.0), r.radius(), 28, Particle.DUST, new Particle.DustOptions(Color.fromRGB(rgb), 1.2F));
         for (Entity e : w.getNearbyEntities(t.getLocation(), r.radius(), r.radius(), r.radius())) {
            if (e != t && this.validTarget(p, e) && e.getLocation().distanceSquared(t.getLocation()) <= r.radius() * r.radius()) {
               LivingEntity le = (LivingEntity)e;
               if (el.equals("foudre") || el.equals("arcane") && r.state().equals("foudre")) {
                  this.fx.bolt(c, chest(le), 0.04F, 4);
               }

               this.damage(p, le, base * r.splash(), rgb, false);
               this.setState(le, el);
               if (r.effect().equals("ignite")) {
                  le.setFireTicks(Math.max(le.getFireTicks(), 80));
               }
            }
         }
      }

      int half = t instanceof Player ? 2 : 1;
      switch (r.effect()) {
         case "stun" -> {
            if (!boss) {
               t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50 / half, 9, false, true, true));
               t.setVelocity(new Vector(0, 0, 0));
            }
         }
         case "weakness" -> t.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100 / half, 0, false, true, true));
         case "ignite" -> t.setFireTicks(Math.max(t.getFireTicks(), 100));
         case "knock" -> {
            if (!boss) {
               t.setVelocity(t.getVelocity().add(new Vector(0.0, 0.55, 0.0)));
            }
         }
         case "refund" -> {
            this.mana.put(p.getUniqueId(), Math.min(this.maxMana(p), this.mana(p) + this.refund));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5F, 0.7F);
         }
         default -> {
         }
      }
   }

   /** Toutes les 5 ticks : les cibles marquées par un élément le montrent. */
   private void tickStates() {
      if (this.states.isEmpty()) {
         return;
      }

      long now = System.currentTimeMillis();
      this.states.entrySet().removeIf(en -> {
         Entity e = now >= en.getValue().until() ? null : Bukkit.getEntity(en.getKey());
         if (!(e instanceof LivingEntity le) || le.isDead()) {
            return true;
         }

         World w = le.getWorld();
         Location c = chest(le);
         double hw = le.getWidth() * 0.5, hh = le.getHeight() * 0.35;
         switch (en.getValue().element()) {
            case "givre" -> w.spawnParticle(Particle.SNOWFLAKE, c, 3, hw, hh, hw, 0.01);
            case "foudre" -> w.spawnParticle(Particle.ELECTRIC_SPARK, c, 4, hw, hh, hw, 0.05);
            case "feu" -> w.spawnParticle(Particle.SMALL_FLAME, c, 3, hw, hh, hw, 0.01);
            case "ombre" -> w.spawnParticle(Particle.SQUID_INK, c, 2, hw, hh, hw, 0.01);
            default -> w.spawnParticle(Particle.DUST, c, 4, hw, hh, hw, 0.0, new Particle.DustOptions(Color.fromRGB(0xB06BFF), 0.9F));
         }

         return false;
      });
   }

   private String elementOf(Spell sp) {
      String e = sp.s("element", ELEMENT.getOrDefault(sp.id(), ""));
      return e == null ? "" : e.toLowerCase(Locale.ROOT);
   }

   private int circleOf(Spell sp) {
      if (sp.id().equals("egide")) {
         return Fx.CIRCLE_EGIDE;
      }

      return switch (this.elementOf(sp)) {
         case "givre" -> Fx.CIRCLE_GIVRE;
         case "foudre" -> Fx.CIRCLE_FOUDRE;
         case "feu" -> Fx.CIRCLE_FEU;
         case "ombre" -> Fx.CIRCLE_OMBRE;
         default -> Fx.CIRCLE_ARCANE;
      };
   }

   // ------------------------------------------------------------------ les 6 sorts

   private boolean trait(Cast ctx) {
      Player p = ctx.p();
      Spell sp = ctx.sp();
      double f = ctx.charge();
      double dmg = sp.d("damage", 6.0) * ctx.power() * (1.0 + sp.d("charge-damage", 1.0) * f);
      int range = sp.i("range", 30);
      int maxHits = ctx.full() ? Math.max(1, sp.i("charge-pierce", 5)) : 1;
      float size = (float)(0.3 + 0.3 * f);
      Location start = p.getEyeLocation();
      Vector dir = start.getDirection().normalize();
      World w = p.getWorld();
      Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(155, 77, 255), (float)(1.3 + f));
      w.playSound(start, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8F, 1.6F);
      w.playSound(start, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.5F, 1.8F);
      w.playSound(start, Sound.ENTITY_BREEZE_SHOOT, 0.7F, (float)(1.5 - 0.6 * f));
      BlockDisplay orb = this.fx.orb(start.clone().add(dir.clone().multiply(0.8)), Material.AMETHYST_BLOCK, size);
      new BukkitRunnable() {
         final Location pos = start.clone();
         final Set<UUID> done = new HashSet<>();
         double travelled = 0.0;
         int age = 0;

         void end(boolean wall) {
            if (wall) {
               w.spawnParticle(Particle.WITCH, this.pos, 12, 0.15, 0.15, 0.15, 0.05);
               w.playSound(this.pos, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.8F, 1.3F);
               MagicManager.this.fx.burst(this.pos, 5, 0.8F, 0.12F, 7, Material.AMETHYST_BLOCK);
            }

            MagicManager.this.fx.kill(orb);
            this.cancel();
         }

         public void run() {
            this.age++;
            for (int step = 0; step < 4; step++) {
               this.pos.add(dir.clone().multiply(0.5));
               this.travelled += 0.5;
               Block b = this.pos.getBlock();
               if (this.travelled > range || !p.isOnline()) {
                  this.end(false);
                  return;
               }

               if (b.getType().isSolid() && !b.isPassable()) {
                  this.end(true);
                  return;
               }

               w.spawnParticle(Particle.DUST, this.pos, 2, 0.03, 0.03, 0.03, 0.0, dust);
               if (step % 2 == 0) {
                  w.spawnParticle(Particle.WITCH, this.pos, 1, 0.0, 0.0, 0.0, 0.0);
               }

               double box = 0.6 + 0.3 * f;
               for (Entity e : w.getNearbyEntities(BoundingBox.of(this.pos, box, box, box))) {
                  if (!this.done.contains(e.getUniqueId()) && MagicManager.this.validTarget(p, e)) {
                     this.done.add(e.getUniqueId());
                     MagicManager.this.hit(ctx, (LivingEntity)e, dmg);
                     w.spawnParticle(Particle.WITCH, this.pos, 25, 0.3, 0.3, 0.3, 0.1);
                     w.spawnParticle(Particle.DUST, this.pos, 15, 0.3, 0.3, 0.3, 0.0, dust);
                     w.playSound(this.pos, Sound.ENTITY_ALLAY_HURT, 0.8F, 0.6F);
                     w.playSound(this.pos, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0F, 0.9F);
                     MagicManager.this.fx.burst(this.pos, 6, 1.0F, 0.13F, 8, Material.AMETHYST_BLOCK, Material.PURPLE_STAINED_GLASS);
                     if (this.done.size() >= maxHits) {
                        this.end(false);
                        return;
                     }
                  }
               }
            }

            MagicManager.this.fx.move(orb, this.pos, size, this.age * 0.6F);
         }
      }.runTaskTimer(this.plugin, 0L, 1L);
      return true;
   }

   private boolean nova(Cast ctx) {
      Player p = ctx.p();
      Spell sp = ctx.sp();
      double f = ctx.charge();
      double dmg = sp.d("damage", 4.0) * ctx.power() * (1.0 + sp.d("charge-damage", 0.5) * f);
      double r = sp.d("radius", 5.0) * (1.0 + sp.d("charge-radius", 0.6) * f);
      int slow = sp.i("slow-seconds", 3) * 20;
      int root = ctx.full() ? (int)Math.round(sp.d("charge-root-seconds", 1.5) * 20.0) : 0;
      Location c = p.getLocation();
      World w = p.getWorld();
      w.playSound(c, Sound.BLOCK_GLASS_BREAK, 1.0F, 0.6F);
      w.playSound(c, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.0F, 0.8F);
      w.playSound(c, Sound.BLOCK_POWDER_SNOW_BREAK, 1.0F, 0.6F);
      if (ctx.full()) {
         w.playSound(c, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.4F, 2.0F);
      }

      this.fx.spikes(c, r, 8 + (int)(r * 2.5), slow, Material.BLUE_ICE, Material.PACKED_ICE, Material.ICE);
      this.fx.decal(c, Fx.DECAL_GIVRE, (float)(r * 2.0), slow + 10, 0.0F);
      new BukkitRunnable() {
         int t = 0;

         public void run() {
            double rr = r * (this.t + 1) / 5.0;
            int n = (int)(8 + rr * 6);
            for (int i = 0; i < n; i++) {
               double a = Math.PI * 2 * i / n;
               Location l = c.clone().add(Math.cos(a) * rr, 0.3, Math.sin(a) * rr);
               w.spawnParticle(Particle.SNOWFLAKE, l, 1, 0.05, 0.1, 0.05, 0.0);
               if (i % 3 == 0) {
                  w.spawnParticle(Particle.CLOUD, l, 1, 0.0, 0.0, 0.0, 0.0);
               }
            }

            if (++this.t >= 5) {
               this.cancel();
            }
         }
      }.runTaskTimer(this.plugin, 0L, 1L);
      for (Entity e : w.getNearbyEntities(c, r, 3.0, r)) {
         if (this.validTarget(p, e) && e.getLocation().distanceSquared(c) <= r * r) {
            LivingEntity le = (LivingEntity)e;
            this.hit(ctx, le, dmg);
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 1, false, true, true));
            le.setFreezeTicks(Math.max(le.getFreezeTicks(), 100));
            if (root > 0 && !this.isBoss(le)) {
               le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, le instanceof Player ? root / 2 : root, 9, false, true, true));
            }
         }
      }

      return true;
   }

   private boolean chaine(Cast ctx) {
      Player p = ctx.p();
      Spell sp = ctx.sp();
      double f = ctx.charge();
      double dmg = sp.d("damage", 5.0) * ctx.power() * (1.0 + sp.d("charge-damage", 0.5) * f);
      int range = sp.i("range", 20);
      int targets = sp.i("targets", 4) + (int)Math.round(sp.d("charge-targets", 3.0) * f);
      double bounce = sp.d("bounce", 6.0);
      World w = p.getWorld();
      RayTraceResult rt = w.rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true, 0.8, e -> this.validTarget(p, e));
      if (rt == null || rt.getHitEntity() == null) {
         p.sendActionBar(Text.c("&7Aucune cible en vue."));
         p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5F, 0.6F);
         return false;
      } else {
         List<LivingEntity> chain = new ArrayList<>();
         chain.add((LivingEntity)rt.getHitEntity());
         while (chain.size() < targets) {
            LivingEntity last = chain.get(chain.size() - 1);
            LivingEntity best = null;
            double bd = bounce * bounce;
            for (Entity e : w.getNearbyEntities(last.getLocation(), bounce, bounce, bounce)) {
               if (!chain.contains(e) && this.validTarget(p, e)) {
                  double dd = e.getLocation().distanceSquared(last.getLocation());
                  if (dd <= bd) {
                     bd = dd;
                     best = (LivingEntity)e;
                  }
               }
            }

            if (best == null) {
               break;
            }

            chain.add(best);
         }

         float thick = (float)(0.05 + 0.04 * f);
         Location[] from = {this.hand(p)};
         int delay = 0;
         for (LivingEntity le : chain) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
               if (!le.isValid()) {
                  return;
               }

               Location b = chest(le);
               this.bolt(w, from[0], b);
               this.fx.bolt(from[0], b, thick, 4);
               from[0] = b;
               w.spawnParticle(Particle.ELECTRIC_SPARK, b, 20, 0.3, 0.4, 0.3, 0.2);
               w.spawnParticle(Particle.FLASH, b, 1);
               w.playSound(b, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.7F, 1.6F);
               this.hit(ctx, le, dmg);
            }, delay);
            delay += 3;
         }

         w.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, (float)(0.4 + 0.4 * f), 1.8F);
         w.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.5F, 1.8F);
         return true;
      }
   }

   private void bolt(World w, Location a, Location b) {
      Vector d = b.toVector().subtract(a.toVector());
      double len = d.length();
      int n = Math.max(2, (int)(len * 4));
      Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 240, 120), 0.9F);
      for (int i = 0; i <= n; i++) {
         Location l = a.clone().add(d.clone().multiply((double)i / n));
         double j = (i == 0 || i == n) ? 0.0 : 0.15;
         l.add((Math.random() - 0.5) * j, (Math.random() - 0.5) * j, (Math.random() - 0.5) * j);
         if (i % 2 == 0) {
            w.spawnParticle(Particle.DUST, l, 1, 0.0, 0.0, 0.0, 0.0, dust);
         }
      }
   }

   private boolean ombre(Cast ctx) {
      Player p = ctx.p();
      Spell sp = ctx.sp();
      double dist = sp.d("distance", 10.0);
      int inv = sp.i("invisible-seconds", 2) * 20;
      double mark = sp.d("mark-radius", 4.0);
      Location eye = p.getEyeLocation();
      Vector dir = eye.getDirection().normalize();
      if (dir.getY() < 0.0) {
         // regard vers le bas : on glisse à l'horizontale au lieu de viser dans le sol
         dir.setY(0.0);
         if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(-Math.sin(Math.toRadians(eye.getYaw())), 0.0, Math.cos(Math.toRadians(eye.getYaw())));
         }

         dir.normalize();
      }

      Location foot = p.getLocation();
      Location best = null;
      for (double t = dist; t >= 1.0; t -= 0.5) {
         Location c = foot.clone().add(dir.clone().multiply(t));
         // pas à travers les murs : ligne de vue depuis les yeux
         RayTraceResult rt = p.getWorld().rayTraceBlocks(eye, dir, t, FluidCollisionMode.NEVER, true);
         if (rt != null && rt.getHitPosition().distance(eye.toVector()) < t - 0.3) {
            continue;
         }

         if (!c.getBlock().isPassable() && c.getBlock().getRelative(0, 1, 0).isPassable()) {
            c.setY(c.getBlockY() + 1.0); // une marche
         }

         Block feet = c.getBlock();
         Block head = feet.getRelative(0, 1, 0);
         if (feet.isPassable() && head.isPassable() && !feet.isLiquid()) {
            best = c;
            break;
         }
      }

      if (best == null) {
         p.sendActionBar(Text.c("&7Pas de place pour te téléporter."));
         return false;
      } else {
         World w = p.getWorld();
         Location from = p.getLocation().add(0.0, 1.0, 0.0);
         w.spawnParticle(Particle.PORTAL, from, 60, 0.3, 0.6, 0.3, 0.6);
         w.spawnParticle(Particle.SMOKE, from, 25, 0.3, 0.6, 0.3, 0.02);
         this.fx.burst(from, 9, 1.2F, 0.16F, 9, Material.OBSIDIAN, Material.BLACK_STAINED_GLASS, Material.CRYING_OBSIDIAN);
         this.fx.decal(foot, Fx.CIRCLE_OMBRE, 2.0F, 14, -0.3F);
         best.setYaw(foot.getYaw());
         best.setPitch(foot.getPitch());
         float fall = p.getFallDistance();
         p.teleport(best);
         p.setFallDistance(Math.min(fall, 0.0F));
         p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, inv, 0, false, false, true));
         Location to = best.clone().add(0.0, 1.0, 0.0);
         // traînée d'ombre entre les deux points
         Vector line = to.toVector().subtract(from.toVector());
         int n = Math.max(2, (int)(line.length() * 2));
         for (int i = 0; i <= n; i++) {
            Location l = from.clone().add(line.clone().multiply((double)i / n));
            w.spawnParticle(Particle.SQUID_INK, l, 2, 0.15, 0.4, 0.15, 0.0);
            w.spawnParticle(Particle.DUST, l, 2, 0.15, 0.4, 0.15, 0.0, new Particle.DustOptions(Color.fromRGB(0x5A2D91), 1.2F));
         }

         w.spawnParticle(Particle.REVERSE_PORTAL, to, 60, 0.3, 0.6, 0.3, 0.05);
         this.fx.decal(best, Fx.CIRCLE_OMBRE, (float)(mark * 2.0), 16, 0.3F);
         w.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 0.8F);
         w.playSound(to, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8F, 1.0F);
         w.playSound(to, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0F, 1.6F);
         // les ennemis proches de l'arrivée sont marqués par l'ombre
         if (mark > 0.0) {
            String el = ctx.element();
            for (Entity e : w.getNearbyEntities(best, mark, mark, mark)) {
               if (this.validTarget(p, e) && e.getLocation().distanceSquared(best) <= mark * mark) {
                  LivingEntity le = (LivingEntity)e;
                  if (this.stateOf(le) == null) {
                     this.setState(le, el);
                     w.spawnParticle(Particle.SQUID_INK, chest(le), 10, 0.3, 0.4, 0.3, 0.02);
                  }
               }
            }
         }

         return true;
      }
   }

   private boolean egide(Cast ctx) {
      Player p = ctx.p();
      Spell sp = ctx.sp();
      int level = Math.max(1, sp.i("level", 3));
      int ticks = sp.i("seconds", 8) * 20;
      double r = sp.d("radius", 8.0);
      World w = p.getWorld();
      int n = 0;
      List<Player> shielded = new ArrayList<>();
      for (Player o : w.getPlayers()) {
         if (o.getLocation().distanceSquared(p.getLocation()) <= r * r && this.friendly(p, o)) {
            o.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, ticks, level - 1, false, false, true));
            Location l = o.getLocation().add(0.0, 1.0, 0.0);
            w.spawnParticle(Particle.END_ROD, l, 20, 0.5, 0.8, 0.5, 0.02);
            w.spawnParticle(Particle.DUST, l, 20, 0.5, 0.8, 0.5, 0.0, new Particle.DustOptions(Color.fromRGB(242, 193, 78), 1.2F));
            if (o != p) {
               o.sendActionBar(Text.c("&6⛨ &e" + p.getName() + " &7te protège avec l'&6Égide&7."));
            }

            shielded.add(o);
            n++;
         }
      }

      w.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.9F, 1.4F);
      w.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.7F, 1.2F);
      w.playSound(p.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6F, 1.6F);
      this.fx.decal(p.getLocation(), Fx.CIRCLE_EGIDE, (float)(r * 2.0), 16, 0.15F);
      // trois boucliers tournent autour de chaque joueur protégé tant que l'absorption tient
      for (Player o : shielded) {
         List<ItemDisplay> orbit = new ArrayList<>();
         for (int i = 0; i < 3; i++) {
            ItemDisplay d = this.fx.item(o.getLocation().add(0.0, 1.0, 0.0), new ItemStack(Material.SHIELD), 0.55F, ticks + 5);
            if (d != null) {
               orbit.add(d);
            }
         }

         if (orbit.isEmpty()) {
            continue;
         }

         new BukkitRunnable() {
            int t = 0;

            public void run() {
               this.t += 2;
               if (this.t >= ticks || !o.isOnline() || o.isDead() || this.t > 10 && o.getAbsorptionAmount() <= 0.0) {
                  if (o.isOnline() && this.t < ticks) {
                     o.getWorld().playSound(o.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.7F, 1.2F);
                  }

                  orbit.forEach(MagicManager.this.fx::kill);
                  this.cancel();
                  return;
               }

               for (int i = 0; i < orbit.size(); i++) {
                  double a = this.t * 0.14 + Math.PI * 2 * i / orbit.size();
                  Location l = o.getLocation().add(Math.cos(a) * 0.95, 1.0 + Math.sin(this.t * 0.1 + i) * 0.12, Math.sin(a) * 0.95);
                  l.setYaw((float)Math.toDegrees(a) + 90.0F);
                  l.setPitch(0.0F);
                  ItemDisplay d = orbit.get(i);
                  if (d.isValid()) {
                     d.teleport(l);
                  }
               }
            }
         }.runTaskTimer(this.plugin, 1L, 2L);
      }

      if (n > 1) {
         p.sendActionBar(Text.c("&6⛨ Égide &7sur &f" + n + " &7joueurs"));
      }

      return true;
   }

   private boolean meteore(Cast ctx) {
      Player p = ctx.p();
      Spell sp = ctx.sp();
      double f = ctx.charge();
      double dmg = sp.d("damage", 12.0) * ctx.power() * (1.0 + sp.d("charge-damage", 0.75) * f);
      int range = sp.i("range", 30);
      double r = sp.d("radius", 4.0) * (1.0 + sp.d("charge-radius", 0.5) * f);
      int delay = Math.max(10, sp.i("delay-ticks", 40));
      int fire = sp.i("fire-seconds", 4) * 20;
      int burn = ctx.full() ? (int)Math.round(sp.d("charge-burn-seconds", 4.0) * 20.0) : 0;
      double burnDmg = sp.d("charge-burn-damage", 2.0) * ctx.power();
      float size = (float)(1.0 + 0.8 * f);
      World w = p.getWorld();
      RayTraceResult rt = w.rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true);
      Location target;
      if (rt != null) {
         target = rt.getHitPosition().toLocation(w);
      } else {
         target = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(range));
         Block b = target.getBlock();
         int guard = 0;
         while (b.isPassable() && guard++ < 40 && b.getY() > w.getMinHeight()) {
            b = b.getRelative(0, -1, 0);
         }

         target = b.getLocation().add(0.5, 1.0, 0.5);
      }

      Location impact = target.clone();
      w.playSound(impact, Sound.ITEM_FIRECHARGE_USE, 1.0F, 0.6F);
      w.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.8F, 0.5F);
      w.playSound(impact, Sound.ENTITY_GHAST_WARN, 0.5F, 0.6F);
      this.fx.decal(impact, Fx.CIRCLE_FEU, (float)(r * 2.0), delay + 2, 0.12F);
      Location top = impact.clone().add(-18.0 * 0.35, 18.0, -18.0 * 0.35);
      BlockDisplay rock = this.fx.orb(top, Material.MAGMA_BLOCK, size);
      new BukkitRunnable() {
         int t = 0;

         public void run() {
            // cercle d'alerte
            if (this.t % 2 == 0) {
               Fx.ring(w, impact.clone().add(0.0, 0.15, 0.0), r, 32, Particle.DUST, new Particle.DustOptions(Color.fromRGB(255, 90, 20), 1.1F));
            }

            // le météore descend
            double h = 18.0 * (1.0 - (double)this.t / delay);
            Location m = impact.clone().add(-h * 0.35, h + size / 2.0, -h * 0.35);
            MagicManager.this.fx.move(rock, m, size, this.t * 0.35F);
            w.spawnParticle(Particle.FLAME, m, 12, 0.3, 0.3, 0.3, 0.02);
            w.spawnParticle(Particle.LAVA, m, 2, 0.2, 0.2, 0.2, 0.0);
            w.spawnParticle(Particle.LARGE_SMOKE, m, 3, 0.2, 0.2, 0.2, 0.01);
            if (++this.t >= delay) {
               this.cancel();
               MagicManager.this.fx.kill(rock);
               w.spawnParticle(Particle.EXPLOSION_EMITTER, impact, 1);
               w.spawnParticle(Particle.FLAME, impact, 120, r * 0.5, 0.4, r * 0.5, 0.12);
               w.spawnParticle(Particle.LAVA, impact, 25, r * 0.4, 0.3, r * 0.4, 0.0);
               w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, impact, 12, r * 0.3, 0.2, r * 0.3, 0.03);
               w.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.4F, 0.7F);
               w.playSound(impact, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.0F, 0.6F);
               w.playSound(impact, Sound.BLOCK_ANVIL_LAND, 0.5F, 0.5F);
               MagicManager.this.fx.burst(impact.clone().add(0.0, 0.3, 0.0), 16, (float)(r * 1.1), 0.34F, 14,
                  Material.MAGMA_BLOCK, Material.BLACKSTONE, Material.NETHERRACK);
               MagicManager.this.fx.decal(impact, Fx.DECAL_CRATERE, (float)(r * 2.0), 120, 0.0F);
               if (!p.isOnline()) {
                  return;
               }

               for (Entity e : w.getNearbyEntities(impact, r, r, r)) {
                  if (MagicManager.this.validTarget(p, e) && e.getLocation().distanceSquared(impact) <= r * r) {
                     LivingEntity le = (LivingEntity)e;
                     MagicManager.this.hit(ctx, le, dmg);
                     le.setFireTicks(Math.max(le.getFireTicks(), fire));
                     Vector kb = le.getLocation().toVector().subtract(impact.toVector()).setY(0.0);
                     if (kb.lengthSquared() > 0.01 && !MagicManager.this.isBoss(le)) {
                        le.setVelocity(le.getVelocity().add(kb.normalize().multiply(0.6).setY(0.4)));
                     }
                  }
               }

               if (burn > 0) {
                  MagicManager.this.burning(p, impact, r, burn, burnDmg);
               }
            }
         }
      }.runTaskTimer(this.plugin, 0L, 1L);
      return true;
   }

   /** Météore à pleine charge : le sol reste en feu. */
   private void burning(Player p, Location c, double r, int ticks, double dmg) {
      World w = c.getWorld();
      new BukkitRunnable() {
         int t = 0;

         public void run() {
            this.t += 5;
            if (this.t > ticks || !p.isOnline()) {
               this.cancel();
               return;
            }

            w.spawnParticle(Particle.FLAME, c.clone().add(0.0, 0.2, 0.0), (int)(r * r * 1.5), r * 0.5, 0.1, r * 0.5, 0.01);
            w.spawnParticle(Particle.SMOKE, c.clone().add(0.0, 0.4, 0.0), (int)(r * 2), r * 0.5, 0.2, r * 0.5, 0.01);
            if (this.t % 10 == 0) {
               w.playSound(c, Sound.BLOCK_FIRE_AMBIENT, 1.0F, 0.8F);
               for (Entity e : w.getNearbyEntities(c, r, 2.0, r)) {
                  if (MagicManager.this.validTarget(p, e) && e.getLocation().distanceSquared(c) <= r * r) {
                     LivingEntity le = (LivingEntity)e;
                     MagicManager.this.damage(p, le, dmg / 2.0, 0xFF8A2A, false);
                     le.setFireTicks(Math.max(le.getFireTicks(), 40));
                  }
               }
            }
         }
      }.runTaskTimer(this.plugin, 5L, 5L);
   }

   // ------------------------------------------------------------------ HUD

   private Component hud(Player p) {
      Data d = this.data(p.getUniqueId());
      String sel = this.selected(p);
      double max = this.maxMana(p);
      double cur = Math.min(max, this.mana(p));
      if (!this.plugin.pack().has(p)) {
         StringBuilder sb = new StringBuilder();
         for (int i = 0; i < d.slots.length; i++) {
            String s = d.slots[i];
            boolean on = i == d.selected && s != null;
            if (s == null) {
               sb.append("&8[ - ] ");
               continue;
            }

            long left = this.cooldownLeft(p, s);
            sb.append(on ? "&6[" : "&8[").append(on ? "&e" : "&7").append(SYMBOL.getOrDefault(s, "✦")).append(" ")
               .append(Text.strip(this.spells.get(s).name()));
            if (left > 0L) {
               sb.append(" &c").append((left + 999L) / 1000L).append("s");
            }

            sb.append(on ? "&6] " : "&8] ");
         }

         sb.append("&8| &dMana &f").append((int)cur).append("&7/").append((int)max);
         int cl = this.comboShown(p);
         if (cl > 0) {
            sb.append(" &8| &6Enchaînement x").append(cl);
         }

         return Text.c(sb.toString());
      }

      Component c = Component.empty().color(NamedTextColor.WHITE);
      for (int i = 0; i < d.slots.length; i++) {
         String s = d.slots[i];
         boolean on = i == d.selected && s != null;
         int glyph = s == null ? 0xe50c + (on ? 1 : 0) : 0xe500 + ORDER.indexOf(s) * 2 + (on ? 1 : 0);
         c = c.append(Component.text(String.valueOf((char)glyph)).font(FONT).color(NamedTextColor.WHITE));
         if (s != null) {
            long left = this.cooldownLeft(p, s);
            if (left > 0L) {
               int k = (int)Math.ceil(8.0 * left / this.cooldownTotal(p, s));
               k = Math.max(1, Math.min(8, k));
               c = c.append(Glyphs.shift(-21)).append(Component.text(String.valueOf((char)(0xe50f + k))).font(FONT).color(NamedTextColor.WHITE));
            } else if (this.mana(p) < this.spells.get(s).mana()) {
               c = c.append(Glyphs.shift(-21)).append(Component.text(String.valueOf((char)0xe517)).font(FONT).color(NamedTextColor.WHITE));
            }
         }

         c = c.append(Glyphs.shift(1));
      }

      int lvl = (int)Math.round(20.0 * cur / Math.max(1.0, max));
      lvl = Math.max(0, Math.min(20, lvl));
      c = c.append(Glyphs.shift(3)).append(Component.text(String.valueOf((char)(0xe520 + lvl))).font(FONT).color(NamedTextColor.WHITE));
      c = c.append(Glyphs.shift(3)).append(Component.text((int)cur + "", TextColor.color(0xDAA6FF)));
      if (sel != null) {
         Spell sp = this.spells.get(sel);
         long left = this.cooldownLeft(p, sel);
         c = c.append(Component.text("  ")).append(Text.c(sp.name()));
         if (left > 0L) {
            c = c.append(Component.text(" " + (left + 999L) / 1000L + "s", NamedTextColor.GRAY));
         }
      }

      int cl = this.comboShown(p);
      if (cl > 0) {
         c = c.append(Component.text("  ✦x" + cl, TextColor.color(0xFFC83C)));
      }

      return c;
   }

   private int comboShown(Player p) {
      Combo cb = this.combos.get(p.getUniqueId());
      return cb == null || System.currentTimeMillis() >= cb.until ? 0 : Math.max(1, Math.min(this.comboMax, cb.level + 1));
   }

   // ------------------------------------------------------------------ menu Grimoire

   public void menu(Player p) {
      Data d = this.data(p.getUniqueId());
      Menu m = new Menu(p, "list", 6, "&5Grimoire");
      double max = this.maxMana(p);
      List<String> info = new ArrayList<>();
      info.add("&7Sorts appris : &d" + d.learned.size() + "&7/" + this.spells.size());
      info.add("&7Mana max : &d" + (int)max + (this.catalyst(p) == null ? " &8(sans bâton)" : ""));
      if (this.isArcanien(p)) {
         info.add("&dArcanien &7: mana et recharges bonus");
      }

      info.add("");
      info.add("&fBâton en main :");
      info.add("&7• &fclic droit &7: lancer le sort");
      info.add("&7• &fclic maintenu &7: charger (Trait, Nova, Chaîne, Météore)");
      info.add("&7• &frelâcher au tintement &7: charge parfaite");
      info.add("&7• &faccroupi + clic droit &7: sort suivant");
      info.add("&7• &fenchaîner des sorts différents &7: +dégâts, -mana");
      info.add("");
      info.add("&8Grimoires : butin des donjons.");
      info.add("&8Bâton d'apprenti : chaudron.");
      m.set(4, ItemBuilder.of(Material.ENCHANTED_BOOK).name("&5✦ Grimoire").lore(info).glow(true).build());
      List<String> rl = new ArrayList<>();
      rl.add("&7Chaque sort pose son élément sur la cible");
      rl.add("&7pendant " + trim(this.stateMs / 1000.0) + " s. Un sort d'un autre élément");
      rl.add("&7le fait réagir.");
      rl.add("");
      for (Reaction r : this.reactions.values()) {
         rl.add(EL_STATE.getOrDefault(r.state(), r.state()) + " &8+ " + EL_LABEL.getOrDefault(r.trigger(), r.trigger()) + " &8» " + r.name()
            + " &8x" + trim(r.mult()));
      }

      if (!this.reactions.isEmpty()) {
         m.set(6, ItemBuilder.of(Material.BREWING_STAND).name("&d⚗ Réactions élémentaires").lore(rl).build());
      }

      if (d.innate) {
         m.set(2, ItemBuilder.of(Material.NETHER_STAR).name("&5✦ Incantation innée " + (d.innateOff ? "&c(désactivée)" : "&a(active)")).lore(
            "&7Sans catalyseur, avec n'importe quel", "&7objet en main :", "&7• &fF &7: lancer le sort", "&7• &faccroupi + clic gauche dans le vide",
            "&7  : sort suivant", "&8Pas de charge sans catalyseur.", "", d.innateOff ? "&eClic : activer" : "&eClic : désactiver &8(F échange à nouveau les mains)").glow(!d.innateOff).build(), e -> {
            d.innateOff = !d.innateOff;
            this.dirty = true;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5F, 1.2F);
            this.menu(p);
         });
      } else {
         m.set(2, ItemBuilder.of(Material.AMETHYST_SHARD).name("&d✦ Fragments d'éveil &f" + d.fragments + "&7/&f" + this.fragmentsNeeded).lore(
            "&7Chaque rituel réussi dans la", "&5Faille arcanique &7donne un fragment.", "&7Avec " + this.fragmentsNeeded + " fragments : lancer ses sorts",
            "&7sans catalyseur, pour toujours.", "", "&8La faille s'ouvre environ une fois", "&8par jour : /faille").build());
      }

      int[] slotPos = new int[]{11, 13, 15, 12, 14, 10};
      for (int i = 0; i < d.slots.length && i < slotPos.length; i++) {
         final int idx = i;
         String s = d.slots[i];
         if (s == null) {
            m.set(slotPos[i], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
               .name("&7Emplacement " + (i + 1) + " &8(vide)")
               .lore("&8Clique sur un sort appris", "&8plus bas pour l'équiper.")
               .build());
         } else {
            Spell sp = this.spells.get(s);
            List<String> lore = new ArrayList<>(this.spellLore(p, sp));
            lore.add("");
            lore.add(i == d.selected ? "&6▶ Sort sélectionné" : "&7Emplacement " + (i + 1));
            lore.add("&eClic : retirer");
            m.set(slotPos[i], ItemBuilder.of(ICON.getOrDefault(s, Material.BOOK)).name(sp.name()).lore(lore).glow(i == d.selected).build(), e -> {
               d.slots[idx] = null;
               this.dirty = true;
               p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5F, 1.0F);
               this.menu(p);
            });
         }
      }

      int[] pos = new int[]{28, 29, 30, 32, 33, 34};
      int j = 0;
      for (Spell sp : this.spells.values()) {
         if (j >= pos.length) {
            break;
         }

         boolean known = d.learned.contains(sp.id());
         boolean equipped = false;
         for (String s : d.slots) {
            if (sp.id().equals(s)) {
               equipped = true;
            }
         }

         List<String> lore = new ArrayList<>(this.spellLore(p, sp));
         lore.add("");
         if (!known) {
            lore.add("&8Non appris");
            lore.add("&8Grimoire : " + Text.strip(this.plugin.items().displayName("windy:" + sp.grimoire())));
            m.set(pos[j++], ItemBuilder.of(Material.GRAY_DYE).name("&8" + Text.strip(sp.name())).lore(lore).build());
            continue;
         }

         lore.add(equipped ? "&aÉquipé" : "&eClic : équiper");
         final boolean eq = equipped;
         m.set(pos[j++], ItemBuilder.of(ICON.getOrDefault(sp.id(), Material.BOOK)).name(sp.name()).lore(lore).glow(equipped).build(), e -> {
            if (!eq) {
               this.equip(d, sp.id());
               p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.6F, 1.4F);
               this.menu(p);
            }
         });
      }

      m.set(49, ItemBuilder.of(Material.ARROW).name("&7Retour").build(), e -> this.plugin.menus().profile(p));
      m.fillBorder();
      m.open(p);
   }

   private void equip(Data d, String spell) {
      for (int i = 0; i < d.slots.length; i++) {
         if (d.slots[i] == null) {
            d.slots[i] = spell;
            this.dirty = true;
            return;
         }
      }

      d.slots[d.selected] = spell;
      this.dirty = true;
   }

   private List<String> spellLore(Player p, Spell sp) {
      List<String> l = new ArrayList<>(sp.description());
      l.add("");
      l.add("&7Mana : &d" + sp.mana() + " &8• &7Recharge : &f" + sp.cooldown() + " s");
      if (sp.cfg().contains("damage")) {
         l.add("&7Dégâts : &c" + trim(sp.d("damage", 0.0)) + " &8(moitié contre les joueurs)");
      }

      String el = this.elementOf(sp);
      if (EL_LABEL.containsKey(el)) {
         l.add("&7Élément : " + EL_LABEL.get(el) + " &8(pose : " + Text.strip(EL_STATE.get(el)) + ")");
      }

      if (this.chargeEnabled && sp.d("charge-seconds", 0.0) > 0.0) {
         l.add("&eClic maintenu " + trim(sp.d("charge-seconds", 0.0)) + " s &7: " + sp.s("charged", "sort plus puissant"));
      }

      return l;
   }

   private static String trim(double v) {
      return v == Math.floor(v) ? String.valueOf((int)v) : String.valueOf(v);
   }

   // ------------------------------------------------------------------ commandes

   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (s instanceof Player p) {
         this.menu(p);
      }

      return true;
   }

   /** /clanadmin magie <joueur> <apprendre|oublier|mana|info> [sort|tout] */
   public void admin(CommandSender s, String[] args) {
      if (args.length < 3) {
         this.plugin.msg(s, "&c/clanadmin magie <joueur> <apprendre|oublier> <sort|tout> &7| &c/clanadmin magie <joueur> <mana|info>");
         this.plugin.msg(s, "&7Sorts : &f" + String.join(", ", this.spells.keySet()));
         return;
      }

      Player t = Bukkit.getPlayerExact(args[1]);
      if (t == null) {
         this.plugin.msg(s, "&cJoueur introuvable (il doit être connecté).");
         return;
      }

      String act = args[2].toLowerCase(Locale.ROOT);
      String what = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "tout";
      switch (act) {
         case "apprendre", "learn" -> {
            if (what.equals("tout")) {
               this.spells.keySet().forEach(sp -> this.learn(t.getUniqueId(), sp));
            } else if (!this.spells.containsKey(what)) {
               this.plugin.msg(s, "&cSort inconnu. &7" + String.join(", ", this.spells.keySet()));
               return;
            } else {
               this.learn(t.getUniqueId(), what);
            }

            this.plugin.msg(s, "&a" + t.getName() + " connaît maintenant : " + String.join(", ", this.data(t.getUniqueId()).learned));
         }
         case "oublier", "forget" -> {
            this.forget(t.getUniqueId(), what.equals("tout") ? null : what);
            this.plugin.msg(s, "&aSort(s) oublié(s) pour " + t.getName() + ".");
         }
         case "fragment", "fragments" -> {
            Data d = this.data(t.getUniqueId());
            if (args.length > 3 && args[3].matches("\\d+")) {
               d.fragments = Integer.parseInt(args[3]);
               d.innate = d.fragments >= this.fragmentsNeeded;
               this.dirty = true;
            } else {
               this.grantFragment(t);
            }

            this.plugin.msg(s, "&a" + t.getName() + " : " + d.fragments + "/" + this.fragmentsNeeded + " fragment(s), incantation innée : " + (d.innate ? "oui" : "non"));
         }
         case "mana" -> {
            this.mana.put(t.getUniqueId(), this.maxMana(t));
            this.ready.remove(t.getUniqueId());
            this.plugin.msg(s, "&aMana rempli et recharges remises à zéro pour " + t.getName() + ".");
         }
         default -> {
            Data d = this.data(t.getUniqueId());
            this.plugin.msg(s, "&d" + t.getName() + " &7: appris " + d.learned + " &8• &7emplacements "
               + java.util.Arrays.toString(d.slots) + " &8• &7mana " + (int)this.mana(t) + "/" + (int)this.maxMana(t));
         }
      }
   }

   public List<String> spellIds() {
      return new ArrayList<>(this.spells.keySet());
   }
}
