package fr.windydev.clans.hub;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

/** Roue de la fortune : un tour gratuit par jour, avec une série de jours consécutifs. */
public class WheelManager implements Listener, CommandExecutor {
   /** les 16 cases de la roue, dans le sens des aiguilles d'une montre (menu de 6 lignes) */
   private static final int[] RING = {2, 3, 4, 5, 6, 15, 24, 33, 42, 41, 40, 39, 38, 29, 20, 11};
   private static final int CENTER = 22;
   private static final List<String> RARITIES = List.of("commun", "rare", "epique", "legendaire");

   private final WindyClans plugin;
   private final Random rnd = new Random();
   private final List<Segment> segments = new ArrayList<>();
   private final Map<UUID, long[]> data = new HashMap<>(); // {dernier jour, série}
   private final Map<UUID, Segment> pending = new HashMap<>(); // tour en cours : lot pas encore remis
   private final Set<UUID> spinning = new HashSet<>();
   private File dataFile;
   private boolean dirty;
   private boolean enabled = true;
   private int resetHour = 4;
   private int bonusEvery = 7;
   private boolean reminder = true;
   private int broadcastFrom = 2;

   record Segment(String name, String icon, double weight, int rarity, List<String> items, long xp, long souls) {
   }

   public WheelManager(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "roue.yml");
      if (!f.exists()) {
         this.plugin.saveResource("roue.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      this.enabled = y.getBoolean("settings.enabled", true);
      this.resetHour = Math.max(0, Math.min(23, y.getInt("settings.reset-hour", 4)));
      this.bonusEvery = Math.max(0, y.getInt("settings.streak-bonus-every", 7));
      this.reminder = y.getBoolean("settings.join-reminder", true);
      int bf = RARITIES.indexOf(y.getString("settings.broadcast-from", "epique").toLowerCase(Locale.ROOT));
      this.broadcastFrom = bf < 0 ? 99 : bf;
      this.segments.clear();
      for (Map<?, ?> m : y.getMapList("segments")) {
         List<String> items = new ArrayList<>();
         if (m.get("items") instanceof List<?> l) {
            l.forEach(o -> items.add(String.valueOf(o)));
         }

         double w = m.get("weight") instanceof Number n ? n.doubleValue() : 1.0;
         if (w > 0.0) {
            this.segments.add(new Segment(String.valueOf(m.get("name")), String.valueOf(m.get("icon")), w,
               Math.max(0, RARITIES.indexOf(String.valueOf(m.get("rarity")).toLowerCase(Locale.ROOT))), items,
               m.get("xp") instanceof Number n ? n.longValue() : 0L, m.get("souls") instanceof Number n ? n.longValue() : 0L));
         }
      }

      if (this.dataFile == null) {
         this.dataFile = new File(this.plugin.getDataFolder(), "roue-data.yml");
         YamlConfiguration d = YamlConfiguration.loadConfiguration(this.dataFile);
         ConfigurationSection ps = d.getConfigurationSection("players");
         if (ps != null) {
            for (String k : ps.getKeys(false)) {
               try {
                  this.data.put(UUID.fromString(k), new long[]{ps.getLong(k + ".day"), ps.getLong(k + ".streak")});
               } catch (IllegalArgumentException ignored) {
               }
            }
         }
      }
   }

   public void save() {
      if (this.dataFile == null) {
         return;
      }

      YamlConfiguration y = new YamlConfiguration();
      for (Map.Entry<UUID, long[]> e : this.data.entrySet()) {
         y.set("players." + e.getKey() + ".day", e.getValue()[0]);
         y.set("players." + e.getKey() + ".streak", e.getValue()[1]);
      }

      try {
         y.save(this.dataFile);
         this.dirty = false;
      } catch (IOException ex) {
         this.plugin.getLogger().warning("roue-data.yml : sauvegarde impossible : " + ex.getMessage());
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         if (this.dirty) {
            this.save();
         }
      }, 1200L, 1200L);
   }

   public void shutdown() {
      for (UUID u : new ArrayList<>(this.pending.keySet())) {
         Player p = Bukkit.getPlayer(u);
         if (p != null) {
            this.deliver(p, false);
         }
      }

      if (this.dirty) {
         this.save();
      }
   }

   // ------------------------------------------------------------------ jours et série

   /** Numéro du jour de jeu (la journée commence à reset-hour). */
   private long today() {
      return LocalDateTime.now().minusHours(this.resetHour).toLocalDate().toEpochDay();
   }

   public boolean available(UUID u) {
      long[] d = this.data.get(u);
      return this.enabled && (d == null || d[0] < this.today());
   }

   public long streak(UUID u) {
      long[] d = this.data.get(u);
      if (d == null) {
         return 0L;
      }

      // la série tient si le dernier tour date d'aujourd'hui ou d'hier
      return d[0] >= this.today() - 1L ? d[1] : 0L;
   }

   private String untilReset() {
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime next = now.toLocalDate().atTime(this.resetHour, 0);
      if (!next.isAfter(now)) {
         next = next.plusDays(1);
      }

      return Text.formatTime(java.time.Duration.between(now, next).getSeconds());
   }

   // ------------------------------------------------------------------ menu

   private ItemStack icon(Segment s, boolean lit) {
      ItemStack base = null;
      if (s.icon().toLowerCase(Locale.ROOT).startsWith("windy:")) {
         base = this.plugin.items().create(s.icon().substring(6));
      }

      Material m = base == null ? Material.matchMaterial(s.icon()) : base.getType();
      ItemBuilder b = ItemBuilder.of(m == null ? Material.CHEST : m).name(s.name()).lore("&8" + rarityName(s.rarity())).glow(lit);
      ItemStack it = b.build();
      if (base != null && base.hasItemMeta() && base.getItemMeta().hasCustomModelData()) {
         var meta = it.getItemMeta();
         meta.setCustomModelData(base.getItemMeta().getCustomModelData());
         it.setItemMeta(meta);
      }

      return it;
   }

   private static String rarityName(int r) {
      return switch (r) {
         case 1 -> "Rare";
         case 2 -> "Épique";
         case 3 -> "Légendaire";
         default -> "Commun";
      };
   }

   private static Material pane(int r) {
      return switch (r) {
         case 1 -> Material.BLUE_STAINED_GLASS_PANE;
         case 2 -> Material.PURPLE_STAINED_GLASS_PANE;
         case 3 -> Material.ORANGE_STAINED_GLASS_PANE;
         default -> Material.WHITE_STAINED_GLASS_PANE;
      };
   }

   private Segment at(int ringIndex) {
      return this.segments.get(ringIndex % this.segments.size());
   }

   public void open(Player p) {
      if (!this.enabled || this.segments.isEmpty()) {
         p.sendMessage(Text.c("&7La roue de la fortune est fermée."));
         return;
      }

      if (this.spinning.contains(p.getUniqueId())) {
         return;
      }

      UUID u = p.getUniqueId();
      Menu m = new Menu(p, "list", 6, "&6Roue de la fortune");
      for (int i = 0; i < RING.length; i++) {
         m.set(RING[i], this.icon(this.at(i), false));
      }

      long streak = this.streak(u);
      boolean can = this.available(u);
      boolean bonus = can && this.bonusEvery > 0 && (streak + 1) % this.bonusEvery == 0;
      List<String> lore = new ArrayList<>();
      lore.add("&7Un tour gratuit par jour.");
      lore.add("&7Série : &e" + streak + " jour" + (streak > 1 ? "s" : ""));
      if (this.bonusEvery > 0) {
         long left = this.bonusEvery - (streak + (can ? 1 : 0)) % this.bonusEvery;
         lore.add(bonus ? "&6★ Tour bonus : lot rare ou mieux garanti !"
            : "&8Tour bonus dans " + (left == this.bonusEvery && !can ? this.bonusEvery : left) + " jour(s) de série");
      }

      lore.add("");
      lore.add(can ? "&a▶ Clique pour lancer la roue" : "&cDéjà tournée aujourd'hui &8(retour dans " + this.untilReset() + ")");
      m.set(CENTER, ItemBuilder.of(can ? Material.NETHER_STAR : Material.CLOCK).name(can ? "&6&lLancer la roue" : "&7Reviens demain").lore(lore).glow(can).build(), e -> {
         if (this.available(u) && !this.spinning.contains(u)) {
            this.spin(p, bonus);
         }
      });
      List<String> odds = new ArrayList<>();
      double total = this.segments.stream().mapToDouble(Segment::weight).sum();
      double[] by = new double[4];
      this.segments.forEach(s -> by[s.rarity()] += s.weight());
      for (int r = 0; r < 4; r++) {
         if (by[r] > 0.0) {
            odds.add("&7" + rarityName(r) + " : &f" + Math.round(1000.0 * by[r] / total) / 10.0 + " %");
         }
      }

      m.set(49, ItemBuilder.of(Material.BOOK).name("&eChances").lore(odds).build());
      m.fillBorder();
      m.open(p);
   }

   private Segment roll(boolean bonus) {
      List<Segment> pool = new ArrayList<>();
      for (Segment s : this.segments) {
         if (!bonus || s.rarity() >= 1) {
            pool.add(s);
         }
      }

      if (pool.isEmpty()) {
         pool = this.segments;
      }

      double total = pool.stream().mapToDouble(Segment::weight).sum();
      double r = this.rnd.nextDouble() * total;
      for (Segment s : pool) {
         r -= s.weight();
         if (r <= 0.0) {
            return s;
         }
      }

      return pool.get(pool.size() - 1);
   }

   private void spin(Player p, boolean bonus) {
      UUID u = p.getUniqueId();
      Segment prize = this.roll(bonus);
      // le tour est compté et le lot fixé tout de suite : fermer le menu ne change rien
      long streak = this.streak(u) + 1L;
      this.data.put(u, new long[]{this.today(), streak});
      this.dirty = true;
      this.pending.put(u, prize);
      this.spinning.add(u);
      List<Integer> spots = new ArrayList<>();
      for (int i = 0; i < RING.length; i++) {
         if (this.at(i) == prize) {
            spots.add(i);
         }
      }

      int target = spots.isEmpty() ? 0 : spots.get(this.rnd.nextInt(spots.size()));
      int steps = RING.length * 3 + target; // trois tours complets puis la case gagnante
      Inventory inv = p.getOpenInventory().getTopInventory();
      inv.setItem(CENTER, ItemBuilder.of(Material.CLOCK).name("&e...").build());
      new BukkitRunnable() {
         int step = 0;
         int wait = 0;

         public void run() {
            if (!p.isOnline() || !WheelManager.this.pending.containsKey(u)) {
               this.cancel();
               return;
            }

            if (this.wait-- > 0) {
               return;
            }

            int pos = this.step % RING.length;
            int prev = (pos + RING.length - 1) % RING.length;
            boolean open = p.getOpenInventory().getTopInventory() == inv;
            if (open) {
               inv.setItem(RING[prev], WheelManager.this.icon(WheelManager.this.at(prev), false));
               inv.setItem(RING[pos], WheelManager.this.icon(WheelManager.this.at(pos), true));
               inv.setItem(CENTER, ItemBuilder.of(pane(WheelManager.this.at(pos).rarity())).name(WheelManager.this.at(pos).name()).build());
               p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6F, 1.2F + 0.6F * pos / RING.length);
            }

            if (this.step >= steps) {
               this.cancel();
               Bukkit.getScheduler().runTaskLater(WheelManager.this.plugin, () -> WheelManager.this.deliver(p, true), 12L);
               return;
            }

            this.step++;
            int left = steps - this.step;
            // ralentit sur la fin
            this.wait = left > 20 ? 0 : (left > 10 ? 1 : (left > 5 ? 3 : (left > 2 ? 5 : 8)));
         }
      }.runTaskTimer(this.plugin, 1L, 1L);
   }

   /** Remet le lot du tour en cours (fin de l'animation, menu fermé, déconnexion). */
   private void deliver(Player p, boolean show) {
      UUID u = p.getUniqueId();
      Segment s = this.pending.remove(u);
      this.spinning.remove(u);
      if (s == null) {
         return;
      }

      for (String spec : s.items()) {
         ItemStack it = this.item(spec);
         if (it != null) {
            for (ItemStack rest : p.getInventory().addItem(it).values()) {
               p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
         }
      }

      if (s.xp() > 0L) {
         this.plugin.profiles().addXp(p, s.xp(), "temps");
      }

      if (s.souls() > 0L) {
         this.plugin.profiles().addSouls(p, s.souls(), true);
      }

      long streak = this.streak(u);
      p.sendMessage(Text.c("&6✪ Roue de la fortune &7: tu gagnes " + s.name() + " &7! &8(série : " + streak + " jour" + (streak > 1 ? "s" : "") + ")"));
      if (show) {
         p.showTitle(Title.title(Text.c(s.name()), Text.c("&7" + rarityName(s.rarity()))));
         p.playSound(p.getLocation(), s.rarity() >= 2 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.9F, 1.0F);
         p.getWorld().spawnParticle(s.rarity() >= 2 ? Particle.TOTEM_OF_UNDYING : Particle.HAPPY_VILLAGER, p.getLocation().add(0.0, 1.2, 0.0), s.rarity() >= 2 ? 60 : 20, 0.5, 0.6, 0.5, 0.3);
         if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
               if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() instanceof Menu && !this.spinning.contains(u)) {
                  this.open(p);
               }
            }, 30L);
         }
      }

      if (s.rarity() >= this.broadcastFrom) {
         Bukkit.broadcast(Text.c("&6✪ &f" + p.getName() + " &7a gagné " + s.name() + " &7à la &6roue de la fortune &7! &8(/roue)"));
      }
   }

   private ItemStack item(String spec) {
      String[] p = spec.trim().split(":");
      try {
         if (p[0].equalsIgnoreCase("windy") && p.length >= 2) {
            ItemStack it = this.plugin.items().create(p[1]);
            if (it != null && p.length >= 3) {
               it.setAmount(Math.max(1, Math.min(64, Integer.parseInt(p[2]))));
            }

            return it;
         }

         Material m = Material.matchMaterial(p[0]);
         return m == null ? null : new ItemStack(m, p.length >= 2 ? Math.max(1, Math.min(64, Integer.parseInt(p[1]))) : 1);
      } catch (NumberFormatException e) {
         return null;
      }
   }

   // ------------------------------------------------------------------ évènements, commandes

   @EventHandler
   public void onClose(InventoryCloseEvent e) {
      if (e.getPlayer() instanceof Player p && this.pending.containsKey(p.getUniqueId())) {
         // menu fermé pendant que la roue tourne : le lot est remis quand même
         Bukkit.getScheduler().runTask(this.plugin, () -> this.deliver(p, true));
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.deliver(e.getPlayer(), false);
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      if (this.reminder) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline() && this.available(p.getUniqueId())) {
               Component c = Text.c("&6✪ &eTa roue de la fortune du jour est disponible ! ").append(Text.c("&6&l[TOURNER]").clickEvent(ClickEvent.runCommand("/roue")));
               p.sendMessage(c);
            }
         }, 100L);
      }
   }

   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (s instanceof Player p) {
         this.open(p);
      }

      return true;
   }

   /** /clanadmin roue <joueur> reset */
   public void admin(CommandSender s, String[] args) {
      Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
      if (t == null) {
         this.plugin.msg(s, "&c/clanadmin roue <joueur connecté> reset");
         return;
      }

      long[] d = this.data.get(t.getUniqueId());
      if (d != null) {
         d[0] = this.today() - 1L;
         this.dirty = true;
      }

      this.plugin.msg(s, "&aTour du jour rendu à " + t.getName() + " &7(série : " + this.streak(t.getUniqueId()) + ").");
   }
}
