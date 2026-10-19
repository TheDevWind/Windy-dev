package fr.windydev.clans.profile;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.Particle.DustOptions;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Villager;
import org.bukkit.entity.AbstractArrow.PickupStatus;
import org.bukkit.entity.Display.Brightness;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityPotionEffectEvent.Cause;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerFishEvent.State;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

public class SkillEngine implements Listener {
   private final WindyClans plugin;
   private final ProfileManager pm;
   private final Random rnd = new Random();
   private final Map<UUID, Long> blinkReady = new HashMap<>();
   private final Map<UUID, Long> shieldAt = new HashMap<>();
   private final Map<UUID, Long> veinReady = new HashMap<>();
   private final Map<UUID, Location> stillAt = new HashMap<>();
   private final Map<UUID, Integer> stillTicks = new HashMap<>();
   private final Set<Block> oreBreaks = new HashSet<>();
   private final Set<UUID> potionGuard = new HashSet<>();
   private int tick;

   public SkillEngine(WindyClans plugin, ProfileManager pm) {
      this.plugin = plugin;
      this.pm = pm;
   }

   private double stat(Player p, String k) {
      return this.pm.stat(p, k);
   }

   private boolean chance(double c) {
      return c > 0.0 && this.rnd.nextDouble() < c;
   }

   private static boolean survival(Player p) {
      return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
   }

   private boolean extra(Player p, String race) {
      Profile pr = this.pm.get(p.getUniqueId());
      return pr != null && race.equals(pr.race) && pr.skill(race + ".extra") > 0;
   }

   private static double maxHp(LivingEntity e) {
      AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
      return a == null ? 20.0 : a.getValue();
   }

   public boolean hostile(Player p, Entity e) {
      if (e == p || !(e instanceof LivingEntity) || e.isDead()) {
         return false;
      } else if (e instanceof Player o) {
         if (!survival(o)) {
            return false;
         } else {
            Clan a = this.plugin.clans().of(p);
            Clan b = this.plugin.clans().of(o);
            return a == null || b == null || a != b && !a.allies().contains(b.id());
         }
      } else {
         return e instanceof Tameable t && t.getOwner() != null ? false : e instanceof Enemy;
      }
   }

   public void heal(LivingEntity e, double hp) {
      if (!(hp <= 0.0) && !e.isDead()) {
         e.setHealth(Math.min(maxHp(e), e.getHealth() + hp));
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 5L);
   }

   private void tick() {
      this.tick += 5;

      for (Player p : Bukkit.getOnlinePlayers()) {
         Profile pr = this.pm.get(p.getUniqueId());
         if (pr != null && pr.race != null && !p.isDead()) {
            this.glide(p);
            if (this.tick % 10 == 0) {
               this.analyse(p);
            }

            if (this.tick % 20 == 0) {
               this.camouflage(p);
               if (pr.race.equals("ondin")) {
                  if (this.extra(p, "ondin") && p.isInWater()) {
                     p.setRemainingAir(p.getMaximumAir());
                  }

                  if (this.stat(p, "abyss-vision") > 0.0 && p.isInWater()) {
                     effect(p, PotionEffectType.NIGHT_VISION, 0, 300);
                  }
               }

               if (this.extra(p, "humain")) {
                  effect(p, PotionEffectType.HERO_OF_THE_VILLAGE, 1, 300);
               }
            }

            if (this.tick % 40 == 0) {
               this.fear(p);
               this.track(p);
            }

            if (this.tick % 100 == 0 && survival(p)) {
               double r = this.stat(p, "regen");
               if (r > 0.0 && p.getHealth() < maxHp(p)) {
                  this.heal(p, r);
               }
            }

            if (this.tick % 20 == 0) {
               double sh = this.stat(p, "arcane-shield");
               long now = System.currentTimeMillis();
               if (sh > 0.0 && now >= this.shieldAt.getOrDefault(p.getUniqueId(), 0L)) {
                  this.shieldAt.put(p.getUniqueId(), now + 60000L);
                  if (p.getAbsorptionAmount() < sh) {
                     p.setAbsorptionAmount(sh);
                     p.getWorld().spawnParticle(Particle.ENCHANT, p.getLocation().add(0.0, 1.0, 0.0), 30, 0.4, 0.6, 0.4, 0.5);
                     p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7F, 1.5F);
                  }
               }
            }
         }
      }
   }

   private static void effect(Player p, PotionEffectType t, int amp, int ticks) {
      PotionEffect cur = p.getPotionEffect(t);
      if (cur == null || cur.isAmbient() && cur.getDuration() < ticks - 60 || cur.getAmplifier() < amp) {
         p.addPotionEffect(new PotionEffect(t, ticks, amp, true, false, true));
      }
   }

   private void glide(Player p) {
      double g = this.stat(p, "glide");
      AttributeInstance inst = p.getAttribute(Attribute.GRAVITY);
      if (inst != null) {
         NamespacedKey key = new NamespacedKey(this.plugin, "skill_glide");
         boolean want = g > 0.0 && p.isSneaking() && !p.isOnGround() && !p.isFlying() && p.getVelocity().getY() < 0.0 && !p.isInWater();
         AttributeModifier cur = null;

         for (AttributeModifier m : inst.getModifiers()) {
            if (m.getKey().equals(key)) {
               cur = m;
            }
         }

         if (want && cur == null) {
            inst.addTransientModifier(new AttributeModifier(key, -Math.min(0.8, g), Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
         } else if (!want && cur != null) {
            inst.removeModifier(cur);
         }

         if (want) {
            p.setFallDistance(Math.min(p.getFallDistance(), 3.0F));
         }
      }
   }

   private void analyse(Player p) {
      if (this.extra(p, "arcanien")) {
         RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 30.0, 0.4, e -> e instanceof Player && e != p);
         if (r != null && r.getHitEntity() instanceof Player o) {
            Profile var6 = this.pm.of(o);
            RaceDef race = this.pm.race(var6);
            p.sendActionBar(
               Text.c(
                  "&d\ud83d\udd0d &f"
                     + o.getName()
                     + " &8| &c❤ "
                     + Math.round(o.getHealth()) / 2.0
                     + "&7/"
                     + Math.round(maxHp(o)) / 2.0
                     + " &8| "
                     + (race == null ? "&7sans race" : race.color() + this.pm.rankName(var6))
                     + " &8| &7niv. &f"
                     + var6.level
               )
            );
         }
      }
   }

   private void camouflage(Player p) {
      double c = this.stat(p, "camouflage");
      if (!(c <= 0.0)) {
         Location l = p.getLocation();
         Location prev = this.stillAt.put(p.getUniqueId(), l);
         boolean still = prev != null && prev.getWorld() == l.getWorld() && prev.distanceSquared(l) < 0.01 && p.isSneaking();
         int t = still ? this.stillTicks.merge(p.getUniqueId(), 20, Integer::sum) : 0;
         if (!still) {
            this.stillTicks.remove(p.getUniqueId());
         }

         boolean foliage = false;

         for (Block b : List.of(
            l.getBlock(),
            l.getBlock().getRelative(0, 1, 0),
            l.getBlock().getRelative(1, 0, 0),
            l.getBlock().getRelative(-1, 0, 0),
            l.getBlock().getRelative(0, 0, 1),
            l.getBlock().getRelative(0, 0, -1)
         )) {
            if (Tag.LEAVES.isTagged(b.getType())
               || b.getType() == Material.TALL_GRASS
               || b.getType() == Material.LARGE_FERN
               || b.getType() == Material.SHORT_GRASS
               || b.getType() == Material.FERN
               || b.getType() == Material.AZALEA) {
               foliage = true;
               break;
            }
         }

         if (still && foliage && t >= (6.0 - Math.min(5.0, c)) * 20.0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, true, false, true));
            if (t == (6 - Math.min(5, (int)c)) * 20) {
               p.sendActionBar(Text.c("&a\ud83c\udf43 Camouflé"));
            }
         }
      }
   }

   private void fear(Player p) {
      double f = this.stat(p, "fear-aura");
      if (!(f <= 0.0) && survival(p)) {
         for (Entity e : p.getNearbyEntities(6.0, 3.0, 6.0)) {
            if (e instanceof LivingEntity le && this.hostile(p, e) && this.chance(f)) {
               le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, false, true, true));
               le.getWorld().spawnParticle(Particle.SMOKE, le.getLocation().add(0.0, 1.8, 0.0), 6, 0.2, 0.1, 0.2, 0.01);
            }
         }
      }
   }

   private void track(Player p) {
      double r = this.stat(p, "track");
      if (!(r <= 0.0)) {
         DustOptions red = new DustOptions(Color.fromRGB(200, 20, 30), 1.3F);

         for (Player o : p.getLocation().getNearbyPlayers(r)) {
            if (o != p && this.hostile(p, o) && !(o.getHealth() > maxHp(o) * 0.5)) {
               p.spawnParticle(Particle.DUST, o.getLocation().add(0.0, 1.1, 0.0), 12, 0.3, 0.6, 0.3, 0.0, red);
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onHit(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof LivingEntity target) {
         Entity d = e.getDamager();
         if (d instanceof Player p && e.getCause() == DamageCause.ENTITY_ATTACK) {
            double mult = 1.0 + this.stat(p, "damage");
            if (p.getHealth() <= maxHp(p) * 0.5) {
               mult += this.stat(p, "rage");
            }

            if (target.getHealth() <= maxHp(target) * 0.3) {
               mult += this.stat(p, "execute");
            }

            if (target.getFireTicks() > 0) {
               mult += this.stat(p, "burning-damage");
            }

            boolean air = !p.isOnGround() && p.getFallDistance() > 0.0F;
            if (air) {
               mult += this.stat(p, "dive-damage");
            }

            if (this.plugin.items().defOf(p.getInventory().getItemInMainHand()) != null) {
               mult += this.stat(p, "rune-damage");
            }

            if (this.chance(this.stat(p, "crit")) || air && this.chance(this.stat(p, "air-crit"))) {
               mult += 0.5;
               target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0.0, 1.0, 0.0), 15, 0.3, 0.4, 0.3, 0.2);
               p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8F, 1.1F);
            }

            e.setDamage(Math.max(0.0, e.getDamage() * mult));
            if (this.chance(this.stat(p, "wither-on-hit"))) {
               target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
            }

            if (this.chance(this.stat(p, "stun-on-hit"))) {
               target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 3));
               target.getWorld().spawnParticle(Particle.WAX_OFF, target.getEyeLocation().add(0.0, 0.4, 0.0), 10, 0.3, 0.1, 0.3, 0.5);
            }

            double kb = this.stat(p, "knockback-on-hit");
            if (kb > 0.0) {
               Bukkit.getScheduler().runTask(this.plugin, () -> {
                  if (target.isValid()) {
                     target.setVelocity(target.getVelocity().add(p.getLocation().getDirection().setY(0).normalize().multiply(kb).setY(0.1)));
                  }
               });
            }

            if (target instanceof Player tp && tp.isBlocking() && this.chance(this.stat(p, "shield-break"))) {
               tp.setCooldown(Material.SHIELD, 100);
               tp.clearActiveItem();
               tp.getWorld().playSound(tp.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0F, 1.0F);
            }

            if (this.chance(this.stat(p, "armor-break")) && target.getEquipment() != null) {
               for (ItemStack a : target.getEquipment().getArmorContents()) {
                  this.wear(a, 3);
               }
            }
         } else if (d instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            double multx = 1.0 + this.stat(p, "ranged-damage") + this.stat(p, "armor-pierce");
            if (proj instanceof AbstractArrow && !(proj instanceof Trident) && this.chance(this.stat(p, "bow-crit"))) {
               multx += 0.5;
               target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0.0, 1.0, 0.0), 15, 0.3, 0.4, 0.3, 0.2);
            }

            e.setDamage(Math.max(0.0, e.getDamage() * multx));
            if (this.chance(this.stat(p, "hunter-mark"))) {
               target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0));
            }

            if (proj instanceof Trident && this.chance(this.stat(p, "harpoon"))) {
               target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            }
         } else {
            if (d instanceof Tameable t && t.getOwner() instanceof Player owner && owner.isOnline()) {
               double f = this.stat(owner, "tame-strength");
               if (f > 0.0) {
                  e.setDamage(e.getDamage() * (1.0 + f));
               }
            }
         }
      }
   }

   private void wear(ItemStack it, int dmg) {
      if (it != null && it.getItemMeta() instanceof Damageable dm && it.getType().getMaxDurability() > 0) {
         dm.setDamage(Math.min(it.getType().getMaxDurability() - 1, dm.getDamage() + dmg));
         it.setItemMeta(dm);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onDefend(EntityDamageEvent e) {
      if (e.getEntity() instanceof Player p && survival(p)) {
         DamageCause cause = e.getCause();
         if (cause == DamageCause.LAVA && this.extra(p, "infernal")) {
            e.setCancelled(true);
         } else if (cause == DamageCause.FALL && this.extra(p, "elfe")) {
            e.setCancelled(true);
         } else {
            double red = 0.0;
            switch (cause) {
               case FIRE:
               case FIRE_TICK:
               case LAVA:
               case HOT_FLOOR:
                  red = this.stat(p, "fire-reduction");
                  break;
               case BLOCK_EXPLOSION:
               case ENTITY_EXPLOSION:
                  red = this.stat(p, "explosion-reduction") + this.stat(p, "fire-reduction") * 0.5;
                  break;
               case MAGIC:
               case POISON:
               case WITHER:
               case DRAGON_BREATH:
               case SONIC_BOOM:
                  red = this.stat(p, "magic-reduction");
            }

            if (e instanceof EntityDamageByEntityEvent be
               && (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.PROJECTILE || cause == DamageCause.ENTITY_SWEEP_ATTACK)) {
               if (this.chance(this.stat(p, "dodge"))) {
                  e.setCancelled(true);
                  p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0.0, 1.0, 0.0), 8, 0.3, 0.4, 0.3, 0.02);
                  p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.6F, 1.8F);
                  p.sendActionBar(Text.c("&b✦ Esquive"));
                  return;
               }

               if (cause == DamageCause.ENTITY_ATTACK && be.getDamager() instanceof LivingEntity att && this.extra(p, "infernal")) {
                  att.setFireTicks(Math.max(att.getFireTicks(), 60));
               }
            }

            if (this.extra(p, "orc") && p.getHealth() <= maxHp(p) * 0.3) {
               red += 0.15;
            }

            if (red > 0.0) {
               e.setDamage(e.getDamage() * (1.0 - Math.min(0.9, red)));
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onHurt(EntityDamageEvent e) {
      if (e.getEntity() instanceof Player p && survival(p)) {
         double c = this.stat(p, "escape-blink");
         if (!(c <= 0.0)) {
            double after = p.getHealth() - e.getFinalDamage();
            if (!(after <= 0.0) && !(after > maxHp(p) * 0.2)) {
               long now = System.currentTimeMillis();
               if (now >= this.blinkReady.getOrDefault(p.getUniqueId(), 0L) && this.chance(c)) {
                  this.blinkReady.put(p.getUniqueId(), now + 60000L);
                  Bukkit.getScheduler().runTask(this.plugin, () -> {
                     if (p.isOnline() && !p.isDead()) {
                        Location safe = this.plugin.powers().blinkTarget(p, p.getLocation().getDirection().multiply(-1).setY(0), 10.0);
                        if (safe != null) {
                           p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0.0, 1.0, 0.0), 40, 0.3, 0.6, 0.3, 0.5);
                           p.teleport(safe);
                           p.getWorld().playSound(safe, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.2F);
                           p.sendActionBar(Text.c("&d✦ Repli !"));
                        }
                     }
                  });
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onDamageDone(EntityDamageByEntityEvent e) {
      Player p = e.getDamager() instanceof Player pl ? pl : (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl2 ? pl2 : null);
      if (p != null && e.getEntity() instanceof LivingEntity) {
         double ls = this.stat(p, "lifesteal");
         if (this.pm.night(p)) {
            ls += this.stat(p, "night-lifesteal");
         }

         if (this.plugin.powers().active(p, "vampire")) {
            ls *= 2.0;
         }

         if (!(ls <= 0.0)) {
            double gain = e.getFinalDamage() * ls;
            double missing = maxHp(p) - p.getHealth();
            this.heal(p, gain);
            double over = gain - missing;
            double br = this.stat(p, "blood-reserve");
            if (over > 0.0 && br > 0.0) {
               p.setAbsorptionAmount(Math.min(8.0, p.getAbsorptionAmount() + over * br));
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onKill(EntityDeathEvent e) {
      LivingEntity dead = e.getEntity();
      Player p = dead.getKiller();
      if (p != null && p != dead && (this.hostile(p, dead) || dead instanceof Player)) {
         this.heal(p, this.stat(p, "heal-on-kill"));
         double ab = this.stat(p, "absorption-on-kill");
         if (ab > 0.0) {
            p.setAbsorptionAmount(Math.min(8.0, p.getAbsorptionAmount() + ab));
         }

         double food = this.stat(p, "kill-food");
         if (food > 0.0) {
            p.setFoodLevel(Math.min(20, p.getFoodLevel() + (int)Math.round(food)));
            p.setSaturation(Math.min(20.0F, p.getSaturation() + (float)food));
         }

         if (this.chance(this.stat(p, "detonate"))) {
            Location l = dead.getLocation();
            l.getWorld().spawnParticle(Particle.EXPLOSION, l, 1);
            l.getWorld().spawnParticle(Particle.FLAME, l.clone().add(0.0, 0.5, 0.0), 40, 1.0, 0.5, 1.0, 0.08);
            l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 0.8F, 1.3F);

            for (Entity o : l.getWorld().getNearbyEntities(l, 3.0, 2.0, 3.0)) {
               if (o instanceof LivingEntity le && this.hostile(p, o)) {
                  le.damage(4.0, p);
                  le.setFireTicks(60);
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onFood(FoodLevelChangeEvent e) {
      if (e.getEntity() instanceof Player p && e.getItem() != null) {
         double b = this.stat(p, "food-bonus");
         int delta = e.getFoodLevel() - p.getFoodLevel();
         if (b > 0.0 && delta > 0) {
            e.setFoodLevel(Math.min(20, p.getFoodLevel() + (int)Math.round(delta * (1.0 + b))));
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onExhaust(EntityExhaustionEvent e) {
      if (e.getEntity() instanceof Player p) {
         double r = this.stat(p, "hunger-resist") + this.stat(p, "endurance");
         if (r > 0.0) {
            e.setExhaustion((float)(e.getExhaustion() * (1.0 - Math.min(0.9, r))));
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onEat(PlayerItemConsumeEvent e) {
      Player p = e.getPlayer();
      if (!(this.stat(p, "hunger-resist") <= 0.0) && e.getItem().getType().isEdible()) {
         Bukkit.getScheduler().runTask(this.plugin, () -> {
            p.removePotionEffect(PotionEffectType.HUNGER);
            p.removePotionEffect(PotionEffectType.POISON);
         });
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPotion(EntityPotionEffectEvent e) {
      if (e.getEntity() instanceof Player p && e.getNewEffect() != null && !this.potionGuard.contains(p.getUniqueId())) {
         Cause c = e.getCause();
         if (c == Cause.POTION_DRINK || c == Cause.POTION_SPLASH || c == Cause.AREA_EFFECT_CLOUD) {
            double f = this.stat(p, "potion-duration");
            PotionEffect ne = e.getNewEffect();
            if (!(f <= 0.0) && ne.getDuration() > 1 && !ne.isInfinite()) {
               e.setCancelled(true);
               this.potionGuard.add(p.getUniqueId());

               try {
                  p.addPotionEffect(
                     new PotionEffect(
                        ne.getType(), (int)Math.round(ne.getDuration() * (1.0 + f)), ne.getAmplifier(), ne.isAmbient(), ne.hasParticles(), ne.hasIcon()
                     )
                  );
               } finally {
                  this.potionGuard.remove(p.getUniqueId());
               }
            }
         }
      }
   }

   private static boolean ore(Material m) {
      String n = m.name();
      return n.endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onBreak(BlockBreakEvent e) {
      Player p = e.getPlayer();
      Block b = e.getBlock();
      if (survival(p)) {
         if (ore(b.getType())
            && !this.pm.isPlaced(b)
            && !p.getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH)
            && this.chance(this.stat(p, "ore-double"))) {
            this.oreBreaks.add(b);
         }

         if (b.getBlockData() instanceof Ageable ag
            && ag.getAge() >= ag.getMaximumAge()
            && b.getType() != Material.SUGAR_CANE
            && b.getType() != Material.CACTUS
            && b.getType() != Material.KELP
            && this.chance(this.stat(p, "crop-bonus"))) {
            this.oreBreaks.add(b);
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onDrops(BlockDropItemEvent e) {
      if (this.oreBreaks.remove(e.getBlock())) {
         List<ItemStack> extra = new ArrayList<>();

         for (Item it : e.getItems()) {
            extra.add(it.getItemStack().clone());
         }

         for (ItemStack it : extra) {
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), it);
         }

         e.getBlock().getWorld().spawnParticle(Particle.HAPPY_VILLAGER, e.getBlock().getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onToolDamage(PlayerItemDamageEvent e) {
      if (this.chance(this.stat(e.getPlayer(), "tool-save"))) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onAnvil(PrepareAnvilEvent e) {
      if (e.getView().getPlayer() instanceof Player p) {
         double d = this.stat(p, "anvil-discount");
         if (!(d <= 0.0)) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
               int cost = e.getInventory().getRepairCost();
               if (cost > 1) {
                  e.getInventory().setRepairCost(Math.max(1, (int)Math.round(cost * (1.0 - Math.min(0.9, d)))));
               }
            });
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onTrade(InventoryOpenEvent e) {
      if (e.getPlayer() instanceof Player p && e.getInventory() instanceof MerchantInventory mi) {
         double d = this.stat(p, "trade-discount");
         if (!(d <= 0.0)) {
            Merchant m = mi.getMerchant();
            if (m instanceof Villager) {
               for (MerchantRecipe r : m.getRecipes()) {
                  if (!r.getIngredients().isEmpty()) {
                     int base = ((ItemStack)r.getIngredients().get(0)).getAmount();
                     r.setSpecialPrice(r.getSpecialPrice() - (int)Math.floor(base * Math.min(0.6, d)));
                  }
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onFish(PlayerFishEvent e) {
      if (e.getState() == State.CAUGHT_FISH && e.getCaught() instanceof Item it) {
         if (this.chance(this.stat(e.getPlayer(), "fishing-loot"))) {
            e.getPlayer()
               .getInventory()
               .addItem(new ItemStack[]{it.getItemStack().clone()})
               .values()
               .forEach(r -> e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(), r));
            e.getPlayer().sendActionBar(Text.c("&b\ud83c\udfa3 Double prise !"));
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onShoot(EntityShootBowEvent e) {
      if (e.getEntity() instanceof Player p && e.getProjectile() instanceof AbstractArrow arrow) {
         double sp = this.stat(p, "arrow-speed");
         if (sp > 0.0) {
            arrow.setVelocity(arrow.getVelocity().multiply(1.0 + sp));
         }

         if (this.chance(this.stat(p, "arrow-pierce"))) {
            arrow.setPierceLevel(Math.min(127, arrow.getPierceLevel() + 1));
         }

         if (e.shouldConsumeItem() && this.chance(this.stat(p, "arrow-save"))) {
            e.setConsumeItem(false);
            arrow.setPickupStatus(PickupStatus.CREATIVE_ONLY);
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onTridentHit(ProjectileHitEvent e) {
      if (e.getEntity() instanceof Trident t && t.getShooter() instanceof Player p && p.isOnline()) {
         if (!t.getItemStack().containsEnchantment(Enchantment.LOYALTY) && this.chance(this.stat(p, "trident-return"))) {
            if (t.getPickupStatus() == PickupStatus.ALLOWED) {
               ItemStack it = t.getItemStack().clone();
               Bukkit.getScheduler().runTask(this.plugin, () -> {
                  if (t.isValid()) {
                     t.remove();
                     p.getInventory().addItem(new ItemStack[]{it}).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
                     p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1.0F, 1.2F);
                  }
               });
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onSculk(BlockReceiveGameEvent e) {
      if (e.getEntity() instanceof Player p && this.extra(p, "elfe")) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onSneak(PlayerToggleSneakEvent e) {
      Player p = e.getPlayer();
      if (e.isSneaking() && this.extra(p, "nain")) {
         long now = System.currentTimeMillis();
         if (now >= this.veinReady.getOrDefault(p.getUniqueId(), 0L)) {
            this.veinReady.put(p.getUniqueId(), now + 20000L);
            Block c = p.getLocation().getBlock();
            List<BlockDisplay> shown = new ArrayList<>();

            for (int x = -8; x <= 8; x++) {
               for (int y = -8; y <= 8; y++) {
                  for (int z = -8; z <= 8 && shown.size() < 40; z++) {
                     Block b = c.getRelative(x, y, z);
                     Material m = b.getType();
                     if (m.name().contains("DIAMOND_ORE")
                        || m.name().contains("EMERALD_ORE")
                        || m.name().contains("GOLD_ORE")
                        || m == Material.ANCIENT_DEBRIS
                        || m.name().contains("LAPIS_ORE")) {
                        BlockDisplay d = (BlockDisplay)b.getWorld()
                           .spawn(
                              b.getLocation(),
                              BlockDisplay.class,
                              bd -> {
                                 bd.setBlock(m.createBlockData());
                                 bd.setGlowing(true);
                                 bd.setGlowColorOverride(
                                    m == Material.ANCIENT_DEBRIS
                                       ? Color.fromRGB(160, 90, 60)
                                       : (
                                          m.name().contains("DIAMOND")
                                             ? Color.AQUA
                                             : (m.name().contains("EMERALD") ? Color.LIME : (m.name().contains("GOLD") ? Color.YELLOW : Color.BLUE))
                                       )
                                 );
                                 bd.setVisibleByDefault(false);
                                 bd.setPersistent(false);
                                 bd.setBrightness(new Brightness(15, 15));
                              }
                           );
                        p.showEntity(this.plugin, d);
                        shown.add(d);
                     }
                  }
               }
            }

            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8F, 0.7F);
            p.sendActionBar(Text.c(shown.isEmpty() ? "&6⛏ Aucun filon rare à proximité" : "&6⛏ " + shown.size() + " minerai(s) rare(s) repéré(s)"));
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> shown.forEach(Entity::remove), 100L);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onScroll(PlayerInteractEvent e) {
      if (e.getHand() == EquipmentSlot.HAND) {
         if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player p = e.getPlayer();
            ItemStack it = p.getInventory().getItemInMainHand();
            if (this.plugin.items().matches(it, this.pm.tree().resetItem())) {
               e.setCancelled(true);
               Profile pr = this.pm.of(p);
               if (pr.skills.isEmpty()) {
                  this.plugin.msg(p, "&7Tu n'as encore dépensé aucun point.");
               } else if (!p.isSneaking()) {
                  this.plugin.msg(p, "&7Accroupis-toi et fais clic droit pour &drendre tous tes points &7(" + this.pm.tree().spent(pr) + " points).");
               } else {
                  it.setAmount(it.getAmount() - 1);
                  int spent = this.pm.tree().spent(pr);
                  this.pm.resetSkills(pr);
                  p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 0.6F);
                  p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0F, 0.8F);
                  p.getWorld().spawnParticle(Particle.ENCHANT, p.getLocation().add(0.0, 1.5, 0.0), 60, 0.5, 0.6, 0.5, 1.0);
                  this.plugin.msg(p, "&d✦ Oubli : &f" + spent + " points &7te sont rendus. &f/competences");
               }
            }
         }
      }
   }

   public void quit(Player p) {
      this.stillAt.remove(p.getUniqueId());
      this.stillTicks.remove(p.getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.quit(e.getPlayer());
   }

   public boolean ingredientSaved(Player p) {
      return p != null && this.chance(this.stat(p, "ingredient-save"));
   }
}
