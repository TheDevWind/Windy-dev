-- Seul fichier à adapter pour brancher la tablette sur un autre serveur : comment connaître le métier du joueur.
Bridge = {}

local function framework()
  if Config.Framework ~= 'auto' then return Config.Framework end
  if GetResourceState('es_extended') == 'started' then return 'esx' end
  if GetResourceState('qb-core') == 'started' or GetResourceState('qbx_core') == 'started' then return 'qb' end
  return 'standalone'
end

-- Nom du métier du joueur (ou nil si on ne peut pas le savoir)
function Bridge.GetJob()
  local fw = framework()
  if fw == 'esx' then
    local ok, ESX = pcall(function() return exports['es_extended']:getSharedObject() end)
    if ok and ESX then
      local data = ESX.GetPlayerData()
      return data and data.job and data.job.name or nil
    end
  elseif fw == 'qb' then
    local ok, QB = pcall(function() return exports['qb-core']:GetCoreObject() end)
    if ok and QB then
      local data = QB.Functions.GetPlayerData()
      return data and data.job and data.job.name or nil
    end
  end
  return nil
end

-- Le joueur peut-il ouvrir la tablette ?
function Bridge.CanUse()
  if #Config.AllowedJobs == 0 then return true end
  local job = Bridge.GetJob()
  for _, allowed in ipairs(Config.AllowedJobs) do
    if allowed == job then return true end
  end
  return false
end
