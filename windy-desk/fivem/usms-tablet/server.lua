-- Objet utilisable (facultatif) : ESX ou QBCore. Sans Config.Item, rien à faire ici.
CreateThread(function()
  if not Config.Item then return end
  local function open(source) TriggerClientEvent('usms_tablet:open', source) end
  if GetResourceState('es_extended') == 'started' then
    local ok, ESX = pcall(function() return exports['es_extended']:getSharedObject() end)
    if ok and ESX then ESX.RegisterUsableItem(Config.Item, open) return end
  end
  if GetResourceState('qb-core') == 'started' then
    local ok, QB = pcall(function() return exports['qb-core']:GetCoreObject() end)
    if ok and QB then QB.Functions.CreateUseableItem(Config.Item, open) return end
  end
  print(('[usms_tablet] Config.Item = %s mais aucun framework compatible n\'est démarré : l\'objet n\'est pas enregistré.'):format(Config.Item))
end)
