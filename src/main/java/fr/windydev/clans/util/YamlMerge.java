package fr.windydev.clans.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Ajoute au fichier du serveur les nouvelles entrées d'une section (recettes, rituels) livrées avec le plugin. */
public final class YamlMerge {
   private YamlMerge() {
   }

   /**
    * Une entrée n'est ajoutée qu'une fois : son id est noté dans "auto-added".
    * Si l'admin la supprime ensuite, elle ne revient pas.
    */
   public static YamlConfiguration addNew(JavaPlugin plugin, File f, YamlConfiguration y, String resource, String section) {
      try (InputStream in = plugin.getResource(resource)) {
         if (in == null) {
            return y;
         }

         YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
         ConfigurationSection ds = def.getConfigurationSection(section);
         if (ds == null) {
            return y;
         }

         List<String> done = new ArrayList<>(y.getStringList("auto-added"));
         List<String> added = new ArrayList<>();
         for (String k : ds.getKeys(false)) {
            String path = section + "." + k;
            if (!y.contains(path, true) && !done.contains(k)) {
               y.set(path, ds.get(k));
               added.add(k);
            }

            if (!done.contains(k)) {
               done.add(k);
            }
         }

         if (!done.equals(y.getStringList("auto-added"))) {
            y.set("auto-added", done);
            y.save(f);
            if (!added.isEmpty()) {
               plugin.getLogger().info(resource + " : ajouté(s) " + String.join(", ", added));
            }
         }
      } catch (Exception e) {
         plugin.getLogger().warning(resource + " : fusion des nouveautés impossible : " + e.getMessage());
      }

      return y;
   }

   /**
    * Ajoute au fichier du serveur les réglages (chemins complets) livrés avec une nouvelle version du plugin,
    * avec leurs commentaires, sans toucher au reste. Rend vrai si le fichier a été modifié.
    */
   public static boolean addMissing(JavaPlugin plugin, File f, YamlConfiguration y, String resource, List<String> paths) {
      try (InputStream in = plugin.getResource(resource)) {
         if (in == null) {
            return false;
         }

         YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
         List<String> added = new ArrayList<>();
         for (String path : paths) {
            if (!y.contains(path, true) && def.contains(path, true)) {
               y.set(path, def.get(path));
               List<String> c = def.getComments(path);
               if (!c.isEmpty()) {
                  y.setComments(path, c);
               }

               added.add(path);
            }
         }

         if (!added.isEmpty()) {
            y.save(f);
            plugin.getLogger().info(resource + " : réglages ajoutés : " + String.join(", ", added));
            return true;
         }
      } catch (Exception e) {
         plugin.getLogger().warning(resource + " : ajout des nouveaux réglages impossible : " + e.getMessage());
      }

      return false;
   }
}
