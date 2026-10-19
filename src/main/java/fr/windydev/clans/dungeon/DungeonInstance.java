package fr.windydev.clans.dungeon;

import fr.windydev.clans.model.Clan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;

/** Une partie de donjon en cours (lobby, construction, exploration, fin). */
final class DungeonInstance {
   enum State {
      LOBBY,
      RUNNING,
      ENDING;
   }

   final int slot;
   final DungeonDef def;
   final Clan clan;
   final UUID leader;
   final Set<UUID> participants = new LinkedHashSet<>();
   final Map<UUID, Location> returns = new HashMap<>();
   final List<List<Room>> floors = new ArrayList<>();
   final Deque<Runnable> build = new ArrayDeque<>();
   final Map<String, Long> nextUse = new HashMap<>();
   State state = State.LOBBY;
   long lobbyEnd;
   long startedAt;
   long deadline;
   int lives;
   int floor;
   Location checkpoint;
   BossBar bar;
   BossBar bossBar;
   boolean sealTaken;
   long floorTransitionAt;

   DungeonInstance(int slot, DungeonDef def, Clan clan, UUID leader) {
      this.slot = slot;
      this.def = def;
      this.clan = clan;
      this.leader = leader;
      this.participants.add(leader);
   }

   List<Room> currentRooms() {
      return this.floor < this.floors.size() ? this.floors.get(this.floor) : List.of();
   }

   int monsterLevel() {
      return this.def.floors.get(Math.min(this.floor, this.def.floors.size() - 1));
   }
}
