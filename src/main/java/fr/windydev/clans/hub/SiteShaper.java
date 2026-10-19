package fr.windydev.clans.hub;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.ChunkPos;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Prépare le terrain du spawn : relève le sol naturel, remplit sous le village jusqu'au sol, dégage ce qui dépasse,
 * puis raccorde les alentours en pente douce (la « jupe »). Les constructions de joueurs et les territoires de clan
 * autour ne sont jamais touchés. Tout ce qui est modifié est d'abord sauvegardé, colonne par colonne.
 */
final class SiteShaper {
   /** largeur maximale du raccord autour du village */
   static final int SKIRT = 40;
   /** nombre de couches du modèle sous le niveau de la place */
   static final int BASE = 8;
   static final String MAGIC = "WHUB3";

   private static final Set<String> PLANTS = Set.of(
      "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "VINE", "GLOW_LICHEN", "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT",
      "SUGAR_CANE", "LILY_PAD", "BROWN_MUSHROOM", "RED_MUSHROOM", "SWEET_BERRY_BUSH", "MOSS_CARPET", "SNOW", "HANGING_ROOTS", "SMALL_DRIPLEAF",
      "BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM", "COCOA", "PINK_PETALS", "SPORE_BLOSSOM", "COBWEB", "POWDER_SNOW", "AZALEA", "FLOWERING_AZALEA",
      "POINTED_DRIPSTONE", "BAMBOO_SAPLING", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "PITCHER_PLANT", "TORCHFLOWER", "DANDELION", "POPPY",
      "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER",
      "LILY_OF_THE_VALLEY", "FIRE", "BUBBLE_COLUMN", "CAVE_VINES", "CAVE_VINES_PLANT", "WEEPING_VINES", "TWISTING_VINES");
   private static final Set<String> GROUND = Set.of(
      "GRAVEL", "CLAY", "SANDSTONE", "RED_SANDSTONE", "CALCITE", "DRIPSTONE_BLOCK", "MOSSY_COBBLESTONE", "BEDROCK", "OBSIDIAN", "MAGMA_BLOCK",
      "SMOOTH_BASALT", "SNOW_BLOCK", "ICE", "PACKED_ICE", "BLUE_ICE", "TERRACOTTA", "MUD", "MOSS_BLOCK", "GRASS_BLOCK", "DIRT", "COARSE_DIRT",
      "PODZOL", "MYCELIUM", "ROOTED_DIRT", "STONE", "GRANITE", "DIORITE", "ANDESITE", "TUFF", "DEEPSLATE", "SAND", "RED_SAND", "SUSPICIOUS_SAND",
      "SUSPICIOUS_GRAVEL", "MUDDY_MANGROVE_ROOTS", "MANGROVE_ROOTS", "INFESTED_STONE", "AMETHYST_BLOCK", "BUDDING_AMETHYST");

   private final WindyClans plugin;
   private final World w;
   private final int cx, cy, cz, edge, n, r, s;
   private final double centre;
   private final byte[] relief;
   private final int vh;
   private final int minY, maxY;

   /** par colonne (carré de côté s autour du centre) */
   private int[] nat, top, water, target, lo, hi;
   private boolean[] prot, in;
   private Material[] surf;
   private int skirt;
   private final Map<Material, Boolean> groundCache = new HashMap<>(), naturalCache = new HashMap<>();

   SiteShaper(WindyClans plugin, World w, int cx, int cy, int cz, int edge, double centre, byte[] relief, int vh) {
      this.plugin = plugin;
      this.w = w;
      this.cx = cx;
      this.cy = cy;
      this.cz = cz;
      this.edge = edge;
      this.n = 2 * edge + 1;
      this.r = edge + SKIRT;
      this.s = 2 * this.r + 1;
      this.centre = centre;
      this.relief = relief;
      this.vh = vh;
      this.minY = w.getMinHeight();
      this.maxY = w.getMaxHeight() - 1;
   }

   private int idx(int x, int z) {
      return (z + this.r) * this.s + x + this.r;
   }

   boolean centre(int x, int z) {
      return x * x + z * z <= this.centre * this.centre;
   }

   /** Vrai si la colonne fait partie du spawn (place centrale ou sol du village). */
   boolean inside(int x, int z) {
      if (Math.abs(x) > this.edge || Math.abs(z) > this.edge) {
         return false;
      }

      return this.centre(x, z) || this.relief != null && this.relief[(z + this.edge) * this.n + x + this.edge] != -128;
   }

   /** Hauteur du sol du village par rapport à la place (0 au centre et hors du village). */
   int relief(int x, int z) {
      if (this.relief == null || Math.abs(x) > this.edge || Math.abs(z) > this.edge || this.centre(x, z)) {
         return 0;
      }

      byte b = this.relief[(z + this.edge) * this.n + x + this.edge];
      return b == -128 ? 0 : b;
   }

   int skirtWidth() {
      return this.skirt;
   }

   private boolean veg(Material m) {
      String nm = m.name();
      return Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || nm.contains("MUSHROOM_BLOCK") || nm.equals("MUSHROOM_STEM") || nm.equals("BAMBOO")
         || nm.equals("CACTUS") || nm.equals("PUMPKIN") || nm.equals("MELON") || nm.equals("BEE_NEST");
   }

   private boolean ground(Material m) {
      return this.groundCache.computeIfAbsent(m, k -> k.isSolid() && !this.veg(k) && k != Material.SNOW && k != Material.POWDER_SNOW);
   }

   private boolean natural(Material m) {
      return this.naturalCache.computeIfAbsent(m, k -> {
         String nm = k.name();
         return k.isAir() || k == Material.WATER || k == Material.LAVA || this.veg(k) || PLANTS.contains(nm) || GROUND.contains(nm)
            || nm.endsWith("_ORE") || nm.endsWith("_TERRACOTTA") && !nm.contains("GLAZED") || Tag.SAPLINGS.isTagged(k);
      });
   }

   /** Relève le site et calcule ce qui sera modifié. Faux si la hauteur ne convient pas. */
   boolean plan() {
      int floor = this.cy - 1 - BASE;
      int roof = this.cy - 1 + Math.max(this.vh - BASE, 34);
      if (floor <= this.minY + 1 || roof >= this.maxY) {
         return false;
      }

      int total = this.s * this.s;
      this.nat = new int[total];
      this.top = new int[total];
      this.water = new int[total];
      this.target = new int[total];
      this.lo = new int[total];
      this.hi = new int[total];
      this.prot = new boolean[total];
      this.in = new boolean[total];
      this.surf = new Material[total];
      String world = this.w.getName();
      Map<Long, Boolean> claimed = new HashMap<>();
      for (int z = -this.r; z <= this.r; z++) {
         for (int x = -this.r; x <= this.r; x++) {
            int i = this.idx(x, z), wx = this.cx + x, wz = this.cz + z;
            int t = Math.min(this.w.getHighestBlockYAt(wx, wz), this.maxY);
            int g = this.minY - 1, wt = Integer.MIN_VALUE;
            boolean p = false;
            for (int y = t; y >= this.minY; y--) {
               Material m = this.w.getBlockAt(wx, y, wz).getType();
               if (!this.natural(m)) {
                  p = true;
               }

               if (m == Material.WATER || m == Material.LAVA) {
                  if (wt == Integer.MIN_VALUE) {
                     wt = y;
                  }
               } else if (this.ground(m)) {
                  g = y;
                  this.surf[i] = m;
                  break;
               }
            }

            this.top[i] = Math.min(this.maxY, Math.max(t, g) + 2);
            this.nat[i] = g;
            this.water[i] = wt;
            this.in[i] = this.inside(x, z);
            if (!this.in[i] && !p) {
               long key = (long)(wx >> 4) << 32 | (wz >> 4) & 0xFFFFFFFFL;
               p = claimed.computeIfAbsent(key, k -> this.plugin.clans().at(new ChunkPos(world, wx >> 4, wz >> 4)) != null);
            }

            this.prot[i] = p && !this.in[i];
            this.lo[i] = Integer.MAX_VALUE;
            this.hi[i] = Integer.MIN_VALUE;
            this.target[i] = Integer.MIN_VALUE;
         }
      }

      // emprise : de quoi remplir dessous jusqu'au sol, et dégager dessus jusqu'en haut de ce qui s'y trouve
      for (int z = -this.edge; z <= this.edge; z++) {
         for (int x = -this.edge; x <= this.edge; x++) {
            int i = this.idx(x, z);
            if (this.in[i]) {
               int base = this.centre(x, z) ? this.cy - 1 : floor;
               this.lo[i] = Math.max(this.minY, Math.min(this.nat[i] + 1, base));
               this.hi[i] = Math.min(this.maxY, Math.max(this.top[i], roof));
            }
         }
      }

      // jupe : distance au village (avec la hauteur du bord le plus proche) et distance aux zones à ne pas toucher
      float[] d = new float[total], dp = new float[total];
      int[] eh = new int[total];
      this.spread(d, eh, true);
      this.spread(dp, null, false);
      // largeur du raccord : d'autant plus large que le dénivelé au bord est fort
      int[] gaps = new int[total];
      int ng = 0;
      for (int i = 0; i < total; i++) {
         if (!this.in[i] && d[i] <= 2.5F && !this.prot[i] && this.nat[i] >= this.minY) {
            gaps[ng++] = Math.abs(this.nat[i] - eh[i]);
         }
      }

      Arrays.sort(gaps, 0, ng);
      int p90 = ng == 0 ? 0 : gaps[Math.min(ng - 1, (int)(ng * 0.9))];
      this.skirt = Math.max(14, Math.min(SKIRT, (int)Math.ceil(p90 * 2.2)));
      for (int z = -this.r; z <= this.r; z++) {
         for (int x = -this.r; x <= this.r; x++) {
            int i = this.idx(x, z);
            if (this.in[i] || this.prot[i] || d[i] > this.skirt || this.nat[i] < this.minY) {
               continue;
            }

            double t = d[i] / this.skirt;
            double wgt = 1.0 - t * t * (3.0 - 2.0 * t);
            double pf = Math.max(0.0, Math.min(1.0, (dp[i] - 1.5) / 6.0));
            int tg = (int)Math.round(this.nat[i] + (eh[i] - this.nat[i]) * wgt * pf);
            tg = Math.max(this.minY + 3, Math.min(this.maxY - 2, tg));
            if (tg == this.nat[i] || this.water[i] != Integer.MIN_VALUE && tg <= this.water[i]) {
               continue;
            }

            this.target[i] = tg;
            this.lo[i] = Math.max(this.minY, Math.min(this.nat[i], tg) - 2);
            this.hi[i] = Math.min(this.maxY, Math.max(this.top[i], tg + 1));
         }
      }

      return true;
   }

   /** Distance (8 voisins) aux colonnes sources ; eh reçoit la hauteur absolue du sol de la source la plus proche. */
   private void spread(float[] d, int[] eh, boolean fromVillage) {
      Arrays.fill(d, Float.MAX_VALUE);
      PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Float.compare(Float.intBitsToFloat((int)a[0]), Float.intBitsToFloat((int)b[0])));
      for (int z = -this.r; z <= this.r; z++) {
         for (int x = -this.r; x <= this.r; x++) {
            int i = this.idx(x, z);
            if (fromVillage ? this.in[i] : this.prot[i]) {
               d[i] = 0.0F;
               if (eh != null) {
                  eh[i] = this.cy - 1 + this.relief(x, z);
               }

               pq.add(new long[]{Float.floatToIntBits(0.0F), i});
            }
         }
      }

      int[] ox = {1, -1, 0, 0, 1, 1, -1, -1}, oz = {0, 0, 1, -1, 1, -1, 1, -1};
      while (!pq.isEmpty()) {
         long[] cur = pq.poll();
         int i = (int)cur[1];
         float di = Float.intBitsToFloat((int)cur[0]);
         if (di > d[i] || di > SKIRT + 2) {
            continue;
         }

         int x = i % this.s - this.r, z = i / this.s - this.r;
         for (int k = 0; k < 8; k++) {
            int nx = x + ox[k], nz = z + oz[k];
            if (Math.abs(nx) > this.r || Math.abs(nz) > this.r) {
               continue;
            }

            int j = this.idx(nx, nz);
            float nd = di + (k < 4 ? 1.0F : 1.4142F);
            if (nd < d[j]) {
               d[j] = nd;
               if (eh != null) {
                  eh[j] = eh[i];
               }

               pq.add(new long[]{Float.floatToIntBits(nd), j});
            }
         }
      }
   }

   /** Écrit dans le fichier l'état actuel de toutes les colonnes qui vont changer. */
   void backup(File f) throws IOException {
      Map<BlockData, Integer> pal = new LinkedHashMap<>();
      int cols = 0, cells = 0;
      for (int i = 0; i < this.lo.length; i++) {
         if (this.lo[i] <= this.hi[i]) {
            cols++;
            cells += this.hi[i] - this.lo[i] + 1;
         }
      }

      int[] ids = new int[cells];
      int k = 0;
      for (int z = -this.r; z <= this.r; z++) {
         for (int x = -this.r; x <= this.r; x++) {
            int i = this.idx(x, z);
            for (int y = this.lo[i]; y <= this.hi[i]; y++) {
               BlockData bd = this.w.getBlockAt(this.cx + x, y, this.cz + z).getBlockData();
               Integer id = pal.get(bd);
               if (id == null) {
                  id = pal.size();
                  pal.put(bd, id);
               }

               ids[k++] = id;
            }
         }
      }

      try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)))) {
         out.writeUTF(MAGIC);
         out.writeUTF(this.w.getName());
         out.writeInt(this.cx);
         out.writeInt(this.cy);
         out.writeInt(this.cz);
         out.writeInt(pal.size());
         for (BlockData bd : pal.keySet()) {
            out.writeUTF(bd.getAsString());
         }

         out.writeInt(cols);
         k = 0;
         for (int z = -this.r; z <= this.r; z++) {
            for (int x = -this.r; x <= this.r; x++) {
               int i = this.idx(x, z);
               if (this.lo[i] > this.hi[i]) {
                  continue;
               }

               out.writeShort(x);
               out.writeShort(z);
               out.writeInt(this.lo[i]);
               int len = this.hi[i] - this.lo[i] + 1;
               out.writeInt(len);
               for (int y = 0; y < len; y++) {
                  out.writeInt(ids[k++]);
               }
            }
         }
      }
   }

   /** Remet les colonnes d'une sauvegarde WHUB3 (l'en-tête « WHUB3 » est déjà lu). Renvoie {x, y, z} du centre. */
   static int[] restore(DataInputStream in, World[] worldOut) throws IOException {
      World world = Bukkit.getWorld(in.readUTF());
      if (world == null) {
         return null;
      }

      worldOut[0] = world;
      int x0 = in.readInt(), y0 = in.readInt(), z0 = in.readInt();
      BlockData[] pal = new BlockData[in.readInt()];
      for (int i = 0; i < pal.length; i++) {
         pal[i] = Bukkit.createBlockData(in.readUTF());
      }

      int cols = in.readInt();
      for (int c = 0; c < cols; c++) {
         int x = in.readShort(), z = in.readShort(), lo = in.readInt(), len = in.readInt();
         for (int y = 0; y < len; y++) {
            world.getBlockAt(x0 + x, lo + y, z0 + z).setBlockData(pal[in.readInt()], false);
         }
      }

      return new int[]{x0, y0, z0};
   }

   /** Sous le village : pierre jusqu'au sol naturel. Au-dessus : tout est dégagé. */
   void shapeInside() {
      int floor = this.cy - 1 - BASE;
      for (int z = -this.edge; z <= this.edge; z++) {
         for (int x = -this.edge; x <= this.edge; x++) {
            int i = this.idx(x, z);
            if (!this.in[i]) {
               continue;
            }

            boolean c = this.centre(x, z);
            int base = c ? this.cy - 1 : floor;
            for (int y = this.lo[i]; y <= this.hi[i]; y++) {
               Material m = y >= base ? Material.AIR : (c && y >= this.cy - 4 ? Material.DIRT : Material.STONE);
               Block b = this.w.getBlockAt(this.cx + x, y, this.cz + z);
               if (b.getType() != m) {
                  b.setType(m, false);
               }
            }
         }
      }
   }

   /** Raccorde les alentours. Renvoie le nombre de colonnes remodelées. */
   int shapeSkirt() {
      Random rnd = new Random(this.cx * 31L + this.cz);
      int done = 0;
      for (int z = -this.r; z <= this.r; z++) {
         for (int x = -this.r; x <= this.r; x++) {
            int i = this.idx(x, z);
            int tg = this.target[i];
            if (tg == Integer.MIN_VALUE) {
               continue;
            }

            done++;
            int wx = this.cx + x, wz = this.cz + z, g = this.nat[i];
            Material s0 = this.surf[i] == null ? Material.GRASS_BLOCK : this.surf[i];
            Material topM, under;
            if (Tag.SAND.isTagged(s0) || s0 == Material.GRAVEL || s0 == Material.SNOW_BLOCK || s0.name().endsWith("TERRACOTTA")) {
               topM = s0;
               under = s0 == Material.SAND ? Material.SANDSTONE : (s0 == Material.RED_SAND ? Material.RED_SANDSTONE : s0);
            } else if (Tag.DIRT.isTagged(s0) && s0 != Material.DIRT && s0 != Material.COARSE_DIRT && s0 != Material.ROOTED_DIRT) {
               topM = s0;
               under = Material.DIRT;
            } else if (s0 == Material.STONE || s0 == Material.DEEPSLATE || s0 == Material.ANDESITE || s0 == Material.GRANITE || s0 == Material.DIORITE
               || s0 == Material.TUFF || s0 == Material.CALCITE) {
               // roche à nu : on la garde en altitude, sinon de l'herbe
               topM = tg > this.cy + 24 ? s0 : Material.GRASS_BLOCK;
               under = topM == Material.GRASS_BLOCK ? Material.DIRT : s0;
            } else {
               topM = Material.GRASS_BLOCK;
               under = Material.DIRT;
            }

            boolean snow = g + 1 <= this.maxY && this.w.getBlockAt(wx, g + 1, wz).getType() == Material.SNOW;
            // au-dessus : plus rien (arbres et herbes de la colonne compris)
            for (int y = Math.max(tg, g) + 1; y <= this.hi[i]; y++) {
               Block b = this.w.getBlockAt(wx, y, wz);
               if (!b.getType().isAir()) {
                  b.setType(Material.AIR, false);
               }
            }

            if (tg > g) {
               for (int y = g; y <= tg - 4; y++) {
                  this.w.getBlockAt(wx, y, wz).setType(Material.STONE, false);
               }
            } else {
               for (int y = tg + 1; y <= g; y++) {
                  this.w.getBlockAt(wx, y, wz).setType(Material.AIR, false);
               }
            }

            for (int y = Math.max(tg - 3, this.lo[i]); y < tg; y++) {
               Block b = this.w.getBlockAt(wx, y, wz);
               if (tg > g ? y >= g : !Tag.BASE_STONE_OVERWORLD.isTagged(b.getType()) || y >= tg - 2) {
                  b.setType(under, false);
               }
            }

            this.w.getBlockAt(wx, tg, wz).setType(topM, false);
            if (tg + 1 <= this.hi[i]) {
               if (snow) {
                  this.w.getBlockAt(wx, tg + 1, wz).setType(Material.SNOW, false);
               } else if (topM == Material.GRASS_BLOCK && rnd.nextInt(9) == 0) {
                  this.w.getBlockAt(wx, tg + 1, wz).setType(Material.SHORT_GRASS, false);
               }
            }
         }
      }

      return done;
   }
}
