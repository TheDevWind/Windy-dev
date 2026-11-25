fx_version 'cerulean'
game 'gta5'
lua54 'yes'

name 'usms-tablet'
description 'Tablette du U.S. Marshals Service : console du service, notes, appareil photo, calculatrice et autres applications'
version '2.0.0'

ui_page 'ui/index.html'
files { 'ui/index.html', 'ui/css/*.css', 'ui/js/*.js', 'ui/js/apps/*.js', 'ui/fonts/*.woff2', 'ui/img/*' }

shared_script 'config.lua'
client_scripts { 'bridge.lua', 'client.lua' }
server_script 'server.lua'
