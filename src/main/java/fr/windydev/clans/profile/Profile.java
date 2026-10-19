package fr.windydev.clans.profile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Profile {
   public static final String[] SOURCES = new String[]{"combat", "metiers", "rituels", "temps"};
   final UUID id;
   String name;
   String race;
   int level = 1;
   long xp;
   long totalXp;
   int raceChanges;
   long playMinutes;
   int mobKills;
   int bossWins;
   int rituals;
   int rank = 1;
   long souls;
   long awakenRetryAt;
   final Map<String, Integer> skills = new HashMap<>();
   final Map<String, Long> sources = new HashMap<>();

   Profile(UUID id, String name) {
      this.id = id;
      this.name = name;
   }

   public UUID id() {
      return this.id;
   }

   public String name() {
      return this.name;
   }

   public String race() {
      return this.race;
   }

   public int level() {
      return this.level;
   }

   public long xp() {
      return this.xp;
   }

   public long totalXp() {
      return this.totalXp;
   }

   public int raceChanges() {
      return this.raceChanges;
   }

   public long playMinutes() {
      return this.playMinutes;
   }

   public int mobKills() {
      return this.mobKills;
   }

   public int bossWins() {
      return this.bossWins;
   }

   public int rituals() {
      return this.rituals;
   }

   public long source(String s) {
      return this.sources.getOrDefault(s, 0L);
   }

   public int rank() {
      return this.rank;
   }

   public long souls() {
      return this.souls;
   }

   public long awakenRetryAt() {
      return this.awakenRetryAt;
   }

   public int skill(String node) {
      return this.skills.getOrDefault(node, 0);
   }

   public Map<String, Integer> skills() {
      return this.skills;
   }
}
