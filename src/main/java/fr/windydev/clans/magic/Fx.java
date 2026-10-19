package fr.windydev.clans.magic;

import fr.windydev.clans.WindyClans;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Effets visuels de la magie : projectiles et débris en 3D (entités d'affichage, visibles par tous),
 * cercles runiques et marques au sol (modèles du pack, montrés seulement aux joueurs qui l'ont),
 * chiffres de dégâts flottants. Rien n'est sauvegardé dans le monde.
 */
public final class Fx {
   private static final Display.Brightness FULL = new Display.Brightness(15, 15);
   /** custom_model_data (sur PAPER) des textures plates du pack */
   public static final int CIRCLE_ARCANE = 8101, CIRCLE_GIVRE = 8102, CIRCLE_FOUDRE = 8103, CIRCLE_FEU = 8104, CIRCLE_OMBRE = 8105,
      CIRCLE_EGIDE = 8106, DECAL_GIVRE = 8107, DECAL_CRATERE = 8108;

   private final WindyClans plugin;
   private final Set<Entity> live = new HashSet<>();
   boolean displays = true;
   boolean numbers = true;

   /** Texture plate posée au sol, qui tourne et peut changer de taille. */
   public final class Decal {
      final ItemDisplay e;
      float size;
      float angle;
      final float spin;
      int task = -1;

      Decal(ItemDisplay e, float size, float spin) {
         this.e = e;
         this.size = size;
         this.spin = spin;
      }

      void apply(int interp) {
         if (this.e.isValid()) {
            this.e.setInterpolationDelay(0);
            this.e.setInterpolationDuration(interp);
            this.e.setTransformation(flat(this.size, this.angle));
         }
      }

      public void size(float s) {
         this.size = s;
      }

      public void move(Location ground) {
         if (this.e.isValid()) {
            Location l = ground.clone();
            l.setYaw(0.0F);
            l.setPitch(0.0F);
            this.e.teleport(l);
         }
      }

      public void remove() {
         if (this.task != -1) {
            Bukkit.getScheduler().cancelTask(this.task);
            this.task = -1;
         }

         Fx.this.kill(this.e);
      }
   }

   public Fx(WindyClans plugin) {
      this.plugin = plugin;
   }

   // ------------------------------------------------------------------ base

   private <T extends Display> T spawn(Location l, Class<T> type, int life, Consumer<T> init) {
      Location at = l.clone();
      at.setYaw(0.0F);
      at.setPitch(0.0F);
      T d = at.getWorld().spawn(at, type, e -> {
         e.setPersistent(false);
         e.setBrightness(FULL);
         e.setShadowRadius(0.0F);
         init.accept(e);
      });
      this.live.add(d);
      if (life > 0) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.kill(d), life);
      }

      return d;
   }

   public void kill(Entity e) {
      if (e != null) {
         this.live.remove(e);
         if (e.isValid()) {
            e.remove();
         }
      }
   }

   public void shutdown() {
      for (Entity e : new ArrayList<>(this.live)) {
         if (e.isValid()) {
            e.remove();
         }
      }

      this.live.clear();
   }

   /** Cube centré sur l'entité, tourné par rot. */
   static Transformation cube(float sx, float sy, float sz, Quaternionf rot) {
      Vector3f half = rot.transform(new Vector3f(sx / 2.0F, sy / 2.0F, sz / 2.0F));
      return new Transformation(new Vector3f(-half.x, -half.y, -half.z), new Quaternionf(rot), new Vector3f(sx, sy, sz), new Quaternionf());
   }

   /** Colonne dont la base est centrée sur l'entité (pics de glace). */
   static Transformation column(float w, float h, Quaternionf rot) {
      Vector3f half = rot.transform(new Vector3f(w / 2.0F, 0.0F, w / 2.0F));
      return new Transformation(new Vector3f(-half.x, -half.y, -half.z), new Quaternionf(rot), new Vector3f(w, h, w), new Quaternionf());
   }

   static Transformation flat(float size, float angle) {
      Quaternionf q = new Quaternionf().rotateY(angle).rotateX((float)(Math.PI / 2));
      return new Transformation(new Vector3f(), q, new Vector3f(size, size, 0.2F), new Quaternionf());
   }

   static Quaternionf randomRot() {
      ThreadLocalRandom r = ThreadLocalRandom.current();
      return new Quaternionf().rotateXYZ(r.nextFloat() * 6.28F, r.nextFloat() * 6.28F, r.nextFloat() * 6.28F);
   }

   // ------------------------------------------------------------------ volumes

   /** Projectile : un bloc centré, à déplacer avec {@link #move}. */
   public BlockDisplay orb(Location center, Material m, float size) {
      if (!this.displays) {
         return null;
      }

      return this.spawn(center, BlockDisplay.class, 400, e -> {
         e.setBlock(m.createBlockData());
         e.setTransformation(cube(size, size, size, randomRot()));
         e.setTeleportDuration(1);
         e.setViewRange(1.5F);
      });
   }

   public void move(BlockDisplay d, Location to, float size, float angle) {
      if (d != null && d.isValid()) {
         Location l = to.clone();
         l.setYaw(0.0F);
         l.setPitch(0.0F);
         d.teleport(l);
         d.setInterpolationDelay(0);
         d.setInterpolationDuration(1);
         d.setTransformation(cube(size, size, size, new Quaternionf().rotateXYZ(angle, angle * 0.7F, angle * 0.4F)));
      }
   }

   /** Éclats projetés depuis un point, qui rétrécissent en vol. */
   public void burst(Location c, int count, float spread, float size, int life, Material... mats) {
      if (!this.displays || mats.length == 0) {
         return;
      }

      ThreadLocalRandom r = ThreadLocalRandom.current();
      List<BlockDisplay> all = new ArrayList<>();
      List<Vector3f> dirs = new ArrayList<>();
      for (int i = 0; i < count; i++) {
         float s = size * (0.6F + r.nextFloat() * 0.8F);
         Material m = mats[r.nextInt(mats.length)];
         all.add(this.spawn(c, BlockDisplay.class, life + 3, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(cube(s, s, s, randomRot()));
         }));
         Vector3f d = new Vector3f(r.nextFloat() - 0.5F, r.nextFloat() * 0.9F + 0.1F, r.nextFloat() - 0.5F);
         if (d.lengthSquared() < 1.0E-4F) {
            d.set(0.0F, 1.0F, 0.0F);
         }

         dirs.add(d.normalize().mul(spread * (0.5F + r.nextFloat() * 0.5F)));
      }

      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         for (int i = 0; i < all.size(); i++) {
            BlockDisplay e = all.get(i);
            if (e.isValid()) {
               e.setInterpolationDelay(0);
               e.setInterpolationDuration(life);
               e.setTransformation(new Transformation(dirs.get(i), randomRot(), new Vector3f(0.02F, 0.02F, 0.02F), new Quaternionf()));
            }
         }
      }, 2L);
   }

   /** Trait droit entre deux points (éclair). */
   public void beam(Location a, Location b, Material m, float thick, int life) {
      if (!this.displays) {
         return;
      }

      Vector3f dir = new Vector3f((float)(b.getX() - a.getX()), (float)(b.getY() - a.getY()), (float)(b.getZ() - a.getZ()));
      float len = dir.length();
      if (len < 0.05F) {
         return;
      }

      Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0.0F, 0.0F, 1.0F), dir.normalize());
      Vector3f half = rot.transform(new Vector3f(thick / 2.0F, thick / 2.0F, 0.0F));
      this.spawn(a, BlockDisplay.class, life, e -> {
         e.setBlock(m.createBlockData());
         e.setTransformation(new Transformation(new Vector3f(-half.x, -half.y, -half.z), rot, new Vector3f(thick, thick, len), new Quaternionf()));
         e.setViewRange(1.5F);
      });
   }

   /** Éclair en zigzag de a vers b. */
   public void bolt(Location a, Location b, float thick, int life) {
      World w = a.getWorld();
      double len = a.distance(b);
      int seg = Math.max(2, Math.min(7, (int)(len / 1.6)));
      ThreadLocalRandom r = ThreadLocalRandom.current();
      Location prev = a.clone();
      for (int i = 1; i <= seg; i++) {
         double t = (double)i / seg;
         Location next = a.clone().add((b.getX() - a.getX()) * t, (b.getY() - a.getY()) * t, (b.getZ() - a.getZ()) * t);
         if (i < seg) {
            double j = Math.min(0.7, len * 0.12);
            next.add((r.nextDouble() - 0.5) * 2 * j, (r.nextDouble() - 0.5) * 2 * j, (r.nextDouble() - 0.5) * 2 * j);
         }

         this.beam(prev, next, Material.WHITE_CONCRETE, thick, life);
         this.beam(prev, next, Material.YELLOW_STAINED_GLASS, thick * 2.6F, life + 1);
         w.spawnParticle(Particle.ELECTRIC_SPARK, next, 3, 0.1, 0.1, 0.1, 0.05);
         prev = next;
      }
   }

   /** Pics qui sortent du sol, restent, puis rentrent. */
   public void spikes(Location c, double radius, int count, int stay, Material... mats) {
      if (!this.displays) {
         return;
      }

      ThreadLocalRandom r = ThreadLocalRandom.current();
      for (int i = 0; i < count; i++) {
         double a = r.nextDouble() * Math.PI * 2;
         double d = radius * (0.35 + 0.65 * Math.sqrt(r.nextDouble()));
         Location g = ground(c.clone().add(Math.cos(a) * d, 0.0, Math.sin(a) * d), 3);
         if (g == null) {
            continue;
         }

         float w = 0.18F + r.nextFloat() * 0.22F;
         float h = 0.6F + r.nextFloat() * 1.1F;
         // penché vers l'extérieur
         Quaternionf rot = new Quaternionf().rotateY((float)-a).rotateZ(-(0.15F + r.nextFloat() * 0.35F)).rotateY(r.nextFloat() * 3.0F);
         Material m = mats[r.nextInt(mats.length)];
         BlockDisplay e = this.spawn(g, BlockDisplay.class, stay + 12, x -> {
            x.setBlock(m.createBlockData());
            x.setTransformation(column(w, 0.01F, rot));
         });
         int delay = 1 + (int)(d / radius * 4);
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (e.isValid()) {
               e.setInterpolationDelay(0);
               e.setInterpolationDuration(3);
               e.setTransformation(column(w, h, rot));
            }
         }, delay + 1L);
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (e.isValid()) {
               e.setInterpolationDelay(0);
               e.setInterpolationDuration(8);
               e.setTransformation(column(w, 0.01F, rot));
            }
         }, stay);
      }
   }

   /** Objet (bouclier...) affiché tel quel, à déplacer soi-même. */
   public ItemDisplay item(Location l, ItemStack it, float scale, int life) {
      if (!this.displays) {
         return null;
      }

      return this.spawn(l, ItemDisplay.class, life, e -> {
         e.setItemStack(it);
         e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
         e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
         e.setTeleportDuration(2);
      });
   }

   // ------------------------------------------------------------------ textures plates (pack)

   /** Sol sous (ou juste au-dessus de) la position, null si rien à portée. */
   public static Location ground(Location l, int range) {
      Block b = l.getBlock();
      for (int i = 0; i < range && !b.isPassable(); i++) {
         b = b.getRelative(0, 1, 0);
      }

      if (!b.isPassable()) {
         return null;
      }

      for (int i = 0; i <= range + 1; i++) {
         Block under = b.getRelative(0, -1, 0);
         if (!under.isPassable()) {
            double top = under.getBoundingBox().getMaxY();
            return new Location(l.getWorld(), l.getX(), (top > under.getY() ? top : under.getY() + 1.0) + 0.03, l.getZ());
         }

         b = under;
      }

      return null;
   }

   /** Cercle ou marque au sol ; visible seulement avec le pack. life <= 0 : à retirer soi-même. */
   public Decal decal(Location at, int model, float size, int life, float spin) {
      if (!this.displays) {
         return null;
      }

      Location g = ground(at, 4);
      if (g == null) {
         g = at.clone().add(0.0, 0.05, 0.0);
      }

      ItemStack it = new ItemStack(Material.PAPER);
      ItemMeta im = it.getItemMeta();
      im.setCustomModelData(model);
      it.setItemMeta(im);
      Location spot = g;
      ItemDisplay e = this.spawn(g, ItemDisplay.class, 0, x -> {
         x.setItemStack(it);
         x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
         x.setTransformation(flat(0.05F, 0.0F));
         x.setVisibleByDefault(false);
         x.setTeleportDuration(2);
         x.setViewRange(1.2F);
      });
      for (Player p : spot.getWorld().getPlayers()) {
         if (p.getLocation().distanceSquared(spot) < 64 * 64 && this.plugin.pack().has(p)) {
            p.showEntity(this.plugin, e);
         }
      }

      Decal d = new Decal(e, size, spin);
      int[] age = {0};
      d.task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         age[0] += 3;
         if (!e.isValid()) {
            d.remove();
            return;
         }

         if (life > 0 && age[0] >= life) {
            d.remove();
            return;
         }

         d.angle += d.spin * 3.0F;
         if (life > 0 && age[0] >= life - 6) {
            d.size = 0.05F;
         }

         d.apply(3);
      }, 1L, 3L).getTaskId();
      return d;
   }

   // ------------------------------------------------------------------ texte flottant

   /** Évite les couleurs que le shader du pack déplace vers le HUD (R%4==1, G%4==2, B%4==3). */
   static int safe(int rgb) {
      int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
      if (r % 4 == 1 && g % 4 == 2 && b % 4 == 3) {
         b = b - 1;
      }

      return r << 16 | g << 8 | b;
   }

   public void text(Location at, String text, int rgb, float scale, boolean bold) {
      if (!this.numbers) {
         return;
      }

      ThreadLocalRandom r = ThreadLocalRandom.current();
      Location l = at.clone().add((r.nextDouble() - 0.5) * 0.7, r.nextDouble() * 0.3, (r.nextDouble() - 0.5) * 0.7);
      Component c = Component.text(text, TextColor.color(safe(rgb))).decoration(TextDecoration.BOLD, bold);
      TextDisplay d = this.spawn(l, TextDisplay.class, 22, e -> {
         e.text(c);
         e.setBillboard(Display.Billboard.CENTER);
         e.setDefaultBackground(false);
         e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
         e.setShadowed(true);
         e.setSeeThrough(false);
         e.setViewRange(0.6F);
         e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale * 0.4F, scale * 0.4F, scale * 0.4F), new Quaternionf()));
      });
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (d.isValid()) {
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(4);
            d.setTransformation(new Transformation(new Vector3f(0.0F, 0.25F, 0.0F), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
         }
      }, 2L);
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (d.isValid()) {
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(14);
            d.setTransformation(new Transformation(new Vector3f(0.0F, 1.0F, 0.0F), new Quaternionf(), new Vector3f(scale * 0.6F, scale * 0.6F, scale * 0.6F), new Quaternionf()));
         }
      }, 7L);
   }

   // ------------------------------------------------------------------ particules

   public static void ring(World w, Location c, double r, int n, Particle p, Object data) {
      for (int i = 0; i < n; i++) {
         double a = Math.PI * 2 * i / n;
         w.spawnParticle(p, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0, data);
      }
   }
}
