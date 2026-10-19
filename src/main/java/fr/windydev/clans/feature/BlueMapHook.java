package fr.windydev.clans.feature;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker.Builder;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;

public class BlueMapHook implements MapHook {
   private static final String SET_ID = "windyclans";
   private final WindyClans plugin;
   private boolean scheduled;

   public BlueMapHook(WindyClans plugin) {
      this.plugin = plugin;
      BlueMapAPI.onEnable(api -> Bukkit.getScheduler().runTask(plugin, this::refresh));
   }

   @Override
   public void refresh() {
      if (!this.scheduled) {
         this.scheduled = true;
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            this.scheduled = false;
            this.update();
         }, 40L);
      }
   }

   private void update() {
      BlueMapAPI.getInstance()
         .ifPresent(
            api -> {
               for (World world : Bukkit.getWorlds()) {
                  api.getWorld(world)
                     .ifPresent(
                        bw -> {
                           MarkerSet set = MarkerSet.builder().label(this.plugin.settings().bluemapLabel).toggleable(true).build();

                           for (Clan c : this.plugin.clans().all()) {
                              Color line = color(c, 1.0F);
                              Color fill = color(c, (float)this.plugin.settings().bluemapOpacity);

                              for (ChunkPos p : c.claims()) {
                                 if (p.world().equals(world.getName())) {
                                    boolean cap = this.plugin.clans().isCapitalZone(c, p);
                                    String label = c.name() + (cap ? " (capitale)" : "");
                                    ShapeMarker m = ((Builder)((Builder)ShapeMarker.builder().label(label))
                                          .detail(
                                             "<b>"
                                                + Text.strip(c.name())
                                                + "</b> ["
                                                + c.tag()
                                                + "]<br>Niveau "
                                                + c.level()
                                                + "<br>"
                                                + c.members().size()
                                                + " membre(s)"
                                                + (cap ? "<br><i>Capitale</i>" : "")
                                          ))
                                       .shape(Shape.createRect(p.x() * 16, p.z() * 16, p.x() * 16 + 16, p.z() * 16 + 16), 64.0F)
                                       .depthTestEnabled(false)
                                       .lineColor(line)
                                       .fillColor(fill)
                                       .lineWidth(cap ? 3 : 1)
                                       .build();
                                    m.setPosition(p.x() * 16 + 8.0, 64.0, p.z() * 16 + 8.0);
                                    set.getMarkers().put(c.id() + "_" + p.x() + "_" + p.z(), m);
                                 }
                              }
                           }

                           for (BlueMapMap map : bw.getMaps()) {
                              map.getMarkerSets().put("windyclans", set);
                           }
                        }
                     );
               }
            }
         );
   }

   private static Color color(Clan c, float alpha) {
      int rgb = Text.rgb(c.color());
      return new Color(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, alpha);
   }
}
