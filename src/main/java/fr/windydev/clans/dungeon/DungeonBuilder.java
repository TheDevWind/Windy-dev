package fr.windydev.clans.dungeon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Génère le plan d'un étage (suite de salles sur une grille) et construit les blocs.
 * Tout est construit par petites tâches (une salle ou un couloir à la fois) pour ne pas figer le serveur.
 */
final class DungeonBuilder {
   static final int GRID = 34;
   static final int ROOM_H = 7;
   static final int CORRIDOR_H = 5;

   /** Palette de blocs d'un thème. */
   static final class Theme {
      final Material[] floor;
      final Material[] wall;
      final Material pillar;
      final Material ceiling;
      final BlockData light;
      final boolean lightInCeiling;
      final Material bars;
      final Material[] deco;

      Theme(Material[] floor, Material[] wall, Material pillar, Material ceiling, BlockData light, boolean lightInCeiling, Material bars, Material[] deco) {
         this.floor = floor;
         this.wall = wall;
         this.pillar = pillar;
         this.ceiling = ceiling;
         this.light = light;
         this.lightInCeiling = lightInCeiling;
         this.bars = bars;
         this.deco = deco;
      }

      static Theme of(String id) {
         switch (id == null ? "" : id) {
            case "mines":
               return new Theme(
                  new Material[]{Material.COBBLED_DEEPSLATE, Material.GRAVEL, Material.COBBLED_DEEPSLATE, Material.TUFF, Material.COARSE_DIRT},
                  new Material[]{Material.TUFF_BRICKS, Material.TUFF, Material.COBBLED_DEEPSLATE, Material.DEEPSLATE_IRON_ORE, Material.TUFF_BRICKS},
                  Material.STRIPPED_SPRUCE_LOG,
                  Material.DEEPSLATE,
                  Bukkit.createBlockData("minecraft:lantern[hanging=true]"),
                  false,
                  Material.IRON_BARS,
                  new Material[]{Material.COBWEB, Material.DEEPSLATE_COPPER_ORE, Material.DEEPSLATE_GOLD_ORE}
               );
            case "citadelle":
               return new Theme(
                  new Material[]{Material.PACKED_ICE, Material.POLISHED_DIORITE, Material.PACKED_ICE, Material.BLUE_ICE, Material.SNOW_BLOCK},
                  new Material[]{Material.SNOW_BLOCK, Material.PACKED_ICE, Material.POLISHED_DIORITE, Material.SNOW_BLOCK},
                  Material.QUARTZ_PILLAR,
                  Material.PACKED_ICE,
                  Material.SEA_LANTERN.createBlockData(),
                  true,
                  Material.IRON_BARS,
                  new Material[]{Material.ICE, Material.BLUE_ICE}
               );
            default:
               return new Theme(
                  new Material[]{Material.DEEPSLATE_TILES, Material.CRACKED_DEEPSLATE_TILES, Material.DEEPSLATE_TILES, Material.POLISHED_DEEPSLATE, Material.MOSSY_COBBLESTONE},
                  new Material[]{Material.DEEPSLATE_BRICKS, Material.CRACKED_DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICKS, Material.MOSSY_STONE_BRICKS},
                  Material.POLISHED_BASALT,
                  Material.DEEPSLATE_TILES,
                  Bukkit.createBlockData("minecraft:soul_lantern[hanging=true]"),
                  false,
                  Material.IRON_BARS,
                  new Material[]{Material.COBWEB, Material.BONE_BLOCK, Material.SKELETON_SKULL}
               );
         }
      }
   }

   /** Plan d'un étage : salles dans l'ordre + couloirs entre elles. */
   static List<Room> plan(int floorIndex, int baseX, int baseY, int baseZ, int combatRooms, Random rnd) {
      List<Room.Type> types = new ArrayList<>();
      types.add(Room.Type.ENTRANCE);
      int treasureAt = 1 + rnd.nextInt(Math.max(1, combatRooms));

      for (int i = 0; i < combatRooms; i++) {
         if (i == treasureAt) {
            types.add(Room.Type.TREASURE);
         }

         types.add(Room.Type.COMBAT);
      }

      if (!types.contains(Room.Type.TREASURE)) {
         types.add(types.size(), Room.Type.TREASURE);
      }

      types.add(Room.Type.BOSS);
      List<int[]> cells = new ArrayList<>();
      Set<Long> used = new HashSet<>();
      int gx = 0;
      int gz = 0;
      int last = 0;
      cells.add(new int[]{gx, gz});
      used.add(pack(gx, gz));

      for (int i = 1; i < types.size(); i++) {
         List<Integer> options = new ArrayList<>();
         options.add(0);
         options.add(0);
         if (last != 2 && !used.contains(pack(gx, gz - 1)) && Math.abs(gz - 1) <= 3) {
            options.add(1);
         }

         if (last != 1 && !used.contains(pack(gx, gz + 1)) && Math.abs(gz + 1) <= 3) {
            options.add(2);
         }

         int dir = options.get(rnd.nextInt(options.size()));
         if (dir == 0) {
            gx++;
         } else if (dir == 1) {
            gz--;
         } else {
            gz++;
         }

         last = dir;
         used.add(pack(gx, gz));
         cells.add(new int[]{gx, gz});
      }

      List<Room> rooms = new ArrayList<>();

      for (int i = 0; i < types.size(); i++) {
         Room.Type t = types.get(i);
         int half = t == Room.Type.BOSS ? 12 : 9;
         int[] c = cells.get(i);
         rooms.add(new Room(i, floorIndex, t, baseX + c[0] * GRID, baseY, baseZ + c[1] * GRID, half));
      }

      for (int i = 0; i + 1 < rooms.size(); i++) {
         Room a = rooms.get(i);
         Room b = rooms.get(i + 1);
         int dx = Integer.signum(b.cx - a.cx);
         int dz = Integer.signum(b.cz - a.cz);
         a.exit = doorway(a, dx, dz);
         b.entry = doorway(b, -dx, -dz);
         a.nextDx = dx;
         a.nextDz = dz;
      }

      return rooms;
   }

   private static long pack(int x, int z) {
      return (long)x << 32 | z & 4294967295L;
   }

   /** Les 3x3 blocs de l'ouverture dans le mur de la salle, côté (dx, dz). */
   private static List<int[]> doorway(Room r, int dx, int dz) {
      List<int[]> out = new ArrayList<>();
      int wx = r.cx + dx * r.half;
      int wz = r.cz + dz * r.half;

      for (int s = -1; s <= 1; s++) {
         for (int y = 1; y <= 3; y++) {
            out.add(dx != 0 ? new int[]{wx, r.y0 + y, wz + s} : new int[]{wx + s, r.y0 + y, wz});
         }
      }

      return out;
   }

   /** Tâches de construction d'un étage : chaque salle, puis chaque couloir. */
   static List<Runnable> buildTasks(World w, List<Room> rooms, Theme th, Random rnd) {
      List<Runnable> tasks = new ArrayList<>();

      for (Room r : rooms) {
         long seed = rnd.nextLong();
         tasks.add(() -> buildRoom(w, r, th, new Random(seed)));
      }

      for (int i = 0; i + 1 < rooms.size(); i++) {
         Room a = rooms.get(i);
         Room b = rooms.get(i + 1);
         long seed = rnd.nextLong();
         tasks.add(() -> buildCorridor(w, a, b, th, new Random(seed)));
      }

      tasks.add(() -> {
         for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            boolean open = i == 0;
            if (r.exit != null) {
               setDoor(w, r.exit, open ? Material.AIR : th.bars);
            }

            if (r.entry != null) {
               setDoor(w, r.entry, i == 1 ? Material.AIR : th.bars);
            }
         }
      });
      return tasks;
   }

   static void setDoor(World w, List<int[]> door, Material m) {
      for (int[] p : door) {
         w.getBlockAt(p[0], p[1], p[2]).setType(m, m != Material.AIR);
      }
   }

   private static void buildRoom(World w, Room r, Theme th, Random rnd) {
      int h = r.half;
      int H = ROOM_H;

      for (int x = -h; x <= h; x++) {
         for (int z = -h; z <= h; z++) {
            boolean edge = Math.abs(x) == h || Math.abs(z) == h;
            int bx = r.cx + x;
            int bz = r.cz + z;
            w.getBlockAt(bx, r.y0, bz).setType(pick(th.floor, rnd), false);
            w.getBlockAt(bx, r.y0 + H, bz).setType(th.ceiling, false);

            for (int y = 1; y < H; y++) {
               w.getBlockAt(bx, r.y0 + y, bz).setType(edge ? pick(th.wall, rnd) : Material.AIR, false);
            }

            if (!edge && x % 6 == 0 && z % 6 == 0) {
               if (th.lightInCeiling) {
                  w.getBlockAt(bx, r.y0 + H, bz).setBlockData(th.light, false);
               } else {
                  w.getBlockAt(bx, r.y0 + H - 1, bz).setBlockData(th.light, false);
               }
            }
         }
      }

      int p = h - 2;

      for (int[] c : new int[][]{{p, p}, {p, -p}, {-p, p}, {-p, -p}}) {
         for (int y = 1; y < H; y++) {
            w.getBlockAt(r.cx + c[0], r.y0 + y, r.cz + c[1]).setType(th.pillar, false);
         }
      }

      for (int i = 0; i < 6; i++) {
         int x = rnd.nextInt(2 * h - 1) - h + 1;
         int z = rnd.nextInt(2 * h - 1) - h + 1;
         if (Math.abs(x) >= h - 1 || Math.abs(z) >= h - 1) {
            Material d = pick(th.deco, rnd);
            int y = d == Material.COBWEB ? r.y0 + H - 1 : r.y0 + 1;
            Block b = w.getBlockAt(r.cx + x, y, r.cz + z);
            if (b.getType().isAir()) {
               b.setType(d, false);
            }
         }
      }

      if (r.type == Room.Type.ENTRANCE || r.type == Room.Type.BOSS) {
         Material m = r.type == Room.Type.BOSS ? Material.CRYING_OBSIDIAN : Material.CHISELED_TUFF_BRICKS;

         for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
               w.getBlockAt(r.cx + x, r.y0, r.cz + z).setType(m, false);
            }
         }

         w.getBlockAt(r.cx, r.y0, r.cz).setType(r.type == Room.Type.BOSS ? Material.SHROOMLIGHT : Material.LODESTONE, false);
      }
   }

   private static void buildCorridor(World w, Room a, Room b, Theme th, Random rnd) {
      int dx = Integer.signum(b.cx - a.cx);
      int dz = Integer.signum(b.cz - a.cz);
      int from = dx != 0 ? a.cx + dx * a.half : a.cz + dz * a.half;
      int to = dx != 0 ? b.cx - dx * b.half : b.cz - dz * b.half;
      int step = dx != 0 ? dx : dz;
      int y0 = a.y0;

      for (int m = from + step; m != to; m += step) {
         for (int s = -2; s <= 2; s++) {
            int bx = dx != 0 ? m : a.cx + s;
            int bz = dx != 0 ? a.cz + s : m;
            boolean edge = Math.abs(s) == 2;
            w.getBlockAt(bx, y0, bz).setType(pick(th.floor, rnd), false);
            w.getBlockAt(bx, y0 + CORRIDOR_H, bz).setType(th.ceiling, false);

            for (int y = 1; y < CORRIDOR_H; y++) {
               w.getBlockAt(bx, y0 + y, bz).setType(edge ? pick(th.wall, rnd) : Material.AIR, false);
            }

            if (s == 0 && Math.floorMod(m, 5) == 0) {
               if (th.lightInCeiling) {
                  w.getBlockAt(bx, y0 + CORRIDOR_H, bz).setBlockData(th.light, false);
               } else {
                  w.getBlockAt(bx, y0 + CORRIDOR_H - 1, bz).setBlockData(th.light, false);
               }
            }
         }
      }

      for (int[] p : a.exit) {
         w.getBlockAt(p[0], p[1], p[2]).setType(Material.AIR, false);
      }

      for (int[] p : b.entry) {
         w.getBlockAt(p[0], p[1], p[2]).setType(Material.AIR, false);
      }
   }

   /** Tâches d'effacement (air) de tout un étage. */
   static List<Runnable> clearTasks(World w, List<Room> rooms) {
      List<Runnable> tasks = new ArrayList<>();

      for (Room r : rooms) {
         tasks.add(() -> fill(w, r.cx - r.half, r.y0, r.cz - r.half, r.cx + r.half, r.y0 + ROOM_H, r.cz + r.half));
      }

      for (int i = 0; i + 1 < rooms.size(); i++) {
         Room a = rooms.get(i);
         Room b = rooms.get(i + 1);
         tasks.add(() -> fill(w, Math.min(a.cx, b.cx) - 2, a.y0, Math.min(a.cz, b.cz) - 2, Math.max(a.cx, b.cx) + 2, a.y0 + CORRIDOR_H, Math.max(a.cz, b.cz) + 2));
      }

      return tasks;
   }

   private static void fill(World w, int x0, int y0, int z0, int x1, int y1, int z1) {
      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
               Block b = w.getBlockAt(x, y, z);
               if (!b.getType().isAir()) {
                  b.setType(Material.AIR, false);
               }
            }
         }
      }
   }

   private static Material pick(Material[] arr, Random rnd) {
      return arr[rnd.nextInt(arr.length)];
   }
}
