package fr.windydev.clans.hub;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Le spawn vivant : hologrammes d'explication, PNJ qui ouvrent les menus, et protection de la place.
 * Tout est décrit dans spawn-hub.yml ; les entités ne sont jamais sauvegardées dans le monde.
 */
public class HubManager implements Listener {
   private static final String TAG = "windyhub";
   public static final List<String> ACTIONS = List.of("boutique", "donjons", "boss", "roue", "grimoire", "classement", "clans", "profil", "races", "menu", "faille");

   private final WindyClans plugin;
   private final Map<String, Holo> holos = new LinkedHashMap<>();
   private final Map<String, Npc> npcs = new LinkedHashMap<>();
   private final Map<String, Entity> live = new HashMap<>();
   private final Map<UUID, Long> lastUse = new HashMap<>();
   private File file;
   private String zoneWorld;
   private double zoneX, zoneZ, zoneR;
   private boolean protect = true;

   record Holo(String world, double x, double y, double z, double scale, List<String> lines) {
   }

   record Npc(String world, double x, double y, double z, float yaw, String type, String profession, String biome, String name, String action, double roam) {
   }

   public HubManager(WindyClans plugin) {
      this.plugin = plugin;
   }

   // ------------------------------------------------------------------ fichier

   public void load() {
      this.removeAll();
      this.file = new File(this.plugin.getDataFolder(), "spawn-hub.yml");
      YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);
      this.holos.clear();
      this.npcs.clear();
      ConfigurationSection hs = y.getConfigurationSection("holograms");
      if (hs != null) {
         for (String k : hs.getKeys(false)) {
            this.holos.put(k, new Holo(hs.getString(k + ".world", "world"), hs.getDouble(k + ".x"), hs.getDouble(k + ".y"), hs.getDouble(k + ".z"),
               hs.getDouble(k + ".scale", 1.0), hs.getStringList(k + ".lines")));
         }
      }

      ConfigurationSection ns = y.getConfigurationSection("npcs");
      if (ns != null) {
         for (String k : ns.getKeys(false)) {
            this.npcs.put(k, new Npc(ns.getString(k + ".world", "world"), ns.getDouble(k + ".x"), ns.getDouble(k + ".y"), ns.getDouble(k + ".z"),
               (float)ns.getDouble(k + ".yaw"), ns.getString(k + ".type", "VILLAGER"), ns.getString(k + ".profession", "none"),
               ns.getString(k + ".biome", "plains"), ns.getString(k + ".name", k), ns.getString(k + ".action", "menu"), ns.getDouble(k + ".roam", 0.0)));
         }
      }

      this.zoneWorld = y.getString("zone.world");
      this.zoneX = y.getDouble("zone.x");
      this.zoneZ = y.getDouble("zone.z");
      this.zoneR = y.getDouble("zone.radius", 0.0);
      this.protect = y.getBoolean("zone.protect", true);
   }

   public void save() {
      YamlConfiguration y = new YamlConfiguration();
      y.options().setHeader(List.of("WindyClans - spawn : hologrammes, PNJ et zone protégée.",
         "Créé par /clanadmin hub installer ; modifiable à la main, puis /clanadmin hub recharger.",
         "Actions des PNJ : " + String.join(", ", ACTIONS) + ", ou cmd:<commande sans />."));
      if (this.zoneWorld != null) {
         y.set("zone.world", this.zoneWorld);
         y.set("zone.x", this.zoneX);
         y.set("zone.z", this.zoneZ);
         y.set("zone.radius", this.zoneR);
         y.set("zone.protect", this.protect);
      }

      this.holos.forEach((k, h) -> {
         String b = "holograms." + k + ".";
         y.set(b + "world", h.world());
         y.set(b + "x", h.x());
         y.set(b + "y", h.y());
         y.set(b + "z", h.z());
         y.set(b + "scale", h.scale());
         y.set(b + "lines", h.lines());
      });
      this.npcs.forEach((k, n) -> {
         String b = "npcs." + k + ".";
         y.set(b + "world", n.world());
         y.set(b + "x", n.x());
         y.set(b + "y", n.y());
         y.set(b + "z", n.z());
         y.set(b + "yaw", (double)n.yaw());
         y.set(b + "type", n.type());
         y.set(b + "profession", n.profession());
         y.set(b + "biome", n.biome());
         y.set(b + "name", n.name());
         y.set(b + "action", n.action());
         if (n.roam() > 0.0) {
            y.set(b + "roam", n.roam());
         }
      });

      try {
         y.save(this.file);
      } catch (IOException e) {
         this.plugin.getLogger().warning("spawn-hub.yml : sauvegarde impossible : " + e.getMessage());
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::ensure, 60L, 60L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::look, 65L, 5L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tether, 100L, 100L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::refresh, 200L, 1200L);
   }

   public void shutdown() {
      this.removeAll();
   }

   // ------------------------------------------------------------------ modification

   public void setZone(Location c, double radius) {
      this.zoneWorld = c.getWorld().getName();
      this.zoneX = c.getX();
      this.zoneZ = c.getZ();
      this.zoneR = radius;
   }

   public void putHolo(String id, Location l, double scale, List<String> lines) {
      this.kill(id);
      this.holos.put(id, new Holo(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), scale, new ArrayList<>(lines)));
   }

   public void putNpc(String id, Location l, String profession, String biome, String name, String action) {
      this.kill(id);
      this.npcs.put(id, new Npc(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), "VILLAGER", profession, biome, name, action, 0.0));
   }

   /** Habitant ou animal qui se promène dans un rayon autour de son point de départ. */
   public void putRoamer(String id, Location l, String type, String profession, String biome, String name, String action, double roam) {
      this.kill(id);
      this.npcs.put(id, new Npc(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), type, profession, biome, name, action, roam));
   }

   /** Remplace {top_clans}, {top_joueurs} et {records} par les classements du moment. */
   private String expand(List<String> lines) {
      List<String> out = new ArrayList<>();
      for (String l : lines) {
         switch (l.trim()) {
            case "{top_clans}" -> {
               List<fr.windydev.clans.model.Clan> top = this.plugin.clans().top();
               for (int i = 0; i < Math.min(5, top.size()); i++) {
                  out.add((i == 0 ? "&6" : (i == 1 ? "&7" : (i == 2 ? "&c" : "&8"))) + "#" + (i + 1) + " " + top.get(i).coloredTag() + " &f" + top.get(i).name() + " &7niv. &e" + top.get(i).level());
               }

               if (top.isEmpty()) {
                  out.add("&7Aucun clan pour l'instant");
               }
            }
            case "{top_joueurs}" -> {
               List<fr.windydev.clans.profile.Profile> top = this.plugin.profiles().top();
               for (int i = 0; i < Math.min(5, top.size()); i++) {
                  out.add((i == 0 ? "&6" : (i == 1 ? "&7" : (i == 2 ? "&c" : "&8"))) + "#" + (i + 1) + " &f" + top.get(i).name() + " &7niv. &d" + top.get(i).level());
               }

               if (top.isEmpty()) {
                  out.add("&7Personne pour l'instant");
               }
            }
            case "{records}" -> {
               String txt = this.plugin.dungeons().recordsDiscord();
               int cut = txt.indexOf("\n\n");
               for (String r : (cut >= 0 ? txt.substring(0, cut) : txt).split("\n")) {
                  if (r.isBlank()) {
                     continue;
                  }

                  boolean title = r.startsWith("**");
                  String clean = r.replace("**", "").replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Zs}]", "").trim();
                  out.add(title ? "&c" + clean : "&7• &f" + clean);
               }
            }
            default -> out.add(l);
         }
      }

      return String.join("\n", out);
   }

   /** Met à jour les hologrammes de classement. */
   private void refresh() {
      for (Map.Entry<String, Holo> en : this.holos.entrySet()) {
         if (this.live.get(en.getKey()) instanceof TextDisplay d && d.isValid() && String.join("", en.getValue().lines()).contains("{")) {
            d.text(Text.c(this.expand(en.getValue().lines())));
         }
      }
   }

   /** Ramène vers chez eux les promeneurs qui s'éloignent trop. */
   private void tether() {
      for (Map.Entry<String, Npc> en : this.npcs.entrySet()) {
         Npc n = en.getValue();
         if (n.roam() > 0.0 && this.live.get(en.getKey()) instanceof Mob m && m.isValid()) {
            Location home = loc(n.world(), n.x(), n.y(), n.z());
            if (home == null) {
               continue;
            }

            double d = m.getLocation().distanceSquared(home);
            if (d > (n.roam() * 3.0) * (n.roam() * 3.0) || Math.abs(m.getLocation().getY() - home.getY()) > 6.0) {
               m.teleport(home);
            } else if (d > n.roam() * n.roam()) {
               m.getPathfinder().moveTo(home, 0.6);
            }
         }
      }
   }

   public boolean remove(String id) {
      this.kill(id);
      return this.holos.remove(id) != null | this.npcs.remove(id) != null;
   }

   /** Retire tout ce dont l'id commence par ce préfixe (annulation de l'installation du spawn). */
   public void removePrefix(String prefix) {
      for (String k : new ArrayList<>(this.holos.keySet())) {
         if (k.startsWith(prefix)) {
            this.remove(k);
         }
      }

      for (String k : new ArrayList<>(this.npcs.keySet())) {
         if (k.startsWith(prefix)) {
            this.remove(k);
         }
      }
   }

   private void kill(String id) {
      Entity e = this.live.remove(id);
      if (e != null && e.isValid()) {
         e.remove();
      }
   }

   private void removeAll() {
      for (Entity e : this.live.values()) {
         if (e.isValid()) {
            e.remove();
         }
      }

      this.live.clear();
   }

   // ------------------------------------------------------------------ entités

   private static Location loc(String world, double x, double y, double z) {
      World w = Bukkit.getWorld(world);
      return w == null ? null : new Location(w, x, y, z);
   }

   /** Fait (ré)apparaître ce qui manque dans les zones chargées. */
   public void ensure() {
      for (Map.Entry<String, Holo> en : this.holos.entrySet()) {
         Holo h = en.getValue();
         Location l = loc(h.world(), h.x(), h.y(), h.z());
         if (l == null || !l.isChunkLoaded() || this.valid(en.getKey())) {
            continue;
         }

         this.sweep(l, en.getKey());
         float s = (float)h.scale();
         TextDisplay d = l.getWorld().spawn(l, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.addScoreboardTag(TAG + "_" + en.getKey());
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setShadowed(true);
            e.setLineWidth(260);
            e.setViewRange(0.6F);
            e.text(Text.c(this.expand(h.lines())));
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
         });
         this.live.put(en.getKey(), d);
      }

      for (Map.Entry<String, Npc> en : this.npcs.entrySet()) {
         Npc n = en.getValue();
         Location l = loc(n.world(), n.x(), n.y(), n.z());
         if (l == null || !l.isChunkLoaded() || this.valid(en.getKey())) {
            continue;
         }

         l.setYaw(n.yaw());
         this.sweep(l, en.getKey());
         EntityType type;
         try {
            type = EntityType.valueOf(n.type().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException ex) {
            type = EntityType.VILLAGER;
         }

         if (!type.isAlive() || type.getEntityClass() == null) {
            type = EntityType.VILLAGER;
         }

         Entity e = l.getWorld().spawn(l, type.getEntityClass(), false, x -> {
            x.setPersistent(false);
            x.addScoreboardTag(TAG);
            x.addScoreboardTag(TAG + "_" + en.getKey());
            x.setInvulnerable(true);
            x.setSilent(true);
            x.customName(Text.c(n.name()));
            x.setCustomNameVisible(true);
            if (x instanceof LivingEntity le) {
               le.setAI(n.roam() > 0.0);
               le.setCollidable(false);
               le.setRemoveWhenFarAway(false);
               le.setCanPickupItems(false);
            }

            if (x instanceof Villager v) {
               Villager.Profession pr = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(n.profession().toLowerCase(Locale.ROOT)));
               Villager.Type bt = Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft(n.biome().toLowerCase(Locale.ROOT)));
               if (bt != null) {
                  v.setVillagerType(bt);
               }

               if (pr != null) {
                  v.setProfession(pr);
               }

               v.setVillagerLevel(5);
               v.setVillagerExperience(1);
            }
         });
         this.live.put(en.getKey(), e);
      }
   }

   private boolean valid(String id) {
      Entity e = this.live.get(id);
      return e != null && e.isValid();
   }

   /** Supprime un éventuel double resté dans le monde. */
   private void sweep(Location l, String id) {
      for (Entity e : l.getWorld().getNearbyEntities(l, 2.0, 3.0, 2.0)) {
         if (e.getScoreboardTags().contains(TAG + "_" + id)) {
            e.remove();
         }
      }
   }

   /** Les PNJ regardent le joueur le plus proche. */
   private void look() {
      for (Map.Entry<String, Npc> en : this.npcs.entrySet()) {
         Entity e = this.live.get(en.getKey());
         if (e == null || !e.isValid() || en.getValue().roam() > 0.0) {
            continue;
         }

         Player best = null;
         double bd = 64.0;
         for (Player p : e.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(e.getLocation());
            if (d < bd) {
               bd = d;
               best = p;
            }
         }

         if (best != null) {
            Location a = e.getLocation(), b = best.getEyeLocation();
            double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ(), dy = b.getY() - (a.getY() + e.getHeight() * 0.9);
            float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float)-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            e.setRotation(yaw, Math.max(-40.0F, Math.min(40.0F, pitch)));
         } else if (Math.abs(e.getLocation().getYaw() - en.getValue().yaw()) > 1.0F) {
            e.setRotation(en.getValue().yaw(), 0.0F);
         }
      }
   }

   private String idOf(Entity e) {
      if (!e.getScoreboardTags().contains(TAG)) {
         return null;
      }

      for (String t : e.getScoreboardTags()) {
         if (t.startsWith(TAG + "_")) {
            return t.substring(TAG.length() + 1);
         }
      }

      return null;
   }

   // ------------------------------------------------------------------ actions

   public void run(Player p, String action) {
      String a = action.trim();
      if (a.toLowerCase(Locale.ROOT).startsWith("say:")) {
         String[] lines = a.substring(4).split("\\|");
         p.sendMessage(Text.c("&8» &7" + lines[java.util.concurrent.ThreadLocalRandom.current().nextInt(lines.length)].trim()));
         p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_AMBIENT, 0.6F, 1.0F);
         return;
      }

      if (a.toLowerCase(Locale.ROOT).startsWith("cmd:")) {
         p.performCommand(a.substring(4).trim());
         return;
      }

      switch (a.toLowerCase(Locale.ROOT)) {
         case "boutique" -> this.plugin.dungeons().shop().menu(p, 0);
         case "donjons" -> this.plugin.dungeons().menu(p);
         case "boss" -> this.plugin.menus().bosses(p);
         case "roue" -> this.plugin.wheel().open(p);
         case "grimoire" -> this.plugin.magic().menu(p);
         case "classement" -> this.plugin.menus().playersTop(p);
         case "clans" -> this.plugin.menus().top(p);
         case "profil" -> this.plugin.menus().profile(p);
         case "races" -> this.plugin.menus().races(p);
         case "faille" -> this.plugin.rift().join(p);
         default -> this.plugin.menus().main(p);
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onInteract(PlayerInteractEntityEvent e) {
      String id = this.idOf(e.getRightClicked());
      if (id == null) {
         return;
      }

      e.setCancelled(true);
      Npc n = this.npcs.get(id);
      if (n == null || e.getHand() != EquipmentSlot.HAND) {
         return;
      }

      Player p = e.getPlayer();
      long now = System.currentTimeMillis();
      Long last = this.lastUse.get(p.getUniqueId());
      if (last != null && now - last < 400L) {
         return;
      }

      this.lastUse.put(p.getUniqueId(), now);
      try {
         this.run(p, n.action());
      } catch (Exception ex) {
         this.plugin.getLogger().warning("PNJ " + id + " : action « " + n.action() + " » en erreur : " + ex);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onNpcDamage(EntityDamageEvent e) {
      if (e.getEntity().getScoreboardTags().contains(TAG)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onTransform(EntityTransformEvent e) {
      if (e.getEntity().getScoreboardTags().contains(TAG)) {
         e.setCancelled(true);
      }
   }

   // ------------------------------------------------------------------ protection de la place

   public boolean inZone(Location l) {
      if (this.zoneWorld == null || this.zoneR <= 0.0 || !this.protect || l.getWorld() == null || !l.getWorld().getName().equals(this.zoneWorld)) {
         return false;
      }

      double dx = l.getX() - this.zoneX, dz = l.getZ() - this.zoneZ;
      return dx * dx + dz * dz <= this.zoneR * this.zoneR;
   }

   /** Le chunk (cx, cz) touche-t-il la zone protégée ? Sert au reste du plugin (claims, protections, HUD). */
   public boolean zoneTouches(String world, int cx, int cz) {
      if (this.zoneWorld == null || this.zoneR <= 0.0 || !this.protect || !this.zoneWorld.equals(world)) {
         return false;
      }

      double nx = Math.max(cx * 16.0, Math.min(this.zoneX, cx * 16.0 + 16.0)), nz = Math.max(cz * 16.0, Math.min(this.zoneZ, cz * 16.0 + 16.0));
      double dx = nx - this.zoneX, dz = nz - this.zoneZ;
      return dx * dx + dz * dz <= this.zoneR * this.zoneR;
   }

   // casser, poser, seaux, coffres, cadres : tout passe par ProtectionListener (zone du spawn)

   /** Enderman, villageois fermiers, moutons... : aucune créature ne modifie le village. */
   @EventHandler(ignoreCancelled = true)
   public void onMobChange(EntityChangeBlockEvent e) {
      if (!(e.getEntity() instanceof Player) && !(e.getEntity() instanceof FallingBlock) && this.inZone(e.getBlock().getLocation())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onBurn(BlockBurnEvent e) {
      if (this.inZone(e.getBlock().getLocation())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onIgnite(BlockIgniteEvent e) {
      if (e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL && e.getCause() != BlockIgniteEvent.IgniteCause.FIREBALL
         && this.inZone(e.getBlock().getLocation())) {
         e.setCancelled(true); // propagation, lave, foudre ; le briquet d'un joueur est déjà géré par la protection
      }
   }

   /** L'eau ou la lave versée dehors ne coule pas dans le village. */
   @EventHandler(ignoreCancelled = true)
   public void onFlow(BlockFromToEvent e) {
      if (this.inZone(e.getToBlock().getLocation()) && !this.inZone(e.getBlock().getLocation())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onExplode(EntityExplodeEvent e) {
      e.blockList().removeIf(b -> this.inZone(b.getLocation()));
   }

   @EventHandler(ignoreCancelled = true)
   public void onSpawn(CreatureSpawnEvent e) {
      if (e.getEntity() instanceof Monster && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL && this.inZone(e.getLocation())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onPvp(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof Player && this.inZone(e.getEntity().getLocation())) {
         Entity src = e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity sh ? sh : e.getDamager();
         if (src instanceof Player || src instanceof Mob) {
            e.setCancelled(true); // la place du spawn est une zone sûre
         }
      }
   }

   // ------------------------------------------------------------------ commande /clanadmin hub

   public void admin(CommandSender s, String[] args) {
      String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "aide";
      switch (act) {
         case "installer", "install" -> new SpawnBuilder(this.plugin, this).install(s, Arrays.copyOfRange(args, 2, args.length));
         case "annuler", "undo" -> new SpawnBuilder(this.plugin, this).undo(s);
         case "reconstruire", "rebuild" -> new SpawnBuilder(this.plugin, this).rebuild(s);
         case "recharger", "reload" -> {
            this.load();
            this.ensure();
            this.plugin.msg(s, "&aSpawn rechargé : " + this.holos.size() + " hologramme(s), " + this.npcs.size() + " PNJ.");
         }
         case "liste", "list" -> {
            this.plugin.msg(s, "&eHologrammes : &f" + String.join(", ", this.holos.keySet()));
            this.plugin.msg(s, "&ePNJ : &f" + this.npcs.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue().action() + ")").toList());
         }
         case "suppr", "supprimer", "remove" -> {
            if (args.length > 2 && this.remove(args[2])) {
               this.save();
               this.plugin.msg(s, "&aSupprimé : " + args[2]);
            } else {
               this.plugin.msg(s, "&cId inconnu. &7/clanadmin hub liste");
            }
         }
         case "holo" -> {
            if (!(s instanceof Player p) || args.length < 4) {
               this.plugin.msg(s, "&c/clanadmin hub holo <id> <texte> &7(en jeu ; | pour changer de ligne, & pour les couleurs)");
               return;
            }

            String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            this.putHolo(args[2], p.getLocation().add(0.0, 2.2, 0.0), 1.0, Arrays.asList(text.split("\\|")));
            this.save();
            this.ensure();
            this.plugin.msg(s, "&aHologramme « " + args[2] + " » placé ici.");
         }
         case "pnj", "npc" -> {
            if (!(s instanceof Player p) || args.length < 6) {
               this.plugin.msg(s, "&c/clanadmin hub pnj <id> <action> <métier> <nom> &7(en jeu)");
               this.plugin.msg(s, "&7Actions : &f" + String.join(", ", ACTIONS) + ", cmd:<commande>");
               this.plugin.msg(s, "&7Métiers : &flibrarian, cleric, armorer, weaponsmith, cartographer, farmer, fletcher, mason, nitwit...");
               return;
            }

            this.putNpc(args[2], p.getLocation(), args[4], "plains", String.join(" ", Arrays.copyOfRange(args, 5, args.length)), args[3]);
            this.save();
            this.ensure();
            this.plugin.msg(s, "&aPNJ « " + args[2] + " » placé ici.");
         }
         default -> {
            this.plugin.msg(s, "&e/clanadmin hub installer [x y z] [monde] &7- construit la place du spawn (pavillon, décor, PNJ, hologrammes)");
            this.plugin.msg(s, "&e/clanadmin hub annuler &7- remet le terrain d'avant l'installation");
            this.plugin.msg(s, "&e/clanadmin hub reconstruire &7- refait la place au même endroit (après une mise à jour)");
            this.plugin.msg(s, "&e/clanadmin hub holo <id> <texte> &7| &epnj <id> <action> <métier> <nom> &7| &esuppr <id> &7| &eliste &7| &erecharger");
         }
      }
   }
}
