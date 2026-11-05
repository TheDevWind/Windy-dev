-- Plans d'intervention, suite : véhicules, texte libre, couleurs par forme, hauteur (maquette 3D) et légende.
-- Seule la fonction save_plan change ; aucune donnée ni table touchée, les plans déjà enregistrés restent valides.

create or replace function public.save_plan(p_org uuid, p_id uuid, p_title text, p_kind text, p_ref uuid, p_data jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'carte', null); pl public.intervention_plans; pid uuid; lay jsonb; shp jsonb; c jsonb; pt jsonb; n integer; ok boolean; tm record;
begin
  if p_kind is null or public.plan_cap(p_kind) is null then raise exception 'Type de plan inconnu.'; end if;
  if not public.has_cap(p_org, public.plan_cap(p_kind)) then raise exception 'Ton grade ne permet pas cette action.'; end if;
  if length(trim(coalesce(p_title, ''))) < 2 then raise exception 'Donne un titre au plan.'; end if;
  -- le plan s'accroche à une pièce du même service
  if p_ref is not null then
    ok := case p_kind
      when 'mandat' then exists (select 1 from public.warrants where id = p_ref and org_id = p_org)
      when 'transfert' then exists (select 1 from public.transfers where id = p_ref and org_id = p_org)
      else exists (select 1 from public.sog_missions where id = p_ref and org_id = p_org) end;
    if not ok then raise exception 'Pièce rattachée inconnue.'; end if;
  end if;
  -- contenu : tailles bornées, coordonnées numériques, photos rangées dans le dossier plans/ du service
  if jsonb_typeof(p_data -> 'layers') <> 'array' or jsonb_typeof(p_data -> 'shapes') <> 'array' then raise exception 'Plan invalide.'; end if;
  if jsonb_array_length(p_data -> 'layers') > 30 or jsonb_array_length(p_data -> 'shapes') > 500 then raise exception 'Plan trop chargé : 30 calques et 500 formes au plus.'; end if;
  for lay in select * from jsonb_array_elements(p_data -> 'layers') loop
    if lay ->> 'kind' <> 'photo' or jsonb_typeof(lay -> 'corners') <> 'array' or jsonb_array_length(lay -> 'corners') <> 4 then raise exception 'Calque invalide.'; end if;
    if (lay ->> 'path') !~ ('^' || p_org::text || '/plans/[A-Za-z0-9._-]+$') or not public.stored('mdt', p_org, lay ->> 'path') then raise exception 'Photo introuvable : envoie-la de nouveau.'; end if;
    for c in select * from jsonb_array_elements(lay -> 'corners') loop
      if jsonb_typeof(c) <> 'array' or jsonb_array_length(c) <> 2 or jsonb_typeof(c -> 0) <> 'number' or jsonb_typeof(c -> 1) <> 'number' then raise exception 'Calque invalide.'; end if;
    end loop;
    if lay ? 'opacity' and (jsonb_typeof(lay -> 'opacity') <> 'number' or (lay ->> 'opacity')::numeric not between 0 and 1) then raise exception 'Opacité invalide (0 à 1).'; end if;
  end loop;
  for shp in select * from jsonb_array_elements(p_data -> 'shapes') loop
    if (shp ->> 'type') not in ('fleche', 'zone', 'perimetre', 'ligne', 'entree', 'etiquette', 'vehicule', 'texte') or jsonb_typeof(shp -> 'pts') <> 'array' then raise exception 'Forme invalide.'; end if;
    n := jsonb_array_length(shp -> 'pts');
    if n < 1 or n > 200 or length(coalesce(shp ->> 'label', '')) > 80 or length(coalesce(shp ->> 'role', '')) > 40 then raise exception 'Forme invalide.'; end if;
    if (shp ->> 'type') in ('entree', 'etiquette', 'vehicule', 'texte') and n <> 1 then raise exception 'Forme invalide.'; end if;
    -- options : couleur #rrggbb, type de véhicule, angle, taille du texte, hauteur de la maquette 3D
    if shp ? 'color' and (jsonb_typeof(shp -> 'color') <> 'string' or (shp ->> 'color') !~ '^#[0-9a-fA-F]{6}$') then raise exception 'Couleur invalide.'; end if;
    if (shp ->> 'type') = 'vehicule' and coalesce(shp ->> 'vkind', '') not in ('voiture', 'suv', 'camion', 'moto', 'helico', 'bateau') then raise exception 'Véhicule invalide.'; end if;
    if shp ? 'angle' and jsonb_typeof(shp -> 'angle') <> 'number' then raise exception 'Angle invalide.'; end if;
    if shp ? 'size' and (jsonb_typeof(shp -> 'size') <> 'number' or (shp ->> 'size')::numeric not between 8 and 48) then raise exception 'Taille de texte invalide.'; end if;
    if shp ? 'height' and (jsonb_typeof(shp -> 'height') <> 'number' or (shp ->> 'height')::numeric not between 0 and 60) then raise exception 'Hauteur invalide.'; end if;
    for pt in select * from jsonb_array_elements(shp -> 'pts') loop
      if jsonb_typeof(pt) <> 'array' or jsonb_array_length(pt) <> 2 or jsonb_typeof(pt -> 0) <> 'number' or jsonb_typeof(pt -> 1) <> 'number' then raise exception 'Forme invalide.'; end if;
    end loop;
  end loop;
  -- légende : équipes A à D (nom de 30 caractères au plus, couleur #rrggbb)
  if p_data ? 'legend' then
    if jsonb_typeof(p_data -> 'legend') <> 'object' or (p_data -> 'legend' ? 'teams' and jsonb_typeof(p_data -> 'legend' -> 'teams') <> 'object') then raise exception 'Légende invalide.'; end if;
    for tm in select key, value from jsonb_each(coalesce(p_data -> 'legend' -> 'teams', '{}')) loop
      if tm.key not in ('A', 'B', 'C', 'D') or jsonb_typeof(tm.value) <> 'object' then raise exception 'Légende invalide.'; end if;
      if length(coalesce(tm.value ->> 'name', '')) > 30 then raise exception 'Nom d’équipe trop long (30 caractères).'; end if;
      if tm.value ? 'color' and (jsonb_typeof(tm.value -> 'color') <> 'string' or (tm.value ->> 'color') !~ '^#[0-9a-fA-F]{6}$') then raise exception 'Couleur invalide.'; end if;
    end loop;
  end if;
  if p_id is null then
    insert into public.intervention_plans (org_id, title, kind, ref_id, data, created_by, by_name) values (p_org, trim(p_title), p_kind, p_ref, p_data, me.id, me.name) returning id into pid;
  else
    select * into pl from public.intervention_plans where id = p_id and org_id = p_org;
    if pl.id is null then raise exception 'Plan inconnu.'; end if;
    if pl.kind <> p_kind then raise exception 'Le type d’un plan ne change pas.'; end if;
    if pl.created_by is distinct from me.id and not public.has_cap(p_org, 'dossiers') then raise exception 'Seul son auteur ou un enquêteur modifie ce plan.'; end if;
    update public.intervention_plans set title = trim(p_title), ref_id = p_ref, data = p_data, updated_at = now() where id = pl.id returning id into pid;
  end if;
  return pid;
exception
  when check_violation then raise exception 'Plan invalide ou trop volumineux (256 Ko au plus).';
  when invalid_text_representation then raise exception 'Plan invalide.';
end $$;
