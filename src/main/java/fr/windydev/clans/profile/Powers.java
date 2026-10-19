package fr.windydev.clans.profile;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.AbstractArrow.PickupStatus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public class Powers implements Listener {
   private final WindyClans plugin;
   private final ProfileManager pm;
   private final SkillEngine engine;
   private final Map<UUID, Map<String, Long>> ready = new HashMap<>();
   private final Map<UUID, List<Long>> charges = new HashMap<>();
   private final Map<UUID, BukkitTask> pending = new HashMap<>();
   private final Map<UUID, Long> ultimateUntil = new HashMap<>();
   private final Map<UUID, String> ultimateRace = new HashMap<>();
   private final Map<UUID, Long> frenzyUntil = new HashMap<>();
   private final Map<UUID, Long> mistUntil = new HashMap<>();
   private final Map<UUID, Long> stoneUntil = new HashMap<>();
   private final Map<UUID, Long> rainUntil = new HashMap<>();
   private final Set<UUID> stoneReflect = new HashSet<>();
   private final Map<UUID, int[]> arrowRain = new HashMap<>();

   public Powers(WindyClans plugin, ProfileManager pm, SkillEngine engine) {
      this.plugin = plugin;
      this.pm = pm;
      this.engine = engine;
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 10L, 10L);
   }

   private static long now() {
      return System.currentTimeMillis();
   }

   private static boolean live(Map<UUID, Long> m, Player p) {
      Long t = m.get(p.getUniqueId());
      return t != null && t > now();
   }

   public boolean active(Player p, String race) {
      return race.equals(this.ultimateRace.get(p.getUniqueId())) && live(this.ultimateUntil, p);
   }

   public boolean raining(Player p) {
      return live(this.rainUntil, p);
   }

   public boolean flying(Player p) {
      return this.active(p, "sylphe");
   }

   public double bonus(Player p, String key) {
      return switch (key) {
         case "lifesteal" -> live(this.frenzyUntil, p) ? 0.1 : 0.0;
         case "dodge" -> this.active(p, "arcanien") ? 0.3 : 0.0;
         case "coin-gain" -> "humain".equals(this.pm.of(p).race()) && this.learned(p, "extra") ? 0.1 : 0.0;
         default -> 0.0;
      };
   }

   public boolean noWeaponCooldown(Player p) {
      return this.active(p, "arcanien");
   }

   public void reapply(Player p) {
   }

   private boolean learned(Player p, String kind) {
      return this.pm.hasSpecial(p, kind);
   }

   private SkillTree.Special special(Player p, String kind) {
      return this.pm.tree().special(this.pm.of(p).race(), kind);
   }

   private long left(Player p, String kind) {
      return Math.max(0L, this.ready.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(kind, 0L) - now());
   }

   public long cooldownLeft(Player p, String kind) {
      if (kind.equals("unique") && this.learned(p, "unique-plus") && "arcanien".equals(this.pm.of(p).race())) {
         List<Long> c = this.charges.getOrDefault(p.getUniqueId(), List.of());
         long best = Long.MAX_VALUE;
         int free = 2 - c.size();

         for (long t : c) {
            if (t <= now()) {
               free++;
            } else {
               best = Math.min(best, t - now());
            }
         }

         return free > 0 ? 0L : best;
      } else {
         return this.left(p, kind);
      }
   }

   private void setCooldown(Player p, String kind, int seconds) {
      this.ready.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(kind, now() + seconds * 1000L);
   }

   @EventHandler(
      priority = EventPriority.LOW
   )
   public void onSwap(PlayerSwapHandItemsEvent e) {
      Player p = e.getPlayer();
      if (p.isSneaking()) {
         Profile pr = this.pm.of(p);
         if (pr.race() != null) {
            boolean uni = this.learned(p, "unique");
            boolean ult = this.learned(p, "ultimate") && !pr.race().equals("humain");
            if (uni || ult) {
               e.setCancelled(true);
               BukkitTask t = this.pending.remove(p.getUniqueId());
               if (t != null) {
                  t.cancel();
                  if (ult) {
                     this.useUltimate(p);
                  } else {
                     this.useUnique(p);
                  }
               } else if (!ult) {
                  this.useUnique(p);
               } else {
                  this.pending.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                     this.pending.remove(p.getUniqueId());
                     if (p.isOnline()) {
                        if (uni) {
                           this.useUnique(p);
                        } else {
                           p.sendActionBar(Text.c("&7Appuie deux fois sur &fF &7(accroupi) pour ton ultime."));
                        }
                     }
                  }, 7L));
               }
            }
         }
      }
   }

   private void useUnique(Player p) {
      Profile pr = this.pm.of(p);
      SkillTree.Special sp = this.special(p, "unique");
      if (sp != null && this.learned(p, "unique")) {
         boolean plus = this.learned(p, "unique-plus");
         String race = pr.race();
         if (race.equals("arcanien") && plus) {
            List<Long> c = this.charges.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
            c.removeIf(x -> x <= now());
            if (c.size() >= 2) {
               this.cdMsg(p, sp.name(), c.stream().min(Long::compare).orElse(0L) - now());
            } else if (this.blink(p, 20.0)) {
               c.add(now() + sp.cooldown() * 1000L);
            }
         } else {
            long l = this.left(p, "unique");
            if (l > 0L) {
               this.cdMsg(p, sp.name(), l);
            } else {
               boolean ok = switch (race) {
                  case "infernal" -> this.inferno(p, plus);
                  case "orc" -> this.frenzy(p, plus);
                  case "arcanien" -> this.blink(p, 12.0);
                  case "vampire" -> this.mist(p, plus);
                  case "sylphe" -> this.gust(p, plus);
                  case "elfe" -> this.arrows(p, plus);
                  case "nain" -> this.stone(p, plus);
                  case "ondin" -> this.geyser(p, plus);
                  case "humain" -> this.rally(p, plus);
                  default -> false;
               };
               if (ok) {
                  this.setCooldown(p, "unique", sp.cooldown());
               }
            }
         }
      }
   }

   private void useUltimate(Player p) {
      Profile pr = this.pm.of(p);
      SkillTree.Special sp = this.special(p, "ultimate");
      if (sp != null && this.learned(p, "ultimate")) {
         long l = this.left(p, "ultimate");
         if (l > 0L) {
            this.cdMsg(p, sp.name(), l);
         } else {
            String race = pr.race();

            int secs = switch (race) {
               case "infernal", "arcanien", "sylphe", "nain" -> 20;
               case "orc" -> 10;
               case "vampire", "ondin" -> 30;
               case "elfe" -> 1;
               default -> 0;
            };
            if (secs > 0) {
               this.setCooldown(p, "ultimate", sp.cooldown());
               this.ultimateRace.put(p.getUniqueId(), race);
               this.ultimateUntil.put(p.getUniqueId(), now() + secs * 1000L);
               World w = p.getWorld();
               Location l0 = p.getLocation();
               p.showTitle(
                  Title.title(
                     Text.c("&6✦ " + sp.name() + " ✦"),
                     Text.c("&7Compétence ultime"),
                     Times.times(Duration.ofMillis(200L), Duration.ofMillis(1600L), Duration.ofMillis(500L))
                  )
               );
               w.playSound(l0, Sound.ENTITY_WITHER_SPAWN, 0.6F, 1.6F);
               w.spawnParticle(Particle.TOTEM_OF_UNDYING, l0.clone().add(0.0, 1.0, 0.0), 60, 0.6, 1.0, 0.6, 0.4);

               for (Player o : l0.getNearbyPlayers(40.0)) {
                  if (o != p) {
                     o.sendActionBar(Text.c("&6✦ &f" + p.getName() + " &7déchaîne &6" + sp.name() + " &7!"));
                  }
               }

               switch (race) {
                  case "sylphe":
                     p.setAllowFlight(true);
                     p.setFlying(true);
                     break;
                  case "nain":
                     this.mod(p, Attribute.SCALE, "power_titan", 0.5, Operation.ADD_SCALAR);
                     this.mod(p, Attribute.MAX_HEALTH, "power_titan", 20.0, Operation.ADD_NUMBER);
                     this.engine.heal(p, 20.0);
                     break;
                  case "elfe":
                     this.roots(p);
                     break;
                  case "ondin":
                     p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 600, 0));
                     break;
                  case "vampire":
                     p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 600, 0, true, false, true));
               }

               this.pm.apply(p);
            }
         }
      }
   }

   private void cdMsg(Player p, String name, long ms) {
      long s = (ms + 999L) / 1000L;
      p.sendActionBar(Text.c("&7" + name + " &8» &c" + (s >= 60L ? s / 60L + " min " + s % 60L + " s" : s + " s")));
   }

   private List<LivingEntity> enemies(Player p, double r) {
      List<LivingEntity> l = new ArrayList<>();

      for (Entity e : p.getNearbyEntities(r, r, r)) {
         if (e instanceof LivingEntity le && this.engine.hostile(p, e) && e.getLocation().distanceSquared(p.getLocation()) <= r * r) {
            l.add(le);
         }
      }

      return l;
   }

   private boolean inferno(Player p, boolean plus) {
      double r = plus ? 8.0 : 5.0;
      World w = p.getWorld();
      Location c = p.getLocation();

      for (int ring = 1; ring <= (int)r; ring++) {
         int rr = ring;
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            for (int i = 0; i < rr * 10; i++) {
               double a = i * Math.PI * 2.0 / (rr * 10);
               w.spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * rr, 0.2, Math.sin(a) * rr), 1, 0.0, 0.1, 0.0, 0.01);
            }
         }, ring);
      }

      w.playSound(c, Sound.ITEM_FIRECHARGE_USE, 1.0F, 0.6F);
      w.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 1.0F, 0.7F);

      for (LivingEntity e : this.enemies(p, r)) {
         e.damage(6.0, p);
         e.setFireTicks(100);
      }

      if (plus) {
         for (int s = 1; s <= 5; s++) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
               if (p.isOnline()) {
                  for (int i = 0; i < 40; i++) {
                     double a = Math.random() * Math.PI * 2.0;
                     double d = Math.random() * r;
                     w.spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * d, 0.1, Math.sin(a) * d), 1, 0.0, 0.05, 0.0, 0.005);
                  }

                  for (Entity ex : w.getNearbyEntities(c, r, 2.0, r)) {
                     if (ex instanceof LivingEntity le && this.engine.hostile(p, ex) && le.isOnGround()) {
                        le.setFireTicks(Math.max(le.getFireTicks(), 60));
                     }
                  }
               }
            }, s * 20L);
         }
      }

      return true;
   }

   private boolean frenzy(Player p, boolean plus) {
      int d = (plus ? 25 : 15) * 20;
      p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, d, 1));
      p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, d, 0));
      this.frenzyUntil.put(p.getUniqueId(), now() + d * 50L);
      if (plus) {
         this.mod(p, Attribute.KNOCKBACK_RESISTANCE, "power_frenzy", 1.0, Operation.ADD_NUMBER);
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.unmod(p, "power_frenzy"), d);
      }

      p.getWorld().playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.0F, 0.8F);
      p.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, p.getLocation().add(0.0, 1.5, 0.0), 12, 0.5, 0.5, 0.5);
      p.sendActionBar(Text.c("&4⚔ Frénésie !"));
      return true;
   }

   public Location blinkTarget(Player p, Vector dir, double dist) {
      if (dir.lengthSquared() < 1.0E-6) {
         return null;
      } else {
         dir = dir.clone().normalize();
         Location eye = p.getEyeLocation();
         RayTraceResult r = p.getWorld().rayTraceBlocks(eye, dir, dist, FluidCollisionMode.NEVER, true);

         for (double d = r == null ? dist : Math.max(0.0, eye.toVector().distance(r.getHitPosition()) - 0.8); d >= 1.0; d -= 0.5) {
            Location t = p.getLocation().add(dir.clone().multiply(d));
            Block feet = t.getBlock();
            Block head = feet.getRelative(0, 1, 0);
            if (feet.isPassable() && head.isPassable() && !feet.isLiquid()) {
               t.setYaw(p.getLocation().getYaw());
               t.setPitch(p.getLocation().getPitch());
               return t;
            }
         }

         return null;
      }
   }

   private boolean blink(Player p, double dist) {
      Location t = this.blinkTarget(p, p.getEyeLocation().getDirection(), dist);
      if (t == null) {
         p.sendActionBar(Text.c("&cPas de place pour te téléporter."));
         return false;
      } else {
         World w = p.getWorld();
         w.spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0.0, 1.0, 0.0), 40, 0.3, 0.6, 0.3, 0.1);
         p.teleport(t);
         p.setFallDistance(0.0F);
         w.spawnParticle(Particle.WITCH, t.clone().add(0.0, 1.0, 0.0), 30, 0.3, 0.6, 0.3, 0.05);
         w.playSound(t, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.4F);
         return true;
      }
   }

   private boolean mist(Player p, boolean plus) {
      int d = (plus ? 8 : 5) * 20;
      p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, d, 0, false, false, true));
      p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, d, 1, false, false, true));
      this.mistUntil.put(p.getUniqueId(), now() + d * 50L);
      if (plus) {
         this.engine.heal(p, 8.0);
      }

      p.getWorld().spawnParticle(Particle.LARGE_SMOKE, p.getLocation().add(0.0, 1.0, 0.0), 40, 0.4, 0.8, 0.4, 0.02);
      p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.0F, 0.6F);
      p.sendActionBar(Text.c("&8☁ Forme de brume"));
      return true;
   }

   private boolean gust(Player p, boolean plus) {
      double r = plus ? 10.0 : 6.0;
      World w = p.getWorld();
      w.spawnParticle(Particle.GUST_EMITTER_LARGE, p.getLocation().add(0.0, 0.5, 0.0), 1);
      w.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1.0F, 0.9F);

      for (LivingEntity e : this.enemies(p, r)) {
         Vector v = e.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
         if (v.lengthSquared() < 0.01) {
            v = new Vector(1, 0, 0);
         }

         e.setVelocity(v.normalize().multiply(1.6).setY(0.7));
         e.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20, 1));
         if (plus) {
            e.damage(4.0, p);
         }
      }

      return true;
   }

   private boolean arrows(Player p, boolean plus) {
      this.arrowRain.put(p.getUniqueId(), new int[]{plus ? 12 : 8, plus ? 1 : 0});
      p.playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1.0F, 1.3F);
      p.sendActionBar(Text.c("&a➹ Ta prochaine flèche se divise en " + (plus ? 12 : 8) + " !"));
      return true;
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onShoot(EntityShootBowEvent e) {
      if (e.getEntity() instanceof Player p && e.getProjectile() instanceof Arrow main) {
         int[] rain = this.arrowRain.remove(p.getUniqueId());
         if (rain != null) {
            Vector base = main.getVelocity();

            for (int i = 0; i < rain[0] - 1; i++) {
               Vector v = base.clone().rotateAroundY((Math.random() - 0.5) * 0.5).add(new Vector(0.0, (Math.random() - 0.3) * 0.25, 0.0));
               Arrow a = p.getWorld().spawnArrow(main.getLocation(), v, (float)base.length(), 4.0F);
               a.setShooter(p);
               a.setPickupStatus(PickupStatus.DISALLOWED);
               a.setDamage(main.getDamage() * 0.6);
               if (rain[1] == 1) {
                  a.setFireTicks(200);
               }
            }

            if (rain[1] == 1) {
               main.setFireTicks(200);
            }

            p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 1.0F, 0.7F);
         }
      }
   }

   private boolean stone(Player p, boolean plus) {
      int d = (plus ? 12 : 8) * 20;
      p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, d, 2));
      this.mod(p, Attribute.KNOCKBACK_RESISTANCE, "power_stone", 1.0, Operation.ADD_NUMBER);
      this.stoneUntil.put(p.getUniqueId(), now() + d * 50L);
      if (plus) {
         this.stoneReflect.add(p.getUniqueId());
      } else {
         this.stoneReflect.remove(p.getUniqueId());
      }

      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.unmod(p, "power_stone"), d);
      p.getWorld().playSound(p.getLocation(), Sound.BLOCK_STONE_PLACE, 1.0F, 0.5F);
      p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation().add(0.0, 1.0, 0.0), 60, 0.4, 0.8, 0.4, Material.STONE.createBlockData());
      p.sendActionBar(Text.c("&7⛰ Peau de " + (plus ? "diamant" : "pierre")));
      return true;
   }

   private boolean geyser(Player p, boolean plus) {
      World w = p.getWorld();
      w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 1.0F, 0.6F);
      w.playSound(p.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 1.0F, 1.0F);

      for (LivingEntity e : this.enemies(p, 5.0)) {
         e.setVelocity(new Vector(0.0, 1.3, 0.0));
         e.damage(5.0, p);
         w.spawnParticle(Particle.SPLASH, e.getLocation(), 60, 0.3, 1.5, 0.3, 0.2);
         w.spawnParticle(Particle.BUBBLE_COLUMN_UP, e.getLocation(), 40, 0.3, 1.0, 0.3, 0.1);
      }

      w.spawnParticle(Particle.SPLASH, p.getLocation(), 80, 2.5, 0.2, 2.5, 0.2);
      if (plus) {
         this.rainUntil.put(p.getUniqueId(), now() + 30000L);
         p.setPlayerWeather(WeatherType.DOWNFALL);
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline() && !this.raining(p)) {
               p.resetPlayerWeather();
            }
         }, 605L);
      }

      return true;
   }

   private boolean rally(Player p, boolean plus) {
      double r = plus ? 25.0 : 15.0;
      Clan c = this.plugin.clans().of(p);
      int n = 0;

      for (Player o : p.getLocation().getNearbyPlayers(r)) {
         Clan oc = this.plugin.clans().of(o);
         boolean friend = o == p || c != null && oc != null && (c == oc || c.allies().contains(oc.id()));
         if (friend) {
            o.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 300, 0));
            o.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 300, 0));
            if (plus) {
               o.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 300, 0));
            }

            if (o != p) {
               n++;
               o.sendActionBar(Text.c("&e⚑ Cri de ralliement de &f" + p.getName() + " &e!"));
            }
         }
      }

      p.getWorld().playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1.2F, 1.1F);
      p.sendActionBar(Text.c("&e⚑ Cri de ralliement &7(" + n + " allié" + (n > 1 ? "s" : "") + ")"));
      return true;
   }

   private void roots(Player p) {
      World w = p.getWorld();
      w.playSound(p.getLocation(), Sound.BLOCK_ROOTS_PLACE, 1.5F, 0.5F);
      w.playSound(p.getLocation(), Sound.BLOCK_CHERRY_LEAVES_BREAK, 1.5F, 0.5F);

      for (LivingEntity e : this.enemies(p, 10.0)) {
         e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 9));
         e.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 100, 200));
         e.setVelocity(new Vector());

         for (int s = 0; s < 5; s++) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
               if (e.isValid()) {
                  w.spawnParticle(Particle.BLOCK, e.getLocation().add(0.0, 0.3, 0.0), 20, 0.3, 0.3, 0.3, Material.MANGROVE_ROOTS.createBlockData());
               }
            }, s * 20L);
         }
      }
   }

   private void tick() {
      for (UUID id : new ArrayList<>(this.ultimateUntil.keySet())) {
         Player p = Bukkit.getPlayer(id);
         String race = this.ultimateRace.get(id);
         if (p != null && !p.isDead() && this.ultimateUntil.get(id) > now()) {
            World w = p.getWorld();
            Location l = p.getLocation();
            switch (race) {
               case "infernal":
                  w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0.0, 1.0, 0.0), 12, 0.6, 0.8, 0.6, 0.02);

                  for (LivingEntity e : this.enemies(p, 4.0)) {
                     e.setFireTicks(Math.max(e.getFireTicks(), 40));
                  }
                  break;
               case "orc":
                  List<LivingEntity> es = this.enemies(p, 8.0);

                  for (LivingEntity e : es) {
                     e.damage(1.0, p);
                     this.engine.heal(p, 0.5);
                     w.spawnParticle(Particle.DAMAGE_INDICATOR, e.getLocation().add(0.0, 1.0, 0.0), 2, 0.2, 0.3, 0.2, 0.0);
                  }

                  if (!es.isEmpty()) {
                     p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 30, Math.min(2, es.size() - 1)));
                  }
                  break;
               case "vampire":
                  w.spawnParticle(Particle.SQUID_INK, l.clone().add(0.0, 1.0, 0.0), 6, 0.4, 0.6, 0.4, 0.01);
                  break;
               case "sylphe":
                  if (!p.getAllowFlight()) {
                     p.setAllowFlight(true);
                  }

                  w.spawnParticle(Particle.CLOUD, l, 4, 0.3, 0.1, 0.3, 0.02);
                  if (Bukkit.getCurrentTick() % 40 >= 10) {
                     break;
                  }

                  int n = 0;

                  for (LivingEntity e : this.enemies(p, 12.0)) {
                     if (n++ >= 3) {
                        break;
                     }

                     w.strikeLightningEffect(e.getLocation());
                     e.damage(5.0, p);
                  }
                  break;
               case "ondin":
                  w.spawnParticle(Particle.SPLASH, l.clone().add(0.0, 0.5, 0.0), 20, 1.5, 0.3, 1.5, 0.1);

                  for (LivingEntity e : this.enemies(p, 8.0)) {
                     e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1));
                  }
                  break;
               case "arcanien":
                  w.spawnParticle(Particle.ENCHANT, l.clone().add(0.0, 1.0, 0.0), 15, 0.5, 0.8, 0.5, 0.6);
                  break;
               case "nain":
                  w.spawnParticle(Particle.BLOCK, l.clone().add(0.0, 0.1, 0.0), 6, 0.6, 0.0, 0.6, Material.STONE.createBlockData());
            }
         } else {
            this.endUltimate(id, p, race);
         }
      }

      for (Player p : Bukkit.getOnlinePlayers()) {
         if (this.raining(p)) {
            p.setPlayerWeather(WeatherType.DOWNFALL);
         }
      }
   }

   private void endUltimate(UUID id, Player p, String race) {
      this.ultimateUntil.remove(id);
      this.ultimateRace.remove(id);
      if (p != null) {
         if ("sylphe".equals(race) && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
            p.setFlying(false);
            p.setAllowFlight(false);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 160, 0));
         }

         if ("nain".equals(race)) {
            this.unmod(p, "power_titan");
         }

         if (p.isOnline()) {
            p.sendActionBar(Text.c("&7Ton ultime s'achève."));
            this.pm.apply(p);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOW
   )
   public void onDamage(EntityDamageEvent e) {
      if (e.getEntity() instanceof Player p) {
         if (live(this.mistUntil, p) && e.getCause() != DamageCause.VOID) {
            e.setCancelled(true);
         } else {
            if (e instanceof EntityDamageByEntityEvent be
               && live(this.stoneUntil, p)
               && this.stoneReflect.contains(p.getUniqueId())
               && be.getDamager() instanceof LivingEntity att
               && att != p) {
               double back = e.getDamage() * 0.2;
               Bukkit.getScheduler().runTask(this.plugin, () -> {
                  if (att.isValid()) {
                     att.damage(back);
                  }
               });
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onHit(EntityDamageByEntityEvent e) {
      if (e.getDamager() instanceof Player p) {
         if (live(this.mistUntil, p)) {
            this.mistUntil.remove(p.getUniqueId());
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
         }

         if (this.active(p, "nain") && e.getCause() == DamageCause.ENTITY_ATTACK && e.getEntity() instanceof LivingEntity target) {
            double dmg = e.getFinalDamage() * 0.5;

            for (Entity o : target.getNearbyEntities(3.0, 2.0, 3.0)) {
               if (o != target && o instanceof LivingEntity le && this.engine.hostile(p, o)) {
                  Bukkit.getScheduler().runTask(this.plugin, () -> {
                     if (le.isValid()) {
                        le.damage(dmg);
                     }
                  });
               }
            }

            p.getWorld().spawnParticle(Particle.EXPLOSION, target.getLocation(), 1);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onKill(EntityDeathEvent e) {
      Player p = e.getEntity().getKiller();
      if (p != null && this.active(p, "infernal")) {
         this.engine.heal(p, 1000.0);
         this.pm.addSouls(p, e.getEntity() instanceof Player ? 50L : 2L, false);
         p.getWorld().spawnParticle(Particle.SOUL, e.getEntity().getLocation().add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.05);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onResurrect(EntityResurrectEvent e) {
      if (e.getEntity() instanceof Player p && e.isCancelled()) {
         if ("humain".equals(this.pm.of(p).race()) && this.learned(p, "ultimate") && this.left(p, "ultimate") <= 0L) {
            SkillTree.Special sp = this.special(p, "ultimate");
            e.setCancelled(false);
            this.setCooldown(p, "ultimate", sp == null ? 1800 : sp.cooldown());
            Bukkit.getScheduler()
               .runTask(
                  this.plugin,
                  () -> {
                     if (p.isOnline() && !p.isDead()) {
                        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
                        p.setHealth(Math.max(1.0, (hp == null ? 20.0 : hp.getValue()) * 0.5));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 4));
                        p.showTitle(
                           Title.title(
                              Text.c("&6✦ Élu des dieux ✦"),
                              Text.c("&7Tu te relèves !"),
                              Times.times(Duration.ofMillis(200L), Duration.ofMillis(1600L), Duration.ofMillis(500L))
                           )
                        );
                        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1.0F, 1.2F);
                     }
                  }
               );
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.clear(e.getPlayer());
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent e) {
      this.clear(e.getEntity());
   }

   private void clear(Player p) {
      UUID id = p.getUniqueId();
      BukkitTask t = this.pending.remove(id);
      if (t != null) {
         t.cancel();
      }

      if (this.ultimateUntil.containsKey(id)) {
         this.endUltimate(id, p, this.ultimateRace.get(id));
      }

      this.frenzyUntil.remove(id);
      this.mistUntil.remove(id);
      this.stoneUntil.remove(id);
      this.stoneReflect.remove(id);
      this.arrowRain.remove(id);
      if (this.rainUntil.remove(id) != null) {
         p.resetPlayerWeather();
      }

      this.unmod(p, "power_frenzy");
      this.unmod(p, "power_stone");
      this.unmod(p, "power_titan");
   }

   public void shutdown() {
      for (Player p : Bukkit.getOnlinePlayers()) {
         this.clear(p);
      }
   }

   private void mod(Player p, Attribute a, String name, double v, Operation op) {
      AttributeInstance inst = p.getAttribute(a);
      if (inst != null) {
         NamespacedKey k = new NamespacedKey(this.plugin, name);

         for (AttributeModifier m : new ArrayList<AttributeModifier>(inst.getModifiers())) {
            if (m.getKey().equals(k)) {
               inst.removeModifier(m);
            }
         }

         inst.addTransientModifier(new AttributeModifier(k, v, op, EquipmentSlotGroup.ANY));
      }
   }

   private void unmod(Player p, String name) {
      NamespacedKey k = new NamespacedKey(this.plugin, name);

      for (Attribute a : List.of(Attribute.SCALE, Attribute.MAX_HEALTH, Attribute.KNOCKBACK_RESISTANCE)) {
         AttributeInstance inst = p.getAttribute(a);
         if (inst != null) {
            for (AttributeModifier m : new ArrayList<AttributeModifier>(inst.getModifiers())) {
               if (m.getKey().equals(k)) {
                  inst.removeModifier(m);
               }
            }
         }
      }

      AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
      if (hp != null && p.getHealth() > hp.getValue()) {
         p.setHealth(hp.getValue());
      }
   }
}
