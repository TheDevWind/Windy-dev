fx_version 'cerulean'
game 'gta5'
lua54 'yes'

name 'usms-tablet'
description 'Tablette du U.S. Marshals Service : ouvre la console du service dans le jeu'
version '1.0.0'

ui_page 'ui/index.html'
files { 'ui/index.html', 'ui/style.css', 'ui/app.js' }

shared_script 'config.lua'
client_scripts { 'bridge.lua', 'client.lua' }
server_script 'server.lua'
