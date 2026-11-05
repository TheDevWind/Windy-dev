-- Plans d'intervention : un plan (calques photo + formes dessinées) rattaché à un mandat, un transfert ou un briefing SOG.
-- Même logique que map_marks : lecture par RLS (service + capacité), écriture uniquement par des fonctions qui vérifient tout.
-- Le module « carte » donne accès à la page ; la capacité du type de plan s'y ajoute (mandats, transferts, sog).

create table public.intervention_plans (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  title text not null check (length(title) between 2 and 120),
  kind text not null check (kind in ('mandat', 'transfert', 'sog')),
  ref_id uuid,                                  -- mandat (warrants), transfert (transfers) ou briefing (sog_missions) ; vérifié par save_plan
  -- {"v":1,"layers":[{"id","kind":"photo","path","corners":[[x,y]x4],"opacity"}],"shapes":[{"id","type","pts":[[x,y]],"team","role","label"}],"view":{"c":[x,y],"z":3}}
  data jsonb not null default '{"v":1,"layers":[],"shapes":[]}'::jsonb
    check (jsonb_typeof(data) = 'object' and data ->> 'v' = '1' and jsonb_typeof(data -> 'layers') = 'array' and jsonb_typeof(data -> 'shapes') = 'array'
      and octet_length(data::text) <= 262144),
  created_by uuid references public.members (id) on delete set null,
  by_name text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index intervention_plans_org on public.intervention_plans (org_id, kind, ref_id);
create index intervention_plans_by on public.intervention_plans (created_by);

alter table public.intervention_plans enable row level security;
-- lecture : tous les membres du service, comme map_marks et sog_missions (décision du Director : les plans SOG ne sont pas masqués)
create policy intervention_plans_read on public.intervention_plans for select to authenticated using (public.is_member(org_id));

-- capacité requise pour écrire, selon le type de plan
create or replace function public.plan_cap(k text) returns text
language sql immutable set search_path = '' as $$ select case k when 'mandat' then 'mandats' when 'transfert' then 'transferts' when 'sog' then 'sog' end $$;

create or replace function public.save_plan(p_org uuid, p_id uuid, p_title text, p_kind text, p_ref uuid, p_data jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'carte', null); pl public.intervention_plans; pid uuid; lay jsonb; shp jsonb; c jsonb; pt jsonb; n integer; ok boolean;
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
    if (shp ->> 'type') not in ('fleche', 'zone', 'perimetre', 'ligne', 'entree', 'etiquette') or jsonb_typeof(shp -> 'pts') <> 'array' then raise exception 'Forme invalide.'; end if;
    n := jsonb_array_length(shp -> 'pts');
    if n < 1 or n > 200 or length(coalesce(shp ->> 'label', '')) > 80 then raise exception 'Forme invalide.'; end if;
    for pt in select * from jsonb_array_elements(shp -> 'pts') loop
      if jsonb_typeof(pt) <> 'array' or jsonb_array_length(pt) <> 2 or jsonb_typeof(pt -> 0) <> 'number' or jsonb_typeof(pt -> 1) <> 'number' then raise exception 'Forme invalide.'; end if;
    end loop;
  end loop;
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

create or replace function public.delete_plan(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare pl public.intervention_plans; me public.members;
begin
  select * into pl from public.intervention_plans where id = p_id;
  if pl.id is null then raise exception 'Plan inconnu.'; end if;
  me := public.need(pl.org_id, 'carte', null);
  if not public.has_cap(pl.org_id, public.plan_cap(pl.kind)) then raise exception 'Ton grade ne permet pas cette action.'; end if;
  if pl.created_by is distinct from me.id and not public.has_cap(pl.org_id, 'dossiers') then raise exception 'Seul son auteur ou un enquêteur retire ce plan.'; end if;
  delete from public.intervention_plans where id = pl.id;
end $$;

-- droits : lecture seule sur la table (comme map_marks) ; écriture par les deux fonctions
revoke all on public.intervention_plans from anon, authenticated;
grant select on public.intervention_plans to authenticated;
revoke execute on function public.plan_cap(text) from public, anon, authenticated;
revoke execute on function public.save_plan(uuid, uuid, text, text, uuid, jsonb), public.delete_plan(uuid) from public, anon;
grant execute on function public.save_plan(uuid, uuid, text, text, uuid, jsonb), public.delete_plan(uuid) to authenticated;

-- la sauvegarde du service contient aussi les plans d'intervention
create or replace function public.export_service(p_org uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'effectifs', null); t text; part jsonb; res jsonb := '{}';
  tables text[] := array['grades', 'members', 'units', 'member_units', 'qualifications', 'member_qualifications', 'career_events', 'blacklist', 'absences', 'agenda', 'agenda_staff', 'shifts',
    'people', 'gangs', 'person_links', 'plates', 'plate_log', 'cases', 'case_agents', 'case_log', 'case_files', 'warrants', 'wanted', 'wanted_history', 'tips', 'announcements',
    'reports', 'penal_code', 'evidence', 'evidence_log', 'ia_cases', 'ia_log', 'complaints', 'discipline', 'transfers', 'transfer_agents', 'transfer_routes', 'detentions',
    'hearings', 'hearing_posts', 'protectees', 'threats', 'safehouses', 'witnesses', 'witsec_events', 'witsec_audit', 'sog_operators', 'sog_tests', 'sog_missions',
    'informants', 'informant_payments', 'intel', 'applications', 'application_notes', 'training_modules', 'training_progress', 'library_docs', 'exams', 'exam_keys', 'exam_attempts',
    'field_evals', 'promotions', 'weapons', 'weapon_loans', 'equipment', 'equipment_moves', 'fleet', 'fleet_trips', 'fleet_damage', 'map_marks', 'intervention_plans', 'doc_templates', 'journal'];
begin
  if not public.is_top(p_org) then raise exception 'Seul le Director exporte les données du service.'; end if;
  foreach t in array tables loop
    execute format('select coalesce(jsonb_agg(to_jsonb(x)), ''[]'') from public.%I x where x.org_id = $1', t) into part using p_org;
    res := res || jsonb_build_object(t, part);
  end loop;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Export de sauvegarde des données du service');
  return jsonb_build_object('service', (select to_jsonb(o) from public.orgs o where o.id = p_org), 'exported_at', now(), 'by', me.name, 'tables', res);
end $$;
