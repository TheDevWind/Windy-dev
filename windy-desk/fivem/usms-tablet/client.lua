local isOpen = false
local prop = nil

local function notify(text)
  BeginTextCommandThefeedPost('STRING')
  AddTextComponentSubstringPlayerName(text)
  EndTextCommandThefeedPostTicker(false, false)
end

local function loadAnim(dict)
  RequestAnimDict(dict)
  local limit = GetGameTimer() + 5000
  while not HasAnimDictLoaded(dict) and GetGameTimer() < limit do Wait(0) end
  return HasAnimDictLoaded(dict)
end

local function loadModel(name)
  local hash = GetHashKey(name)
  RequestModel(hash)
  local limit = GetGameTimer() + 5000
  while not HasModelLoaded(hash) and GetGameTimer() < limit do Wait(0) end
  return HasModelLoaded(hash) and hash or nil
end

local function removeProp()
  if prop and DoesEntityExist(prop) then DeleteEntity(prop) end
  prop = nil
end

local function startHolding()
  local ped = PlayerPedId()
  if IsPedInAnyVehicle(ped, false) then return end   -- en voiture : pas d'animation, la tablette s'affiche seulement
  if Config.Anim and loadAnim(Config.Anim.dict) then
    TaskPlayAnim(ped, Config.Anim.dict, Config.Anim.name, 3.0, 3.0, -1, 49, 0, false, false, false)
  end
  if Config.Prop and Config.Prop.enabled then
    local hash = loadModel(Config.Prop.model)
    if hash then
      local coords = GetEntityCoords(ped)
      prop = CreateObject(hash, coords.x, coords.y, coords.z + 0.2, true, true, false)
      AttachEntityToEntity(prop, ped, GetPedBoneIndex(ped, Config.Prop.bone), Config.Prop.pos.x, Config.Prop.pos.y, Config.Prop.pos.z,
        Config.Prop.rot.x, Config.Prop.rot.y, Config.Prop.rot.z, true, true, false, true, 1, true)
      SetModelAsNoLongerNeeded(hash)
    end
  end
end

local function closeTablet()
  if not isOpen then return end
  isOpen = false
  SetNuiFocus(false, false)
  SendNUIMessage({ action = 'close' })
  local ped = PlayerPedId()
  if Config.Anim then StopAnimTask(ped, Config.Anim.dict, Config.Anim.name, 3.0) end
  removeProp()
end

local function openTablet()
  if isOpen then return end
  local ped = PlayerPedId()
  if IsPedDeadOrDying(ped, true) then notify(Config.Messages.dead) return end
  if not Bridge.CanUse() then notify(Config.Messages.denied) return end
  isOpen = true
  startHolding()
  SetNuiFocus(true, true)
  SendNUIMessage({ action = 'open', url = Config.Url })
  CreateThread(function()   -- la tablette se range si le joueur tombe
    while isOpen do
      if IsPedDeadOrDying(PlayerPedId(), true) then closeTablet() end
      Wait(500)
    end
  end)
end

RegisterNUICallback('close', function(_, cb)
  closeTablet()
  cb('ok')
end)

if Config.Command then
  RegisterCommand(Config.Command, function()
    if isOpen then closeTablet() else openTablet() end
  end, false)
  if Config.Key then
    RegisterKeyMapping(Config.Command, 'Ouvrir la tablette du service', 'keyboard', Config.Key)
  end
end

RegisterNetEvent('usms_tablet:open', openTablet)
exports('Open', openTablet)
exports('Close', closeTablet)
exports('IsOpen', function() return isOpen end)

AddEventHandler('onResourceStop', function(name)
  if name ~= GetCurrentResourceName() then return end
  SetNuiFocus(false, false)
  removeProp()
end)
