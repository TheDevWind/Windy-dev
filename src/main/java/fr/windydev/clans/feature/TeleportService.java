package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

public class TeleportService implements Listener, CommandExecutor, TabCompleter {
   private final WindyClans plugin;
   private final File file;
   private final YamlConfiguration homes;
   private final Map<UUID, BukkitTask> pending = new HashMap<>();
   private final Map<UUID, LinkedHashMap<UUID, Long>> requests = new HashMap<>();
   private final Map<UUID, Long> lastPvp = new HashMap<>();
   private final Map<UUID, Long> lastTpa = new HashMap<>();

   public TeleportService(WindyClans plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "homes.yml");
      this.homes = YamlConfiguration.loadConfiguration(this.file);
      this.migrateHomes();
   }

   /** MAJ 1.22.0 : plusieurs homes par joueur. L'ancien home unique devient le home « home ». */
   private void migrateHomes() {
      boolean changed = false;

      for (String k : this.homes.getKeys(false)) {
         if (this.homes.contains(k + ".world") && !this.homes.isConfigurationSection(k + ".homes")) {
            String base = k + ".homes." + DEFAULT_HOME;

            for (String f : new String[]{"world", "x", "y", "z", "yaw", "pitch"}) {
               this.homes.set(base + "." + f, this.homes.get(k + "." + f));
               this.homes.set(k + "." + f, null);
            }

            changed = true;
         }
      }

      if (changed) {
         this.save();
         this.plugin.getLogger().info("homes.yml mis à jour : chaque ancien home devient le home « " + DEFAULT_HOME + " ».");
      }
   }

   private static final String DEFAULT_HOME = "home";

   /** Nombre de homes autorisés (teleport.max-homes, 3 par défaut). */
   private int maxHomes() {
      return Math.max(1, this.plugin.getConfig().getInt("teleport.max-homes", 3));
   }

   private List<String> homeNames(UUID id) {
      org.bukkit.configuration.ConfigurationSection s = this.homes.getConfigurationSection(id + ".homes");
      return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
   }

   private static String cleanName(String raw) {
      String n = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
      if (n.length() > 16) {
         n = n.substring(0, 16);
      }

      return n.isEmpty() ? DEFAULT_HOME : n;
   }

   /** Le joueur a-t-il placé son home ? (parcours des nouveaux) */
   public boolean hasHome(UUID id) {
      return !this.homeNames(id).isEmpty();
   }

   private int warmup() {
      return Math.max(0, this.plugin.getConfig().getInt("teleport.warmup-seconds", 3));
   }

   private int expire() {
      return Math.max(10, this.plugin.getConfig().getInt("teleport.tpa-expire-seconds", 60));
   }

   private int combat() {
      return Math.max(0, this.plugin.getConfig().getInt("teleport.combat-block-seconds", 15));
   }

   private int tpaCooldown() {
      return Math.max(0, this.plugin.getConfig().getInt("teleport.tpa-cooldown-seconds", 10));
   }

   private void msg(CommandSender s, String t) {
      this.plugin.msg(s, t);
   }

   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (s instanceof Player p) {
         String var6 = cmd.getName().toLowerCase(Locale.ROOT);
         switch (var6) {
            case "sethome":
               this.setHome(p, args.length > 0 ? args[0] : null);
               break;
            case "home":
               this.home(p, args.length > 0 ? args[0] : null);
               break;
            case "homes":
               this.listHomes(p);
               break;
            case "delhome":
               this.delHome(p, args.length > 0 ? args[0] : null);
               break;
            case "spawn":
               this.spawn(p);
               break;
            case "tpa":
               if (args.length < 1) {
                  this.msg(p, "&c/tpa <joueur> &7· &c/tpa accept &7· &c/tpa refuse");
                  return true;
               }

               String a = args[0].toLowerCase(Locale.ROOT);
               if ((a.equals("accept") || a.equals("accepter")) && Bukkit.getPlayerExact(args[0]) == null) {
                  this.accept(p, args.length > 1 ? args[1] : null);
               } else if ((a.equals("refuse") || a.equals("refuser") || a.equals("deny")) && Bukkit.getPlayerExact(args[0]) == null) {
                  this.deny(p, args.length > 1 ? args[1] : null);
               } else {
                  this.request(p, args[0]);
               }
               break;
            case "tpaccept":
               this.accept(p, args.length > 0 ? args[0] : null);
               break;
            case "tpdeny":
               this.deny(p, args.length > 0 ? args[0] : null);
               break;
            default:
               return false;
         }

         return true;
      } else {
         this.msg(s, "&cCommande réservée aux joueurs.");
         return true;
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (args.length != 1) {
         return List.of();
      } else {
         String name = cmd.getName().toLowerCase(Locale.ROOT);
         List<String> out = new ArrayList<>();
         if (name.equals("tpa")) {
            out.add("accept");
            out.add("refuse");
         }

         if ((name.equals("home") || name.equals("delhome") || name.equals("sethome")) && s instanceof Player p) {
            out.addAll(this.homeNames(p.getUniqueId()));
         }

         if (name.equals("tpa") || name.equals("tpaccept") || name.equals("tpdeny")) {
            for (Player o : Bukkit.getOnlinePlayers()) {
               if (o != s) {
                  out.add(o.getName());
               }
            }
         }

         String pre = args[0].toLowerCase(Locale.ROOT);
         out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(pre));
         return out;
      }
   }

   private void setHome(Player p, String raw) {
      UUID id = p.getUniqueId();
      List<String> names = this.homeNames(id);
      String n = cleanName(raw);
      boolean had = names.contains(n);
      if (!had && names.size() >= this.maxHomes()) {
         this.msg(p, "&cTu as déjà " + names.size() + " homes (maximum " + this.maxHomes() + ") : &f" + String.join(", ", names)
            + "&c. Déplace-en un avec &f/sethome <nom>&c ou supprime-le avec &f/delhome <nom>&c.");
         return;
      }

      Location l = p.getLocation();
      String k = id + ".homes." + n;
      this.homes.set(id + ".name", p.getName());
      this.homes.set(k + ".world", l.getWorld().getName());
      this.homes.set(k + ".x", l.getX());
      this.homes.set(k + ".y", l.getY());
      this.homes.set(k + ".z", l.getZ());
      this.homes.set(k + ".yaw", (double)l.getYaw());
      this.homes.set(k + ".pitch", (double)l.getPitch());
      this.save();
      int count = had ? names.size() : names.size() + 1;
      this.msg(p, (had ? "&aHome &f" + n + "&a déplacé ici." : "&aHome &f" + n + "&a défini ici. &7Reviens-y avec &f/home " + n + "&7.")
         + " &8(" + count + "/" + this.maxHomes() + ")");
   }

   private Location homeOf(UUID id, String n) {
      String k = id + ".homes." + n;
      if (!this.homes.contains(k + ".world")) {
         return null;
      } else {
         World w = Bukkit.getWorld(this.homes.getString(k + ".world", ""));
         return w == null
            ? null
            : new Location(
               w,
               this.homes.getDouble(k + ".x"),
               this.homes.getDouble(k + ".y"),
               this.homes.getDouble(k + ".z"),
               (float)this.homes.getDouble(k + ".yaw"),
               (float)this.homes.getDouble(k + ".pitch")
            );
      }
   }

   /** Sans nom : le seul home, ou « home », sinon la liste. */
   private String pickHome(Player p, String raw) {
      List<String> names = this.homeNames(p.getUniqueId());
      if (raw != null) {
         return cleanName(raw);
      } else if (names.size() == 1) {
         return names.get(0);
      } else {
         return names.contains(DEFAULT_HOME) ? DEFAULT_HOME : null;
      }
   }

   private void home(Player p, String raw) {
      List<String> names = this.homeNames(p.getUniqueId());
      if (names.isEmpty()) {
         this.msg(p, "&cTu n'as pas de home. &7Place-le avec &f/sethome [nom]&7.");
         return;
      }

      String n = this.pickHome(p, raw);
      if (n == null) {
         this.listHomes(p);
      } else if (this.homeOf(p.getUniqueId(), n) == null) {
         this.msg(p, "&cAucun home nommé &f" + n + "&c. Tes homes : &f" + String.join(", ", names) + "&c.");
      } else {
         this.teleport(p, () -> this.homeOf(p.getUniqueId(), n), "ton home " + n);
      }
   }

   private void delHome(Player p, String raw) {
      List<String> names = this.homeNames(p.getUniqueId());
      String n = this.pickHome(p, raw);
      if (names.isEmpty()) {
         this.msg(p, "&cTu n'as pas de home.");
      } else if (n == null || !names.contains(n)) {
         this.msg(p, "&cPrécise le home à supprimer : &f/delhome <nom> &7(" + String.join(", ", names) + ")");
      } else {
         this.homes.set(p.getUniqueId() + ".homes." + n, null);
         this.save();
         this.msg(p, "&7Home &f" + n + "&7 supprimé.");
      }
   }

   private void listHomes(Player p) {
      List<String> names = this.homeNames(p.getUniqueId());
      if (names.isEmpty()) {
         this.msg(p, "&7Tu n'as pas de home. &f/sethome [nom] &7pour en placer un (" + this.maxHomes() + " maximum).");
         return;
      }

      TextComponent.Builder b = Component.text().append(Text.c("&7Tes homes (" + names.size() + "/" + this.maxHomes() + ") : "));
      for (int i = 0; i < names.size(); i++) {
         String n = names.get(i);
         if (i > 0) {
            b.append(Component.text(", ", NamedTextColor.DARK_GRAY));
         }

         b.append(
            Component.text(n, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
               .clickEvent(ClickEvent.runCommand("/home " + n))
               .hoverEvent(HoverEvent.showText(Component.text("Se téléporter à " + n)))
         );
      }

      p.sendMessage(b.build());
   }

   private void save() {
      try {
         this.homes.save(this.file);
      } catch (IOException var2) {
         this.plugin.getLogger().warning("homes.yml : " + var2.getMessage());
      }
   }

   private void spawn(Player p) {
      this.teleport(p, () -> {
         Location l = this.plugin.spawns() == null ? null : this.plugin.spawns().get();
         return l != null ? l : ((World)Bukkit.getWorlds().get(0)).getSpawnLocation().add(0.5, 0.0, 0.5);
      }, "le spawn");
   }

   private void request(Player p, String targetName) {
      Player t = Bukkit.getPlayerExact(targetName);
      if (t == null) {
         t = Bukkit.getPlayer(targetName);
      }

      if (t == null || !p.canSee(t)) {
         this.msg(p, "&cJoueur introuvable ou hors ligne.");
      } else if (t.equals(p)) {
         this.msg(p, "&cTu ne peux pas te téléporter à toi-même.");
      } else {
         long now = System.currentTimeMillis();
         Long last = this.lastTpa.get(p.getUniqueId());
         if (last != null && now - last < this.tpaCooldown() * 1000L) {
            this.msg(p, "&cAttends encore " + ((this.tpaCooldown() * 1000L - (now - last)) / 1000L + 1L) + "s avant une nouvelle demande.");
         } else {
            this.lastTpa.put(p.getUniqueId(), now);
            this.requests.computeIfAbsent(t.getUniqueId(), k -> new LinkedHashMap<>()).put(p.getUniqueId(), now + this.expire() * 1000L);
            this.msg(p, "&aDemande envoyée à &f" + t.getName() + "&a. &7Elle expire dans " + this.expire() + "s.");
            t.sendMessage(Text.c(this.plugin.settings().prefix + "&f" + p.getName() + " &7veut se téléporter à toi."));
            t.sendMessage(
               ((TextComponent)((TextComponent)Component.text("   ")
                        .append(
                           ((TextComponent)Component.text("[✔ Accepter]", NamedTextColor.GREEN, new TextDecoration[]{TextDecoration.BOLD})
                                 .clickEvent(ClickEvent.runCommand("/tpaccept " + p.getName())))
                              .hoverEvent(HoverEvent.showText(Component.text("/tpaccept " + p.getName())))
                        ))
                     .append(Component.text("   ")))
                  .append(
                     ((TextComponent)Component.text("[✘ Refuser]", NamedTextColor.RED, new TextDecoration[]{TextDecoration.BOLD})
                           .clickEvent(ClickEvent.runCommand("/tpdeny " + p.getName())))
                        .hoverEvent(HoverEvent.showText(Component.text("/tpdeny " + p.getName())))
                  )
            );
            t.sendMessage(Text.c("&8   ou tape &7/tpa accept &8· &7/tpa refuse"));
            t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.6F);
         }
      }
   }

   private UUID pick(Player target, String from) {
      LinkedHashMap<UUID, Long> map = this.requests.get(target.getUniqueId());
      if (map == null) {
         return null;
      } else {
         long now = System.currentTimeMillis();
         map.values().removeIf(exp -> exp < now);
         if (map.isEmpty()) {
            this.requests.remove(target.getUniqueId());
            return null;
         } else if (from != null) {
            for (UUID id : map.keySet()) {
               Player o = Bukkit.getPlayer(id);
               if (o != null && o.getName().equalsIgnoreCase(from)) {
                  return id;
               }
            }

            return null;
         } else {
            UUID lastId = null;

            for (UUID idx : map.keySet()) {
               lastId = idx;
            }

            return lastId;
         }
      }
   }

   private void accept(Player t, String from) {
      UUID id = this.pick(t, from);
      Player p = id == null ? null : Bukkit.getPlayer(id);
      if (p == null) {
         this.msg(t, "&cAucune demande de téléportation en attente.");
      } else {
         this.requests.get(t.getUniqueId()).remove(id);
         this.msg(t, "&aTu as accepté la demande de &f" + p.getName() + "&a.");
         this.msg(p, "&f" + t.getName() + " &aa accepté ta demande.");
         this.teleport(p, () -> t.isOnline() ? t.getLocation() : null, t.getName());
      }
   }

   private void deny(Player t, String from) {
      UUID id = this.pick(t, from);
      if (id == null) {
         this.msg(t, "&cAucune demande de téléportation en attente.");
      } else {
         this.requests.get(t.getUniqueId()).remove(id);
         Player p = Bukkit.getPlayer(id);
         this.msg(t, "&7Demande refusée.");
         if (p != null) {
            this.msg(p, "&c" + t.getName() + " a refusé ta demande de téléportation.");
         }
      }
   }

   private void teleport(Player p, Supplier<Location> dest, String what) {
      long now = System.currentTimeMillis();
      Long pvp = this.lastPvp.get(p.getUniqueId());
      boolean bypass = this.plugin.bypass().contains(p.getUniqueId());
      if (!bypass && pvp != null && now - pvp < this.combat() * 1000L) {
         this.msg(p, "&cTu es en combat ! Attends " + ((this.combat() * 1000L - (now - pvp)) / 1000L + 1L) + "s.");
      } else {
         this.cancel(p.getUniqueId());
         int w = bypass ? 0 : this.warmup();
         if (w <= 0) {
            this.go(p, dest.get(), what);
         } else {
            Location start = p.getLocation();
            this.msg(p, "&7Téléportation vers &f" + what + " &7dans &f" + w + "s&7, ne bouge pas...");
            int[] left = new int[]{w};
            this.pending.put(p.getUniqueId(), Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
               if (p.isOnline() && p.getWorld() == start.getWorld() && !(p.getLocation().distanceSquared(start) > 0.5)) {
                  left[0]--;
                  if (left[0] <= 0) {
                     this.cancel(p.getUniqueId());
                     this.go(p, dest.get(), what);
                  } else {
                     p.sendActionBar(Text.c("&7Téléportation dans &f" + left[0] + "s"));
                  }
               } else {
                  this.cancel(p.getUniqueId());
                  if (p.isOnline()) {
                     this.msg(p, "&cTéléportation annulée (tu as bougé).");
                  }
               }
            }, 20L, 20L));
         }
      }
   }

   private void go(Player p, Location l, String what) {
      if (l != null && l.getWorld() != null) {
         p.teleportAsync(l).thenRun(() -> p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F));
      } else {
         this.msg(p, "&cDestination introuvable (" + what + ").");
      }
   }

   private void cancel(UUID id) {
      BukkitTask t = this.pending.remove(id);
      if (t != null) {
         t.cancel();
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onDamage(EntityDamageEvent e) {
      if (e.getEntity() instanceof Player p) {
         if (this.pending.containsKey(p.getUniqueId())) {
            this.cancel(p.getUniqueId());
            this.msg(p, "&cTéléportation annulée (tu as pris des dégâts).");
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onPvp(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof Player victim) {
         Player attacker = e.getDamager() instanceof Player d ? d : (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null);
         if (attacker != null && !attacker.equals(victim)) {
            long now = System.currentTimeMillis();
            this.lastPvp.put(victim.getUniqueId(), now);
            this.lastPvp.put(attacker.getUniqueId(), now);
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      UUID id = e.getPlayer().getUniqueId();
      this.cancel(id);
      this.requests.remove(id);
      this.requests.values().forEach(m -> m.remove(id));
   }
}
