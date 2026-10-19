package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.bukkit.Bukkit;

public class DiscordWebhook {
   private final WindyClans plugin;
   private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).build();

   public DiscordWebhook(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void send(String event, String content) {
      if (this.plugin.bot() != null) {
         this.plugin.bot().announce(event, content);
      }

      String url = this.plugin.settings().webhook;
      if (url != null && !url.isBlank()) {
         if (this.plugin.settings().discordEvents.getOrDefault(event, true)) {
            String body = "{\"username\":"
               + json(this.plugin.settings().webhookName)
               + ",\"content\":"
               + json(content)
               + ",\"allowed_mentions\":{\"parse\":[]}}";
            Bukkit.getScheduler()
               .runTaskAsynchronously(
                  this.plugin,
                  () -> {
                     try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                           .timeout(Duration.ofSeconds(10L))
                           .header("Content-Type", "application/json")
                           .POST(BodyPublishers.ofString(body))
                           .build();
                        HttpResponse<String> res = this.http.send(req, BodyHandlers.ofString());
                        if (res.statusCode() >= 300) {
                           this.plugin.getLogger().warning("Webhook Discord : réponse " + res.statusCode());
                        }
                     } catch (Exception var5) {
                        this.plugin.getLogger().warning("Webhook Discord : " + var5.getMessage());
                     }
                  }
               );
         }
      }
   }

   private static String json(String s) {
      StringBuilder sb = new StringBuilder("\"");

      for (char ch : s.toCharArray()) {
         switch (ch) {
            case '\n':
               sb.append("\\n");
            case '\r':
               break;
            case '"':
               sb.append("\\\"");
               break;
            case '\\':
               sb.append("\\\\");
               break;
            default:
               if (ch < ' ') {
                  sb.append(String.format("\\u%04x", Integer.valueOf(ch)));
               } else {
                  sb.append(ch);
               }
         }
      }

      return sb.append('"').toString();
   }
}
