package fr.windydev.clans.feature;

public interface MapHook {
   MapHook NONE = () -> {};

   void refresh();
}
