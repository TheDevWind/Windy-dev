package fr.windydev.clans.ritual;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class WeaponListener implements Listener {
   private static final String META_HOMING = "windy_homing";
   private final WindyClans plugin;
   private final Map<String, Long> cooldowns = new HashMap<>();
   private final Map<UUID, long[]> parrying = new HashMap<>();
   private final Set<UUID> felling = new HashSet<>();

   public WeaponListener(WindyClans plugin) {
      this.plugin = plugin;
   }

   private ConfigurationSection ability(ItemStack it, String type) {
      CustomItems.Def d = this.plugin.items().defOf(it);
      return d != null && d.ability() != null && type.equals(d.ability().getString("type")) ? d.ability() : null;
   }

   private boolean ready(Player p, String type, String label) {
      Long end = this.cooldowns.get(p.getUniqueId() + ":" + type);
      long left = end == null ? 0L : end - System.currentTimeMillis();
      if (left <= 0L) {
         return true;
      } else {
         p.sendActionBar(Text.c("&7" + label + " &8» &c" + String.format(Locale.ROOT, "%.1f", left / 1000.0) + " s"));
         return false;
      }
   }

   private void cooldown(Player p, String type, double seconds) {
      seconds *= 1.0 - Math.max(0.0, Math.min(0.9, this.plugin.profiles().stat(p, "ability-cooldown")));
      seconds *= Math.max(0.5, this.plugin.glyphs().echoFactor(p));
      if (this.plugin.powers().noWeaponCooldown(p)) {
         seconds = 0.0;
      }

      double reset = this.plugin.profiles().stat(p, "cd-reset");
      if (reset > 0.0 && Math.random() < reset) {
         seconds = 0.0;
         p.sendActionBar(Text.c("&d✦ Surcharge : recharge annulée !"));
      }

      this.cooldowns.put(p.getUniqueId() + ":" + type, System.currentTimeMillis() + (long)(seconds * 1000.0));
   }

   private boolean friendly(Player p, Entity e) {
      if (e == p) {
         return true;
      } else if (e instanceof Player o) {
         Clan var6 = this.plugin.clans().of(p);
         Clan b = this.plugin.clans().of(o);
         return var6 != null && b != null && (var6 == b || var6.allies().contains(b.id()));
      } else {
         return e instanceof Tameable t && p.equals(t.getOwner());
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onUse(PlayerInteractEvent e) {
      if (e.getHand() == EquipmentSlot.HAND) {
         if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (e.useItemInHand() != Result.DENY) {
               if (e.getClickedBlock() == null || !e.getClickedBlock().getType().isInteractable() || e.getPlayer().isSneaking()) {
                  Player p = e.getPlayer();
                  ItemStack hand = p.getInventory().getItemInMainHand();
                  ConfigurationSection parry = this.ability(hand, "parry");
                  if (parry != null) {
                     if (this.ready(p, "parry", "Parade")) {
                        double window = parry.getDouble("window", 0.8);
                        this.parrying.put(p.getUniqueId(), new long[]{System.currentTimeMillis() + (long)(window * 1000.0), parry.getInt("reflect", 40)});
                        this.cooldown(p, "parry", parry.getDouble("cooldown", 10.0));
                        p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0F, 1.4F);
                        p.getWorld().spawnParticle(Particle.ENCHANTED_HIT, p.getLocation().add(0.0, 1.2, 0.0), 15, 0.4, 0.4, 0.4, 0.1);
                        p.sendActionBar(Text.c("&6⛨ Parade !"));
                     }
                  } else {
                     ConfigurationSection cry = this.ability(hand, "warcry");
                     if (cry != null) {
                        if (!p.isSneaking() && cry.getBoolean("sneak", false)) {
                           return;
                        }

                        if (!this.ready(p, "warcry", "Cri de guerre")) {
                           return;
                        }

                        double radius = cry.getDouble("radius", 8.0);
                        int dur = cry.getInt("duration", 8) * 20;
                        int amp = cry.getInt("amplifier", 0);
                        int n = 0;
                        Clan c = this.plugin.clans().of(p);

                        for (Player o : p.getLocation().getNearbyPlayers(radius)) {
                           if (o == p || c != null && this.plugin.clans().of(o) == c) {
                              o.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, dur, amp, false, true, true));
                              if (o != p) {
                                 n++;
                                 o.sendActionBar(Text.c("&c⚔ Cri de guerre de &f" + p.getName() + " &c!"));
                              }
                           }
                        }

                        this.cooldown(p, "warcry", cry.getDouble("cooldown", 30.0));
                        World w = p.getWorld();
                        w.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2F, 0.9F);
                        w.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.8F, 1.3F);
                        DustOptions red = new DustOptions(Color.fromRGB(220, 60, 60), 1.4F);

                        for (int i = 0; i < 48; i++) {
                           double a = i * Math.PI * 2.0 / 48.0;
                           w.spawnParticle(
                              Particle.DUST, p.getLocation().add(Math.cos(a) * radius * 0.6, 0.3, Math.sin(a) * radius * 0.6), 1, 0.0, 0.0, 0.0, 0.0, red
                           );
                        }

                        p.sendActionBar(Text.c("&c⚔ Cri de guerre ! &7Force " + (amp + 1) + " pour toi" + (n > 0 ? " et " + n + " membre(s)" : "")));
                        e.setUseItemInHand(Result.DENY);
                     }
                  }
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onParry(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof Player p) {
         long[] st = this.parrying.get(p.getUniqueId());
         if (st != null) {
            if (System.currentTimeMillis() > st[0]) {
               this.parrying.remove(p.getUniqueId());
            } else {
               Entity src = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : e.getDamager();
               double dmg = e.getDamage();
               e.setCancelled(true);
               p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6F, 1.8F);
               p.getWorld().spawnParticle(Particle.CRIT, p.getLocation().add(0.0, 1.2, 0.0), 20, 0.4, 0.4, 0.4, 0.3);
               if (e.getDamager() instanceof Projectile prx) {
                  prx.remove();
               }

               if (src instanceof LivingEntity le && src != p && !this.friendly(p, src)) {
                  double back = dmg * st[1] / 100.0;
                  if (back > 0.0) {
                     le.damage(back, p);
                  }

                  le.setVelocity(le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0).normalize().multiply(0.5).setY(0.25));
               }

               p.sendActionBar(Text.c("&6⛨ Coup paré !"));
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onShoot(EntityShootBowEvent e) {
      if (e.getEntity() instanceof Player p && e.getProjectile() instanceof AbstractArrow arrow) {
         ConfigurationSection h = this.ability(e.getBow(), "homing");
         if (h != null) {
            double bonus = h.getDouble("damage-bonus", 0.0);
            if (bonus > 0.0) {
               arrow.setDamage(arrow.getDamage() + bonus);
            }

            if (!(e.getForce() < 0.4F)) {
               final double range = h.getDouble("range", 10.0) + 3.0 * this.plugin.glyphs().eye(e.getBow());
               final double turn = h.getDouble("turn", 0.15);
               arrow.setMetadata("windy_homing", new FixedMetadataValue(this.plugin, true));
               (new BukkitRunnable() {
                     int life = 0;
                     LivingEntity target;

                     public void run() {
                        if (arrow.isValid() && !arrow.isOnGround() && !arrow.isInBlock() && ++this.life <= 80) {
                           Vector vel = arrow.getVelocity();
                           double speed = vel.length();
                           if (speed < 0.4) {
                              this.cancel();
                           } else {
                              if (this.target == null || !this.target.isValid() || this.target.isDead()) {
                                 this.target = WeaponListener.this.findTarget(p, arrow, vel, range);
                              }

                              if (this.target != null) {
                                 Vector to = this.target
                                    .getLocation()
                                    .add(0.0, this.target.getHeight() * 0.55, 0.0)
                                    .toVector()
                                    .subtract(arrow.getLocation().toVector());
                                 if (!(to.lengthSquared() < 0.25)) {
                                    Vector nv = vel.clone().normalize().multiply(1.0 - turn).add(to.normalize().multiply(turn)).normalize().multiply(speed);
                                    arrow.setVelocity(nv);
                                    if (this.life % 2 == 0) {
                                       arrow.getWorld().spawnParticle(Particle.WITCH, arrow.getLocation(), 1, 0.0, 0.0, 0.0, 0.0);
                                    }
                                 }
                              }
                           }
                        } else {
                           this.cancel();
                        }
                     }
                  })
                  .runTaskTimer(this.plugin, 2L, 1L);
            }
         }
      }
   }

   private LivingEntity findTarget(Player shooter, Entity arrow, Vector dir, double range) {
      LivingEntity best = null;
      double bestScore = Double.MAX_VALUE;
      Vector d = dir.clone().normalize();

      for (Entity e : arrow.getNearbyEntities(range, range, range)) {
         if (e instanceof LivingEntity le
            && !(e instanceof ArmorStand)
            && !le.isDead()
            && !this.friendly(shooter, e)
            && !(e instanceof Player tp && (tp.getGameMode() == GameMode.SPECTATOR || tp.getGameMode() == GameMode.CREATIVE))
            && (
               e instanceof Monster
                  || e instanceof Player
                  || e instanceof Slime
                  || e instanceof Phantom
                  || e instanceof Ghast
                  || e instanceof Shulker
                  || e instanceof Hoglin
            )) {
            Vector to = le.getLocation().add(0.0, le.getHeight() / 2.0, 0.0).toVector().subtract(arrow.getLocation().toVector());
            double dist = to.length();
            if (!(dist > range)) {
               double cos = to.clone().normalize().dot(d);
               if (!(cos < 0.5) && (shooter.hasLineOfSight(le) || arrow.getLocation().distance(le.getLocation()) < 4.0)) {
                  double score = dist * (2.0 - cos);
                  if (score < bestScore) {
                     bestScore = score;
                     best = le;
                  }
               }
            }
         }
      }

      return best;
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onHarpoon(EntityDamageByEntityEvent e) {
      if (e.getDamager() instanceof Trident t && t.getShooter() instanceof Player p) {
         if (e.getEntity() instanceof LivingEntity target && !this.friendly(p, target)) {
            ConfigurationSection h = this.ability(t.getItemStack(), "harpoon");
            if (h != null) {
               double bonus = h.getDouble("damage-bonus", 0.0);
               if (bonus > 0.0) {
                  e.setDamage(e.getDamage() + bonus);
               }

               double strength = h.getDouble("strength", 1.0) * (1.0 + 0.15 * this.plugin.glyphs().eye(t.getItemStack()));
               Bukkit.getScheduler().runTask(this.plugin, () -> {
                  if (target.isValid() && p.isOnline() && target.getWorld() == p.getWorld()) {
                     Vector to = p.getLocation().toVector().subtract(target.getLocation().toVector());
                     double dist = to.length();
                     if (!(dist < 2.0)) {
                        Vector v = to.normalize().multiply(Math.min(strength * (0.6 + dist / 12.0), strength * 2.2));
                        v.setY(Math.max(0.35, Math.min(0.9, v.getY() + 0.35)));
                        target.setVelocity(v);
                        p.getWorld().playSound(target.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0F, 0.7F);
                        DustOptions blue = new DustOptions(Color.fromRGB(90, 200, 230), 1.0F);
                        Location a = target.getLocation().add(0.0, 1.0, 0.0);
                        Vector step = p.getLocation().add(0.0, 1.0, 0.0).toVector().subtract(a.toVector()).multiply(0.08333333333333333);

                        for (int i = 0; i < 12; i++) {
                           p.getWorld().spawnParticle(Particle.DUST, a.clone().add(step.clone().multiply(i)), 1, 0.0, 0.0, 0.0, 0.0, blue);
                        }
                     }
                  }
               });
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onChop(BlockBreakEvent e) {
      Player p = e.getPlayer();
      if (!this.felling.contains(p.getUniqueId()) && !p.isSneaking()) {
         Block start = e.getBlock();
         if (Tag.LOGS.isTagged(start.getType())) {
            ItemStack tool = p.getInventory().getItemInMainHand();
            ConfigurationSection t = this.ability(tool, "timber");
            if (t != null) {
               int max = t.getInt("max-logs", 64);
               List<Block> logs = this.tree(start, max);
               if (!logs.isEmpty()) {
                  this.felling.add(p.getUniqueId());

                  try {
                     for (Block b : logs) {
                        if (p.getInventory().getItemInMainHand().getType().isAir()) {
                           break;
                        }

                        BlockBreakEvent ev = new BlockBreakEvent(b, p);
                        this.plugin.getServer().getPluginManager().callEvent(ev);
                        if (!ev.isCancelled()) {
                           if (p.getGameMode() == GameMode.CREATIVE) {
                              b.setType(Material.AIR);
                           } else {
                              this.plugin.glyphs().breakBlock(p, tool, b);
                              p.damageItemStack(EquipmentSlot.HAND, 1);
                           }
                        }
                     }
                  } finally {
                     this.felling.remove(p.getUniqueId());
                  }
               }
            }
         }
      }
   }

   private List<Block> tree(Block start, int max) {
      List<Block> out = new ArrayList<>();
      Set<Block> seen = new HashSet<>();
      ArrayDeque<Block> queue = new ArrayDeque<>();
      queue.add(start);
      seen.add(start);
      boolean leaves = false;

      while (!queue.isEmpty() && out.size() < max) {
         Block b = queue.poll();

         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  if (dx != 0 || dy != 0 || dz != 0) {
                     Block n = b.getRelative(dx, dy, dz);
                     if (seen.add(n)) {
                        if (Tag.LOGS.isTagged(n.getType())) {
                           if (Math.abs(n.getX() - start.getX()) <= 6 && Math.abs(n.getZ() - start.getZ()) <= 6) {
                              queue.add(n);
                              out.add(n);
                           }
                        } else if (!leaves && n.getBlockData() instanceof Leaves lv && !lv.isPersistent()) {
                           leaves = true;
                        }
                     }
                  }
               }
            }
         }
      }

      if (!leaves) {
         for (Block b : out) {
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
               if (b.getRelative(f).getBlockData() instanceof Leaves lv && !lv.isPersistent()) {
                  leaves = true;
                  break;
               }
            }
         }
      }

      if (!leaves) {
         return List.of();
      } else {
         out.sort(Comparator.comparingInt(Block::getY).reversed());
         return out;
      }
   }
}
