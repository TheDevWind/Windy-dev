package fr.windydev.clans.model;

public enum Relation {
   SELF("&a", "Ton clan"),
   ALLY("&b", "Allié"),
   NEUTRAL("&f", "Neutre"),
   ENEMY("&c", "Ennemi"),
   WAR("&4", "En guerre");

   private final String color;
   private final String label;

   private Relation(String color, String label) {
      this.color = color;
      this.label = label;
   }

   public String color() {
      return this.color;
   }

   public String label() {
      return this.label;
   }
}
