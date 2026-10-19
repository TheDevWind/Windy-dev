package fr.windydev.clans.hub;

import fr.windydev.clans.WindyClans;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Construit la place du spawn : terrain aplani, pavillon (spawn-pavillon.schem), allées, lampadaires,
 * haie, arbres, quatre étals et la grande roue, puis pose les PNJ et les hologrammes.
 * Le terrain d'avant est gardé dans spawn-backup.dat pour /clanadmin hub annuler.
 */
public class SpawnBuilder {
   private static final int R = 24;
   private static final int EDGE = 78;
   /** rayon de la zone protégée : les coins arrondis du village vont plus loin que les côtés */
   private static final int ZONE = 92;
   private static final String MAGIC = "WHUB2";
   private static final String PREFIX = "spawn_";

   private final WindyClans plugin;
   private final HubManager hub;
   private final Random rnd = new Random(20261005L);
   private World w;
   private int cx, cy, cz;
   private SiteShaper shaper;
   private byte[] relief;

   public SpawnBuilder(WindyClans plugin, HubManager hub) {
      this.plugin = plugin;
      this.hub = hub;
   }

   // ------------------------------------------------------------------ blocs

   private void set(int dx, int dy, int dz, Material m) {
      this.w.getBlockAt(this.cx + dx, this.cy + dy, this.cz + dz).setType(m, false);
   }

   private void set(int dx, int dy, int dz, String data) {
      this.w.getBlockAt(this.cx + dx, this.cy + dy, this.cz + dz).setBlockData(Bukkit.createBlockData(data), false);
   }

   private Material path() {
      int r = this.rnd.nextInt(20);
      return r < 10 ? Material.STONE_BRICKS : (r < 14 ? Material.ANDESITE : (r < 17 ? Material.COBBLESTONE : Material.MOSSY_STONE_BRICKS));
   }

   private Location at(double dx, double dy, double dz, float yaw) {
      return new Location(this.w, this.cx + dx, this.cy + dy, this.cz + dz, yaw, 0.0F);
   }

   // ------------------------------------------------------------------ sauvegarde du terrain

   private File backupFile() {
      return new File(this.plugin.getDataFolder(), "spawn-backup.dat");
   }

   public void undo(CommandSender s) {
      this.undo(s, true);
   }

   /** Reconstruit la place au même endroit avec la version actuelle du plugin. */
   public void rebuild(CommandSender s) {
      Location c = this.undo(s, false);
      if (c != null) {
         this.install(s, new String[]{String.valueOf(c.getBlockX()), String.valueOf(c.getBlockY()), String.valueOf(c.getBlockZ()), c.getWorld().getName()});
      }
   }

   private Location undo(CommandSender s, boolean talk) {
      File f = this.backupFile();
      if (!f.exists()) {
         this.plugin.msg(s, "&cAucune installation à annuler.");
         return null;
      }

      Location centre;

      try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
         // les sauvegardes d'avant la 1.17 n'ont pas d'en-tête : place de 26 blocs de rayon
         String first = in.readUTF();
         if (first.equals(SiteShaper.MAGIC)) {
            World[] wo = new World[1];
            int[] c0 = SiteShaper.restore(in, wo);
            if (c0 == null) {
               this.plugin.msg(s, "&cLe monde de la sauvegarde n'existe plus.");
               return null;
            }

            centre = new Location(wo[0], c0[0] + 0.5, c0[1], c0[2] + 0.5);
            this.plugin.spawns().set(centre);
         } else {
            // anciennes sauvegardes : un cube entier (sans en-tête avant la 1.17 : place de 26 blocs de rayon)
            int edge = 26, up = 24;
            if (first.equals(MAGIC)) {
               edge = in.readInt();
               up = in.readInt();
               first = in.readUTF();
            }

            World world = Bukkit.getWorld(first);
            if (world == null) {
               this.plugin.msg(s, "&cLe monde de la sauvegarde n'existe plus.");
               return null;
            }

            int x0 = in.readInt(), y0 = in.readInt(), z0 = in.readInt(), dn = in.readInt();
            BlockData[] pal = new BlockData[in.readInt()];
            for (int i = 0; i < pal.length; i++) {
               pal[i] = Bukkit.createBlockData(in.readUTF());
            }

            // le spawn revient au centre de la place : une réinstallation retombe au même endroit
            centre = new Location(world, x0 + 0.5, y0, z0 + 0.5);
            this.plugin.spawns().set(centre);
            in.readInt();
            for (int y = -dn; y <= up; y++) {
               for (int z = -edge; z <= edge; z++) {
                  for (int x = -edge; x <= edge; x++) {
                     world.getBlockAt(x0 + x, y0 + y, z0 + z).setBlockData(pal[in.readInt()], false);
                  }
               }
            }
         }
      } catch (Exception e) {
         this.plugin.msg(s, "&cAnnulation impossible : " + e.getMessage());
         return null;
      }

      this.hub.removePrefix(PREFIX);
      this.hub.setZone(new Location(Bukkit.getWorlds().get(0), 0, 0, 0), 0.0);
      this.hub.save();
      f.delete();
      if (talk) {
         this.plugin.msg(s, "&aTerrain d'origine remis, PNJ et hologrammes du spawn retirés.");
      }

      return centre;
   }

   // ------------------------------------------------------------------ installation

   public void install(CommandSender s, String[] a) {
      Location c;
      try {
         if (a.length >= 3) {
            World world = a.length >= 4 ? Bukkit.getWorld(a[3]) : (s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0));
            if (world == null) {
               this.plugin.msg(s, "&cMonde inconnu.");
               return;
            }

            c = new Location(world, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
         } else if (s instanceof Player p) {
            c = p.getLocation();
         } else {
            c = this.plugin.spawns().get();
            if (c == null) {
               World world = Bukkit.getWorlds().get(0);
               c = world.getSpawnLocation();
               c.setY(world.getHighestBlockYAt(c) + 1);
            }
         }
      } catch (NumberFormatException e) {
         this.plugin.msg(s, "&c/clanadmin hub installer [x y z] [monde]");
         return;
      }

      if (this.backupFile().exists()) {
         this.plugin.msg(s, "&cUne place est déjà installée. &7/clanadmin hub annuler d'abord.");
         return;
      }

      this.w = c.getWorld();
      this.cx = c.getBlockX();
      this.cy = c.getBlockY();
      this.cz = c.getBlockZ();
      if (!this.loadVillage()) {
         this.vids = null;
         this.relief = null;
         this.plugin.getLogger().warning("Village du spawn absent ou de mauvaise taille : seule la place sera construite.");
      }

      long t0 = System.currentTimeMillis();
      this.shaper = new SiteShaper(this.plugin, this.w, this.cx, this.cy, this.cz, EDGE, R + 0.5, this.relief, this.vids == null ? 0 : this.vh);
      if (!this.shaper.plan()) {
         this.plugin.msg(s, "&cHauteur impossible ici.");
         return;
      }

      try {
         this.shaper.backup(this.backupFile());
      } catch (IOException e) {
         this.backupFile().delete();
         this.plugin.msg(s, "&cSauvegarde du terrain impossible, rien n'a été modifié : " + e.getMessage());
         return;
      }

      // restes éventuels d'une installation précédente
      this.hub.removePrefix(PREFIX);
      this.shaper.shapeInside();
      this.terrain();
      this.avenues();
      this.trees();
      int pasted = this.pavilion();
      this.stall(13, -13, 1, Material.PURPLE_WOOL, Material.WHITE_WOOL, Material.AMETHYST_BLOCK, Material.CHEST, Material.BUDDING_AMETHYST, false);
      this.stall(13, 13, -1, Material.BLUE_WOOL, Material.LIGHT_BLUE_WOOL, Material.BOOKSHELF, Material.ENCHANTING_TABLE, Material.BOOKSHELF, false);
      this.stall(-13, 13, -1, Material.GRAY_WOOL, Material.RED_WOOL, Material.CHISELED_DEEPSLATE, Material.SMITHING_TABLE, Material.POLISHED_DEEPSLATE, false);
      this.stall(-13, -13, 1, Material.YELLOW_WOOL, Material.ORANGE_WOOL, Material.BARREL, Material.SPRUCE_LOG, Material.BARREL, true);
      int built = this.village();
      int skirt = this.shaper.shapeSkirt();
      this.people();
      this.villagers();
      Location spawn = this.at(-1.5, 1.0, 0.5, 90.0F);
      this.plugin.spawns().set(spawn);
      this.hub.setZone(this.at(0.5, 0, 0.5, 0.0F), ZONE);
      this.vids = null;
      this.vpal = null;
      this.relief = null;
      int width = this.shaper.skirtWidth();
      this.shaper = null;
      this.hub.save();
      this.hub.ensure();
      this.plugin.msg(s, "&aPlace et village médiéval construits en &f" + this.cx + " " + this.cy + " " + this.cz + " &a(" + pasted + " blocs de pavillon, " + built + " blocs de village). Spawn du serveur placé dans le pavillon.");
      this.plugin.msg(s, "&7Emprise : " + (2 * EDGE + 1) + " blocs de côté. Alentours raccordés sur " + width + " blocs (" + skirt + " colonnes remodelées, constructions et territoires épargnés). Zone protégée sur " + ZONE + " blocs.");
      this.plugin.msg(s, "&8Fait en " + (System.currentTimeMillis() - t0) / 1000.0 + " s. /clanadmin hub annuler pour revenir en arrière.");
   }

   /** La place centrale : herbe, anneau pavé, haie et fleurs. Le sol et le vide sont déjà préparés par SiteShaper. */
   private void terrain() {
      for (int x = -R - 1; x <= R + 1; x++) {
         for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > R + 0.5) {
               continue;
            }

            boolean avenue = Math.abs(x) <= 1 || Math.abs(z) <= 1;
            Material top = Material.GRASS_BLOCK;
            if (d > 6.5 && d <= 9.5) {
               top = this.path();
            } else if (d > 9.5 && d <= 10.5 && !avenue) {
               top = Material.POLISHED_ANDESITE;
            } else if (avenue && d > 9.5) {
               top = this.path();
            }

            this.set(x, -1, z, top);
            if (top != Material.GRASS_BLOCK) {
               continue;
            }

            boolean gate = Math.abs(x) <= 2 || Math.abs(z) <= 2;
            if (d > 22.0 && d <= 23.4 && !gate) {
               this.set(x, 0, z, "minecraft:oak_leaves[persistent=true]");
               this.set(x, 1, z, this.rnd.nextInt(3) == 0 ? "minecraft:flowering_azalea_leaves[persistent=true]" : "minecraft:oak_leaves[persistent=true]");
            } else if (d > 10.5 && d <= 22.0 || d > 23.4) {
               int r = this.rnd.nextInt(100);
               if (r < 12) {
                  this.set(x, 0, z, Material.SHORT_GRASS);
               } else if (r < 17) {
                  Material[] fl = {Material.POPPY, Material.CORNFLOWER, Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.DANDELION};
                  this.set(x, 0, z, fl[this.rnd.nextInt(fl.length)]);
               }
            }
         }
      }
   }

   private void lamp(int x, int z) {
      this.set(x, -1, z, Material.STONE_BRICKS);
      this.set(x, 0, z, Material.MOSSY_COBBLESTONE_WALL);
      this.set(x, 1, z, Material.SPRUCE_FENCE);
      this.set(x, 2, z, Material.SPRUCE_FENCE);
      this.set(x, 3, z, Material.LANTERN);
   }

   private void pillar(int x, int z) {
      this.set(x, -1, z, Material.STONE_BRICKS);
      this.set(x, 0, z, Material.CHISELED_STONE_BRICKS);
      this.set(x, 1, z, Material.STONE_BRICKS);
      this.set(x, 2, z, Material.MOSSY_STONE_BRICKS);
      this.set(x, 3, z, Material.CHISELED_STONE_BRICKS);
      this.set(x, 4, z, Material.STONE_BRICK_SLAB);
      this.set(x, 5, z, Material.LANTERN);
   }

   /** Lampadaires le long des quatre allées, piliers aux entrées, bancs autour du pavillon. */
   private void avenues() {
      int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      for (int[] d : dirs) {
         int px = d[1], pz = d[0]; // perpendiculaire
         for (int k : new int[]{12, 18}) {
            for (int side : new int[]{-3, 3}) {
               this.lamp(d[0] * k + px * side, d[1] * k + pz * side);
            }
         }

         for (int side : new int[]{-3, 3}) {
            this.pillar(d[0] * 23 + px * side, d[1] * 23 + pz * side);
         }
      }

      // bancs tournés vers le pavillon
      this.set(11, 0, 5, "minecraft:spruce_stairs[facing=east]");
      this.set(11, 0, -5, "minecraft:spruce_stairs[facing=east]");
      this.set(-11, 0, 5, "minecraft:spruce_stairs[facing=west]");
      this.set(-11, 0, -5, "minecraft:spruce_stairs[facing=west]");
      this.set(5, 0, 11, "minecraft:spruce_stairs[facing=south]");
      this.set(-5, 0, 11, "minecraft:spruce_stairs[facing=south]");
      this.set(5, 0, -11, "minecraft:spruce_stairs[facing=north]");
      this.set(-5, 0, -11, "minecraft:spruce_stairs[facing=north]");
   }

   private void trees() {
      for (int i = 0; i < 8; i++) {
         double a = Math.PI / 8 + Math.PI / 4 * i;
         int x = (int)Math.round(Math.cos(a) * 19.0), z = (int)Math.round(Math.sin(a) * 19.0);
         int h = 4 + this.rnd.nextInt(2);
         for (int lx = -2; lx <= 2; lx++) {
            for (int lz = -2; lz <= 2; lz++) {
               for (int ly = h - 2; ly <= h + 1; ly++) {
                  double dd = lx * lx + lz * lz + (ly - h) * (ly - h) * 1.6;
                  if (dd <= 5.2 + this.rnd.nextDouble() * 1.2) {
                     this.set(x + lx, ly, z + lz, this.rnd.nextInt(6) == 0 ? "minecraft:flowering_azalea_leaves[persistent=true]" : "minecraft:oak_leaves[persistent=true]");
                  }
               }
            }
         }

         for (int y = 0; y < h; y++) {
            this.set(x, y, z, Material.OAK_LOG);
         }

         this.set(x, -1, z, Material.DIRT);
      }
   }

   /** Colle le pavillon (format Sponge v2), centré sur la place. Renvoie le nombre de blocs posés. */
   private int pavilion() {
      try (InputStream raw = this.plugin.getResource("spawn-pavillon.schem")) {
         if (raw == null) {
            return 0;
         }

         DataInputStream in = new DataInputStream(new GZIPInputStream(raw));
         in.readByte();
         in.readUTF();
         Map<String, Object> root = compound(in);
         int width = ((Number)root.get("Width")).intValue(), height = ((Number)root.get("Height")).intValue(), length = ((Number)root.get("Length")).intValue();
         @SuppressWarnings("unchecked")
         Map<String, Object> pal = (Map<String, Object>)root.get("Palette");
         Map<Integer, BlockData> byId = new HashMap<>();
         for (Map.Entry<String, Object> e : pal.entrySet()) {
            String name = e.getKey().replace("minecraft:grass[", "minecraft:short_grass[");
            if (name.equals("minecraft:grass")) {
               name = "minecraft:short_grass";
            }

            BlockData bd;
            try {
               bd = Bukkit.createBlockData(name);
            } catch (IllegalArgumentException ex) {
               bd = null;
            }

            byId.put(((Number)e.getValue()).intValue(), bd);
         }

         byte[] data = (byte[])root.get("BlockData");
         int i = 0, n = 0, placed = 0;
         while (i < data.length) {
            int v = 0, shift = 0;
            while (true) {
               byte b = data[i++];
               v |= (b & 0x7F) << shift;
               shift += 7;
               if ((b & 0x80) == 0) {
                  break;
               }
            }

            int y = n / (width * length), z = n % (width * length) / width, x = n % width;
            n++;
            BlockData bd = byId.get(v);
            // la couche 0 est le sol du modèle : la place fournit le sien
            int rx = x - 7, rz = z - 8;
            if (bd == null || y == 0 || bd.getMaterial().isAir() || y >= height || y <= 2 && rx * rx + rz * rz > 36) {
               continue;
            }

            this.w.getBlockAt(this.cx + x - 7, this.cy + y - 1, this.cz + z - 8).setBlockData(bd, false);
            placed++;
         }

         return placed;
      } catch (Exception e) {
         this.plugin.getLogger().warning("Pavillon du spawn : lecture impossible : " + e);
         return 0;
      }
   }

   private static Map<String, Object> compound(DataInputStream in) throws IOException {
      Map<String, Object> m = new LinkedHashMap<>();
      while (true) {
         int t = in.readByte();
         if (t == 0) {
            return m;
         }

         String name = in.readUTF();
         m.put(name, tag(in, t));
      }
   }

   private static Object tag(DataInputStream in, int t) throws IOException {
      switch (t) {
         case 1:
            return in.readByte();
         case 2:
            return in.readShort();
         case 3:
            return in.readInt();
         case 4:
            return in.readLong();
         case 5:
            return in.readFloat();
         case 6:
            return in.readDouble();
         case 7: {
            byte[] b = new byte[in.readInt()];
            in.readFully(b);
            return b;
         }
         case 8:
            return in.readUTF();
         case 9: {
            int et = in.readByte(), n = in.readInt();
            List<Object> l = new ArrayList<>();
            for (int i = 0; i < n; i++) {
               l.add(tag(in, et));
            }

            return l;
         }
         case 10:
            return compound(in);
         case 11: {
            int[] a = new int[in.readInt()];
            for (int i = 0; i < a.length; i++) {
               a[i] = in.readInt();
            }

            return a;
         }
         case 12: {
            long[] a = new long[in.readInt()];
            for (int i = 0; i < a.length; i++) {
               a[i] = in.readLong();
            }

            return a;
         }
         default:
            throw new IOException("balise NBT inconnue " + t);
      }
   }

   /**
    * Étal de marché de 5 x 3. face = +1 : le comptoir regarde vers le sud (+z), -1 : vers le nord.
    * wheel : dresse la grande roue derrière l'étal.
    */
   private void stall(int sx, int sz, int face, Material a, Material b, Material p1, Material p2, Material p3, boolean wheel) {
      // v = profondeur : 0 = comptoir (côté allée), 2 = fond
      for (int u = -2; u <= 2; u++) {
         for (int v = -1; v <= 2; v++) {
            int x = sx + u, z = sz - face * v;
            for (int y = 0; y <= 9; y++) {
               this.set(x, y, z, Material.AIR);
            }

            if (v >= 0) {
               this.set(x, -1, z, Material.SPRUCE_PLANKS);
            }

            this.set(x, 3, z, (u + 2) % 2 == 0 ? a : b);
         }
      }

      for (int u : new int[]{-2, 2}) {
         for (int v : new int[]{0, 2}) {
            for (int y = 0; y <= 2; y++) {
               this.set(sx + u, y, sz - face * v, Material.SPRUCE_FENCE);
            }
         }
      }

      this.set(sx - 1, 0, sz, Material.STRIPPED_SPRUCE_LOG);
      this.set(sx + 1, 0, sz, Material.STRIPPED_SPRUCE_LOG);
      this.set(sx, 0, sz, "minecraft:spruce_slab[type=top]");
      this.set(sx - 1, 0, sz - face * 2, p1);
      this.set(sx, 0, sz - face * 2, p2);
      this.set(sx + 1, 0, sz - face * 2, p3);
      this.set(sx, 2, sz + face, "minecraft:lantern[hanging=true]");
      if (wheel) {
         int z = sz - face * 3;
         Material[] col = {Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE,
            Material.BLUE_CONCRETE, Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE};
         for (int y = 0; y <= 3; y++) {
            this.set(sx, y, z, Material.SPRUCE_LOG);
         }

         for (int u = -3; u <= 3; u++) {
            for (int h = -3; h <= 3; h++) {
               double d = Math.sqrt(u * u + h * h);
               if (d > 3.6) {
                  continue;
               }

               Material m;
               if (u == 0 && h == 0) {
                  m = Material.GOLD_BLOCK;
               } else if (d > 2.9) {
                  m = Material.SPRUCE_PLANKS;
               } else {
                  int sector = (int)Math.floor((Math.atan2(h, u) + Math.PI) / (Math.PI / 4)) % 8;
                  m = col[sector];
               }

               this.set(sx + u, 7 + h, z, m);
            }
         }

         // la flèche
         this.set(sx, 11, z, Material.REDSTONE_BLOCK);
      }
   }

   private void holo(String id, double dx, double dy, double dz, double scale, String... lines) {
      this.hub.putHolo(PREFIX + id, this.at(dx, dy, dz, 0.0F), scale, List.of(lines));
   }

   /** PNJ et hologrammes. */
   private void people() {
      // les quatre étals
      this.hub.putNpc(PREFIX + "pnj_boutique", this.at(13.5, 0, -13.5, 0.0F), "weaponsmith", "taiga", "&d&lMarchand d'Éclats", "boutique");
      this.holo("t_boutique", 13.5, 5.3, -12.5, 0.9, "&d&l✦ Boutique d'Éclats", "&7Échange tes Éclats de donjon :", "&fglyphes, sceaux, runes, grimoires", "&8Clic droit sur le marchand");
      this.hub.putNpc(PREFIX + "pnj_mage", this.at(13.5, 0, 14.5, 180.0F), "cleric", "swamp", "&9&lArchimage", "grimoire");
      this.holo("t_mage", 13.5, 5.3, 13.5, 0.9, "&9&l✦ Grimoire", "&7Choisis tes 3 sorts et vois", "&7les réactions élémentaires.", "&8/grimoire");
      this.hub.putNpc(PREFIX + "pnj_donjons", this.at(-12.5, 0, 14.5, 180.0F), "armorer", "snow", "&c&lVeilleur des donjons", "donjons");
      this.holo("t_donjons", -12.5, 5.3, 13.5, 0.9, "&c&l⚔ Donjons", "&73 étages, 1 à 5 joueurs, 3 vies.", "&7Il faut un &fSceau de donjon&7.", "&8/donjon");
      this.hub.putNpc(PREFIX + "pnj_roue", this.at(-12.5, 0, -13.5, 0.0F), "cartographer", "savanna", "&6&lMaître de la roue", "roue");
      this.holo("t_roue", -12.5, 5.3, -12.5, 0.9, "&6&l✪ Roue de la fortune", "&7Un tour gratuit &fchaque jour&7.", "&77 jours d'affilée : lot rare garanti.", "&8/roue");
      // autour du pavillon
      this.hub.putNpc(PREFIX + "pnj_guide", this.at(-7.5, 0, 3.5, 90.0F), "librarian", "plains", "&a&lGuide", "menu");
      this.holo("t_guide", -7.5, 2.9, 3.5, 0.8, "&a&lGuide", "&7Ouvre le menu principal");
      this.hub.putNpc(PREFIX + "pnj_classement", this.at(-7.5, 0, -2.5, 90.0F), "fletcher", "desert", "&e&lChroniqueur", "classement");
      this.holo("t_classement", -7.5, 2.9, -2.5, 0.8, "&e&lChroniqueur", "&7Classement des joueurs");
      this.hub.putNpc(PREFIX + "pnj_faille", this.at(8.5, 0, 3.5, -90.0F), "cleric", "jungle", "&5&lHéraut de la faille", "faille");
      this.holo("t_faille", 8.5, 2.9, 3.5, 0.8, "&5&lHéraut de la faille", "&7Entrer quand elle est ouverte");
      this.hub.putNpc(PREFIX + "pnj_boss", this.at(8.5, 0, -2.5, -90.0F), "mason", "taiga", "&4&lMaître des boss", "boss");
      this.holo("t_boss", 8.5, 2.9, -2.5, 0.8, "&4&lMaître des boss", "&7Boss de clan");
      // bienvenue, au-dessus de l'entrée du pavillon
      this.holo("bienvenue", -10.5, 3.4, 0.5, 1.2, "&6&l✦ WINDY_MC ✦", "&fBienvenue !", "&7Suis les allées : chaque panneau", "&7explique une partie du serveur.");
      // panneaux des allées
      this.holo("clans", -13.5, 2.6, 4.5, 0.85, "&6&l⚑ Clans", "&e/clan &7: créer ou rejoindre un clan", "&7Territoire, banque, alliances, guerres.", "&e/cc &7chat du clan  &e/ca &7chat des alliés");
      this.holo("voyage", -19.5, 2.6, -4.5, 0.85, "&b&l➤ Se déplacer", "&e/spawn &7: revenir ici", "&e/sethome &7puis &e/home &7: ta maison", "&e/tpa <joueur> &7: rejoindre un ami");
      this.holo("profil", 4.5, 2.6, -13.5, 0.85, "&a&l☺ Profil et races", "&e/profil &7: niveau, XP, statistiques", "&e/races &7: choisis ta race (pouvoirs)", "&e/competences &7et &e/eveil &7pour progresser");
      this.holo("rituels", -4.5, 2.6, -19.5, 0.85, "&d&l⚗ Chaudron et rituels", "&7Tout commence au &fchaudron runique&7.", "&7Baies arcaniques → fluide magique", "&7→ autel : armes, armures, gravure.");
      this.holo("magie", 13.5, 2.6, -4.5, 0.85, "&9&l✦ Magie", "&7Bâton en main : &fclic droit &7lance,", "&fclic maintenu &7charge le sort.", "&7Les grimoires tombent en donjon.");
      this.holo("faille", 19.5, 2.6, 4.5, 0.85, "&5&l🌀 Faille arcanique", "&7S'ouvre environ une fois par jour.", "&e/faille &7: 30 min pour vaincre l'Avatar.", "&73 rituels réussis : magie sans bâton.");
      this.holo("donjons", -4.5, 2.6, 13.5, 0.85, "&c&l⚔ Donjons et boss", "&e/donjon &7: Crypte, Mines, Citadelle", "&7Butin : Éclats, glyphes, grimoires.", "&7Les boss de clan donnent les runes.");
      this.holo("aide", 4.5, 2.6, 19.5, 0.85, "&e&l? Besoin d'aide", "&e/clan aide &7: toutes les commandes", "&e/lier <code> &7: relier ton Discord", "&7Le wiki et les notes sont sur Discord.");
   }

   // ================================================================== village médiéval autour de la place

   /** Village (spawn-village.schem) : la couche 0 est le sol, le centre du modèle est le centre de la place. */
   private int vw, vh, vl;
   private int[] vids;
   private BlockData[] vpal;

   private boolean loadVillage() {
      try (InputStream raw = this.plugin.getResource("spawn-village.schem")) {
         if (raw == null) {
            return false;
         }

         DataInputStream in = new DataInputStream(new GZIPInputStream(raw));
         in.readByte();
         in.readUTF();
         Map<String, Object> root = compound(in);
         this.vw = ((Number)root.get("Width")).intValue();
         this.vh = ((Number)root.get("Height")).intValue();
         this.vl = ((Number)root.get("Length")).intValue();
         @SuppressWarnings("unchecked")
         Map<String, Object> pal = (Map<String, Object>)root.get("Palette");
         this.vpal = new BlockData[pal.size()];
         int bad = 0;
         for (Map.Entry<String, Object> e : pal.entrySet()) {
            BlockData bd = null;
            try {
               bd = Bukkit.createBlockData(e.getKey());
            } catch (IllegalArgumentException ex) {
               bad++;
            }

            this.vpal[((Number)e.getValue()).intValue()] = bd != null && bd.getMaterial().isAir() ? null : bd;
         }

         if (bad > 0) {
            this.plugin.getLogger().warning("Village du spawn : " + bad + " types de blocs inconnus de cette version, ignorés.");
         }

         byte[] data = (byte[])root.get("BlockData");
         this.vids = new int[this.vw * this.vh * this.vl];
         int i = 0, n = 0;
         while (i < data.length && n < this.vids.length) {
            int v = 0, shift = 0;
            while (true) {
               byte b = data[i++];
               v |= (b & 0x7F) << shift;
               shift += 7;
               if ((b & 0x80) == 0) {
                  break;
               }
            }

            this.vids[n++] = v;
         }

         if (this.vw != 2 * EDGE + 1 || this.vl != 2 * EDGE + 1) {
            return false;
         }

         try (InputStream rel = this.plugin.getResource("spawn-relief.bin")) {
            this.relief = rel == null ? null : rel.readAllBytes();
         }

         return this.relief != null && this.relief.length == this.vw * this.vl;
      } catch (Exception e) {
         this.plugin.getLogger().warning("Village du spawn : lecture impossible : " + e);
         return false;
      }
   }

   /** Hauteur (relative à la place) où poser quelque chose sur le sol du village en (x, z). */
   private double gy(int x, int z) {
      return this.shaper == null ? 0.0 : this.shaper.relief(x, z);
   }

   private int village() {
      if (this.vids == null) {
         return 0;
      }

      int placed = 0, i = 0;
      for (int y = 0; y < this.vh; y++) {
         for (int z = -EDGE; z <= EDGE; z++) {
            for (int x = -EDGE; x <= EDGE; x++) {
               BlockData bd = this.vpal[this.vids[i++]];
               if (bd != null) {
                  this.w.getBlockAt(this.cx + x, this.cy + y - 1 - SiteShaper.BASE, this.cz + z).setBlockData(bd, false);
                  placed++;
               }
            }
         }
      }

      // têtes décoratives : la texture est dans l'entité de bloc
      try (InputStream raw = this.plugin.getResource("spawn-tetes.txt")) {
         if (raw != null) {
            for (String line : new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
               String[] p = line.trim().split(" ");
               if (p.length != 4) {
                  continue;
               }

               Block b = this.w.getBlockAt(this.cx + Integer.parseInt(p[0]) - EDGE, this.cy + Integer.parseInt(p[1]) - 1 - SiteShaper.BASE, this.cz + Integer.parseInt(p[2]) - EDGE);
               if (b.getState() instanceof org.bukkit.block.Skull sk) {
                  com.destroystokyo.paper.profile.PlayerProfile pp = Bukkit.createProfile(java.util.UUID.nameUUIDFromBytes(p[3].getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
                  pp.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", p[3]));
                  sk.setPlayerProfile(pp);
                  sk.update(true, false);
               }
            }
         }
      } catch (Exception e) {
         this.plugin.getLogger().warning("Têtes du village : " + e);
      }

      // panneaux des quartiers
      this.holo("q_porte", -56.5, 4.5 + this.gy(-57, 0), 0.5, 1.0, "&6&l✦ Porte de l'Ouest ✦", "&7Bienvenue au village de WINDY_MC");
      this.holo("q_marchands", 24.5, 3.2 + this.gy(24, -31), -30.5, 0.9, "&6&lQuartier des marchands", "&7Maisons à tuiles et chantier des grues");
      this.holo("q_feuillage", 30.5, 3.2 + this.gy(30, 33), 33.5, 0.9, "&a&lQuartier du feuillage", "&7Les toits vivants et la mare");
      this.holo("q_marche", -30.5, 3.2 + this.gy(-31, 30), 30.5, 0.9, "&e&lMarché et campement", "&7Étals, tentes et feu de camp");
      this.holo("q_ferme", -30.5, 3.2 + this.gy(-31, -31), -30.5, 0.9, "&2&lLa ferme", "&7Champs, chaumières et moulin");
      this.hub.putHolo(PREFIX + "records", this.at(40.5, 3.0 + this.gy(40, -4), -3.5, 0.0F), 0.85, List.of("&c&l⚔ Records des donjons", "{records}"));
      this.hub.putHolo(PREFIX + "top_clans", this.at(-40.5, 3.0 + this.gy(-41, 3), 3.5, 0.0F), 0.85, List.of("&6&l⚑ Meilleurs clans", "{top_clans}"));
      this.hub.putHolo(PREFIX + "top_joueurs", this.at(3.5, 3.0 + this.gy(3, 40), 40.5, 0.0F), 0.85, List.of("&e&l★ Meilleurs joueurs", "{top_joueurs}"));
      return placed;
   }

   /** Hauteur (relative à la place) du premier bloc libre au-dessus du sol réel en (x, z). */
   private double floorY(int x, int z) {
      return this.w.getHighestBlockYAt(this.cx + x, this.cz + z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1 - this.cy;
   }

   /** Habitants et chats qui se promènent. */
   private void villagers() {
      String tips = "say:Bonjour !|Belle journée sur WINDY_MC.|Tu as tourné la roue aujourd'hui ?|Le Veilleur ouvre les donjons, si tu as un sceau.|On raconte que l'Avatar de la faille est invincible seul.|Rejoins un clan, c'est plus sûr la nuit.|Le marchand d'Éclats rachète les grimoires en double.";
      double[][] spots = {{28, -6}, {-28, 6}, {6, 28}, {-6, -28}, {45, 0}, {-45, 0}, {0, 45}, {0, -45}, {-36, 36}, {58, -30}};
      String[] jobs = {"farmer", "shepherd", "fisherman", "toolsmith", "leatherworker", "nitwit", "butcher", "farmer", "mason", "fletcher"};
      String[] biomes = {"plains", "taiga", "savanna", "snow", "desert", "jungle", "plains", "taiga", "swamp", "plains"};
      for (int i = 0; i < spots.length; i++) {
         this.hub.putRoamer(PREFIX + "habitant_" + (i + 1), this.at(spots[i][0] + 0.5, this.floorY((int)spots[i][0], (int)spots[i][1]), spots[i][1] + 0.5, 0.0F), "VILLAGER", jobs[i], biomes[i], "&7Habitant", tips, 9.0);
      }

      double[][] cats = {{-30, 4}, {16, -28}, {30, 16}, {-40, 40}, {50, 2}};
      for (int i = 0; i < cats.length; i++) {
         this.hub.putRoamer(PREFIX + "chat_" + (i + 1), this.at(cats[i][0] + 0.5, this.floorY((int)cats[i][0], (int)cats[i][1]), cats[i][1] + 0.5, 0.0F), "CAT", "none", "plains", "&7Chat du village", "say:Miaou.|Mrrr...|...", 8.0);
      }
   }
}
