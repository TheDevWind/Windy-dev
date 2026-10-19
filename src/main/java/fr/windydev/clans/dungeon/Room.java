package fr.windydev.clans.dungeon;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

/** Une salle d'un étage de donjon. */
final class Room {
   enum Type {
      ENTRANCE,
      COMBAT,
      TREASURE,
      BOSS;
   }

   enum State {
      IDLE,
      WAITING,
      FIGHT,
      CLEARED;
   }

   final int index;
   final int floor;
   final Type type;
   final int cx;
   final int y0;
   final int cz;
   final int half;
   List<int[]> entry;
   List<int[]> exit;
   int nextDx;
   int nextDz;
   State state = State.IDLE;
   long waitUntil;
   final Set<UUID> mobs = new HashSet<>();
   UUID boss;

   Room(int index, int floor, Type type, int cx, int y0, int cz, int half) {
      this.index = index;
      this.floor = floor;
      this.type = type;
      this.cx = cx;
      this.y0 = y0;
      this.cz = cz;
      this.half = half;
      if (type == Type.ENTRANCE) {
         this.state = State.CLEARED;
      }
   }

   boolean inside(Location l) {
      return Math.abs(l.getBlockX() - this.cx) < this.half
         && Math.abs(l.getBlockZ() - this.cz) < this.half
         && l.getY() >= this.y0
         && l.getY() <= this.y0 + DungeonBuilder.ROOM_H;
   }

   Location center(World w) {
      return new Location(w, this.cx + 0.5, this.y0 + 1, this.cz + 0.5);
   }

   /** Point d'arrivée près de l'entrée de la salle, tourné vers le centre. */
   Location arrival(World w) {
      Location l = this.center(w);
      if (this.entry != null && !this.entry.isEmpty()) {
         int[] d = this.entry.get(this.entry.size() / 2);
         double ex = d[0] + 0.5 - l.getX();
         double ez = d[2] + 0.5 - l.getZ();
         double len = Math.max(1.0, Math.sqrt(ex * ex + ez * ez));
         l.add(ex / len * (this.half - 3), 0.0, ez / len * (this.half - 3));
      }

      org.bukkit.util.Vector v = this.center(w).toVector().subtract(l.toVector()).setY(0);
      if (v.lengthSquared() > 0.1) {
         l.setDirection(v);
      } else if (this.nextDx != 0 || this.nextDz != 0) {
         l.setDirection(new org.bukkit.util.Vector(this.nextDx, 0, this.nextDz));
      }

      return l;
   }
}
