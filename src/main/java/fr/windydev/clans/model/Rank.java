package fr.windydev.clans.model;

public enum Rank {
   RECRUE("Recrue", "&7"),
   MEMBRE("Membre", "&f"),
   OFFICIER("Officier", "&e"),
   CHEF("Chef", "&6");

   private final String display;
   private final String color;

   private Rank(String display, String color) {
      this.display = display;
      this.color = color;
   }

   public String display() {
      return this.display;
   }

   public String colored() {
      return this.color + this.display;
   }

   public String color() {
      return this.color;
   }

   public boolean atLeast(Rank other) {
      return this.ordinal() >= other.ordinal();
   }

   public Rank next() {
      return this == CHEF ? CHEF : values()[this.ordinal() + 1];
   }

   public Rank previous() {
      return this == RECRUE ? RECRUE : values()[this.ordinal() - 1];
   }

   public static Rank parse(String s, Rank def) {
      if (s == null) {
         return def;
      } else {
         try {
            return valueOf(s.trim().toUpperCase());
         } catch (IllegalArgumentException var3) {
            return def;
         }
      }
   }
}
