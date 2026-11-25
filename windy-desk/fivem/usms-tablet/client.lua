local isOpen = false
local prop = nil
local camMode, camBusy, locating = false, false, false
local savedView = nil

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


-- ---------------------------------------------------------------- informations du jeu pour les applications
local WEATHERS = { 'EXTRASUNNY', 'CLEAR', 'CLOUDS', 'SMOG', 'FOGGY', 'OVERCAST', 'RAIN', 'THUNDER', 'CLEARING', 'NEUTRAL', 'SNOW', 'BLIZZARD', 'SNOWLIGHT', 'XMAS' }
local function currentWeather()
  local hash = GetPrevWeatherTypeHashName() & 0xFFFFFFFF
  for _, name in ipairs(WEATHERS) do
    if (GetHashKey(name) & 0xFFFFFFFF) == hash then return name end
  end
  return 'CLEAR'
end

local function sendWorld()
  SendNUIMessage({ action = 'world', hour = GetClockHours(), minute = GetClockMinutes(), weather = currentWeather() })
end

-- Application « Localisation » : la position est envoyée tant que l'application est ouverte
local function startLocate()
  if locating then return end
  locating = true
  CreateThread(function()
    while locating and isOpen do
      local ped = PlayerPedId()
      local c = GetEntityCoords(ped)
      local s1, s2 = GetStreetNameAtCoord(c.x, c.y, c.z)
      SendNUIMessage({
        action = 'position', x = c.x, y = c.y, z = c.z,
        bearing = (360.0 - GetEntityHeading(ped)) % 360.0,   -- le cap du jeu tourne à l'envers de celui d'une boussole
        street = GetStreetNameFromHashKey(s1), crossing = (s2 and s2 ~= 0) and GetStreetNameFromHashKey(s2) or nil,
        zone = GetLabelText(GetNameOfZone(c.x, c.y, c.z)),
      })
      Wait(400)
    end
    locating = false
  end)
end

-- ---------------------------------------------------------------- appareil photo
local function leaveCamera()
  if not camMode then return end
  camMode = false
  if savedView then SetFollowPedCamViewMode(savedView) savedView = nil end
  SetNuiFocus(true, true)
  SendNUIMessage({ action = 'camera', state = 'off' })
end

local function takePhoto()
  if camBusy then return end
  camBusy = true
  SendNUIMessage({ action = 'camera', state = 'hide' })   -- le viseur ne doit pas apparaître sur la photo
  Wait(150)
  local done = false
  local function finish(data)
    if done then return end
    done = true
    camBusy = false
    SendNUIMessage({ action = 'camera', state = 'show' })
    if data then
      PlaySoundFrontend(-1, 'Camera_Shoot', 'Phone_SoundSet_Michael', true)
      SendNUIMessage({ action = 'shutter' })
      SendNUIMessage({ action = 'photo', data = data })
    end
  end
  exports['screenshot-basic']:requestScreenshot({ encoding = 'jpg', quality = (Config.Camera and Config.Camera.quality) or 0.85 }, finish)
  SetTimeout(6000, function() finish(nil) end)   -- si la capture ne répond pas, on rend la main
end

local function startCamera()
  camMode = true
  savedView = GetFollowPedCamViewMode()
  SetFollowPedCamViewMode(4)   -- vue à la première personne
  SetNuiFocus(false, false)    -- la tablette se range, le joueur reprend le contrôle du jeu
  SendNUIMessage({ action = 'camera', state = 'on' })
  CreateThread(function()
    while camMode do
      for _, control in ipairs({ 24, 25, 37, 140, 141, 142, 257, 263, 264 }) do DisableControlAction(0, control, true) end   -- ni tir ni coup pendant la visée
      if IsControlJustPressed(0, 176) then takePhoto()            -- Entrée
      elseif IsControlJustPressed(0, 177) or IsPedDeadOrDying(PlayerPedId(), true) then leaveCamera() end   -- Retour arrière
      Wait(0)
    end
  end)
end

local function closeTablet()
  if not isOpen then return end
  isOpen = false
  locating = false
  if camMode then camMode = false if savedView then SetFollowPedCamViewMode(savedView) savedView = nil end SendNUIMessage({ action = 'camera', state = 'off' }) end
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
  SendNUIMessage({ action = 'open', url = Config.Url, debug = Config.Debug == true })
  sendWorld()
  CreateThread(function()   -- la tablette se range si le joueur tombe ; l'heure et la météo du jeu sont rafraîchies
    local tick = 0
    while isOpen do
      if IsPedDeadOrDying(PlayerPedId(), true) then closeTablet() break end
      tick = tick + 1
      if tick % 10 == 0 then sendWorld() end
      Wait(500)
    end
  end)
end

RegisterNUICallback('close', function(_, cb)
  closeTablet()
  cb({ ok = true })
end)

RegisterNUICallback('locate', function(data, cb)
  if data and data.on then startLocate() else locating = false end
  cb({ ok = true })
end)

RegisterNUICallback('waypoint', function(data, cb)
  if data and tonumber(data.x) and tonumber(data.y) then SetNewWaypoint(tonumber(data.x) + 0.0, tonumber(data.y) + 0.0) end
  cb({ ok = true })
end)

RegisterNUICallback('camera', function(data, cb)
  if data and data.on then
    if GetResourceState('screenshot-basic') ~= 'started' then cb({ ok = false, reason = 'missing' }) return end
    cb({ ok = true })
    startCamera()
  else
    if camMode then
      camMode = false
      if savedView then SetFollowPedCamViewMode(savedView) savedView = nil end
      SetNuiFocus(true, true)
    end
    cb({ ok = true })
  end
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
