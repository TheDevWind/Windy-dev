package fr.windydev.clans.model;

import org.bukkit.Chunk;
import org.bukkit.Location;

public record ChunkPos(String world, int x, int z) {
   public static ChunkPos of(Location loc) {
      return new ChunkPos(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
   }

   public static ChunkPos of(Chunk c) {
      return new ChunkPos(c.getWorld().getName(), c.getX(), c.getZ());
   }

   public ChunkPos offset(int dx, int dz) {
      return new ChunkPos(this.world, this.x + dx, this.z + dz);
   }

   public int distance(ChunkPos o) {
      return !this.world.equals(o.world) ? Integer.MAX_VALUE : Math.max(Math.abs(this.x - o.x), Math.abs(this.z - o.z));
   }

   public String serialize() {
      return this.world + ";" + this.x + ";" + this.z;
   }

   public static ChunkPos deserialize(String s) {
      String[] p = s.split(";");
      return new ChunkPos(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
   }

   @Override
   public String toString() {
      return this.x + ", " + this.z;
   }
}
