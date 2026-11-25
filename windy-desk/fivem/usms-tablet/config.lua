Config = {}

-- Adresse de la console : le site avec l'habillage de la tablette (?marque=usms) et le code du service à ouvrir (?service=).
Config.Url = 'https://desk.windydev.fr/?marque=usms&service=us-marshal-ls'

-- Ouverture : commande dans le chat, touche par défaut (le joueur peut la changer dans les paramètres de touches du jeu),
-- et/ou objet utilisable (ESX ou QBCore). Mets nil pour désactiver un moyen d'ouverture.
Config.Command = 'tablette'
Config.Key = 'F9'
Config.Item = nil                 -- par exemple 'tablet' : l'objet doit exister dans ton inventaire

-- Qui peut l'ouvrir : liste de noms de métiers (voir bridge.lua). Liste vide = tout le monde.
Config.AllowedJobs = {}           -- par exemple { 'usmarshal', 'marshal' }

-- Framework : 'auto' le détecte, sinon 'esx', 'qb' ou 'standalone' (aucun contrôle de métier).
Config.Framework = 'auto'

-- Tablette tenue en main pendant l'utilisation (positions à ajuster en jeu si besoin).
Config.Prop = { enabled = true, model = 'prop_cs_tablet', bone = 60309, pos = vec3(0.03, 0.002, 0.0), rot = vec3(10.0, 160.0, 0.0) }
Config.Anim = { dict = 'amb@code_human_in_bus_passenger_idles@female@tablet@base', name = 'base' }

-- Diagnostic : affiche en bas de la tablette l'origine de la page, ses ancêtres et si le site est joignable. À couper une fois que tout marche.
Config.Debug = false

-- Appareil photo : demande la ressource gratuite « screenshot-basic » (https://github.com/citizenfx/screenshot-basic). Sans elle, l'application s'ouvre puis se referme avec un message.
Config.Camera = { quality = 0.85 }

Config.Messages = {
  denied = 'Cette tablette est réservée au personnel du service.',
  dead = 'Impossible d’utiliser la tablette dans cet état.',
}
