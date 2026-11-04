-- Windy Desk · services publics, étape 2 (US Marshals) : rapports guidés avec validation, code pénal et calcul des peines,
-- base de données (véhicules, organisations, entourage, type et dangerosité des fiches), recherche globale,
-- preuves et saisies avec chaîne de possession, ouverture automatique d'un dossier Affaires internes après un tir.

-- ---------------------------------------------------------------- modules et accès
insert into public.modules (id, name, price, description, sort, sector) values
  ('rapports', 'Rapports', 250, 'Rapports guidés (arrestation, SOG, transfert, incident, usage de la force, audience, libre), validation, export PDF ou PNG, code pénal et calcul des peines.', 135, 'service_public'),
  ('preuves', 'Preuves et saisies', 150, 'Registre des scellés, chaîne de possession, statut et inventaire des saisies : armes, drogue, argent, véhicules.', 145, 'service_public');
-- les services déjà créés reçoivent les deux nouveaux modules
update public.orgs set modules = modules || array['rapports', 'preuves'] where sector = 'service_public' and not ('rapports' = any (modules));

-- « affaires » : accès aux Affaires internes. Le grade le plus haut (Director) l'a toujours ; il peut le donner à un autre grade.
create or replace function public.all_caps() returns text[]
language sql immutable set search_path = '' as $$
  select array['encaisser', 'stock', 'fabriquer', 'compta', 'declarer', 'paie', 'equipe', 'vehicules', 'evenements',
    'effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces', 'affaires']
$$;

-- ---------------------------------------------------------------- base de données : organisations, fiches, entourage, véhicules
create table public.gangs (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 2 and 60),
  color text check (color ~ '^#[0-9a-fA-F]{6}$'),
  territory text check (length(territory) <= 300),
  activities text check (length(activities) <= 500),
  notes text check (length(notes) <= 2000),
  created_at timestamptz not null default now(),
  by_name text not null
);
create unique index gangs_name on public.gangs (org_id, lower(name));

alter table public.people
  add column kind text not null default 'suspect' check (kind in ('suspect', 'civil', 'temoin', 'victime')),
  add column danger text not null default 'faible' check (danger in ('faible', 'moyenne', 'elevee', 'extreme')),
  add column gang_id uuid references public.gangs (id) on delete set null;
update public.people set danger = 'elevee' where dangerous;
create index people_gang on public.people (gang_id);

create table public.person_links (
  person_id uuid not null references public.people (id) on delete cascade,
  other_id uuid not null references public.people (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  kind text not null check (kind in ('famille', 'proche', 'complice', 'associe', 'rival')),
  note text check (length(note) <= 160),
  by_name text not null,
  created_at timestamptz not null default now(),
  primary key (person_id, other_id),
  check (person_id < other_id)
);
create index person_links_other on public.person_links (other_id);
create index person_links_org on public.person_links (org_id);

create table public.plates (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  plate text not null check (plate ~ '^[A-Z0-9][A-Z0-9 -]{0,8}[A-Z0-9]$'),
  model text check (length(model) <= 40),
  color text check (length(color) <= 20),
  owner_id uuid references public.people (id) on delete set null,
  stolen boolean not null default false,
  wanted boolean not null default false,
  notes text check (length(notes) <= 1000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  by_name text not null,
  unique (org_id, plate)
);
create index plates_owner on public.plates (owner_id);
create table public.plate_log (
  id bigint generated always as identity primary key,
  plate_id uuid not null references public.plates (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  kind text not null check (kind in ('creation', 'proprietaire', 'observation', 'vol', 'recherche', 'saisie', 'note')),
  text text not null check (length(text) between 2 and 500),
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index plate_log_plate on public.plate_log (plate_id, at desc);
create index plate_log_org on public.plate_log (org_id);
create index plate_log_member on public.plate_log (member_id);

-- ---------------------------------------------------------------- code pénal
create table public.penal_code (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  code text not null check (code ~ '^[A-Z0-9][A-Z0-9.-]{0,9}$'),
  title text not null check (length(title) between 3 and 120),
  category text not null check (category in ('contravention', 'delit', 'crime')),
  fine numeric(12, 2) not null default 0 check (fine >= 0),
  jail integer not null default 0 check (jail between 0 and 10000),   -- en mois de peine RP
  archived boolean not null default false,
  unique (org_id, code)
);

-- ---------------------------------------------------------------- rapports et Affaires internes
create table public.reports (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  kind text not null check (kind in ('arrestation', 'sog', 'transfert', 'incident', 'force', 'audience', 'libre')),
  title text not null check (length(title) between 3 and 120),
  status text not null default 'brouillon' check (status in ('brouillon', 'soumis', 'valide', 'a_corriger')),
  author_id uuid references public.members (id) on delete set null,
  author_name text not null,
  person_id uuid references public.people (id) on delete set null,
  case_id uuid references public.cases (id) on delete set null,
  occurred_at timestamptz not null default now(),
  place text check (length(place) <= 120),
  fields jsonb not null default '{}' check (jsonb_typeof(fields) = 'object'),
  narrative text not null default '' check (length(narrative) <= 6000),
  charges jsonb not null default '[]' check (jsonb_typeof(charges) = 'array'),
  total_fine numeric(14, 2) not null default 0,
  total_jail integer not null default 0,
  firearm boolean not null default false,
  participants uuid[] not null default '{}',
  reviewer text,
  review_note text check (length(review_note) <= 1000),
  submitted_at timestamptz,
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (org_id, num)
);
create index reports_org on public.reports (org_id, created_at desc);
create index reports_author on public.reports (author_id);
create index reports_person on public.reports (person_id);
create index reports_case on public.reports (case_id);

create table public.ia_cases (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  member_id uuid references public.members (id) on delete set null,
  report_id uuid references public.reports (id) on delete set null,
  reason text not null check (length(reason) between 3 and 300),
  status text not null default 'ouvert' check (status in ('ouvert', 'enquete', 'classe', 'sanction')),
  opened_at timestamptz not null default now(),
  opened_by text not null,
  unique (org_id, num)
);
create index ia_cases_member on public.ia_cases (member_id);
create index ia_cases_report on public.ia_cases (report_id);

-- ---------------------------------------------------------------- preuves et saisies
create table public.evidence (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  kind text not null check (kind in ('arme', 'drogue', 'argent', 'vehicule', 'document', 'autre')),
  description text not null check (length(description) between 3 and 300),
  qty numeric(14, 3) not null default 1 check (qty > 0),
  unit text not null default 'unité' check (length(unit) between 1 and 20),
  amount numeric(14, 2) check (amount >= 0),
  photo text check (length(photo) <= 300),
  seized_at timestamptz not null default now(),
  place text check (length(place) <= 120),
  case_id uuid references public.cases (id) on delete set null,
  report_id uuid references public.reports (id) on delete set null,
  person_id uuid references public.people (id) on delete set null,
  status text not null default 'stock' check (status in ('stock', 'tribunal', 'restituee', 'detruite')),
  storage text check (length(storage) <= 60),
  holder text check (length(holder) <= 60),
  created_at timestamptz not null default now(),
  by_name text not null,
  unique (org_id, num)
);
create index evidence_org on public.evidence (org_id, seized_at desc);
create index evidence_case on public.evidence (case_id);
create index evidence_report on public.evidence (report_id);
create index evidence_person on public.evidence (person_id);
create table public.evidence_log (
  id bigint generated always as identity primary key,
  evidence_id uuid not null references public.evidence (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  action text not null check (action in ('saisie', 'transfert', 'consultation', 'tribunal', 'restitution', 'destruction', 'note')),
  text text check (length(text) <= 300),
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index evidence_log_evidence on public.evidence_log (evidence_id, at desc);
create index evidence_log_org on public.evidence_log (org_id);
create index evidence_log_member on public.evidence_log (member_id);

-- ---------------------------------------------------------------- outils internes
create or replace function public.next_num(o uuid, k text) returns text
language plpgsql security definer set search_path = '' as $$
declare n integer; p text;
begin
  update public.orgs set counters = jsonb_set(counters, array[k], to_jsonb(coalesce((counters ->> k)::integer, 0) + 1))
    where id = o returning (counters ->> k)::integer, id_prefix into n, p;
  return p || case k when 'case' then '-IOD-' when 'warrant' then '-M-' when 'ia' then '-AI-' when 'evidence' then '-P-'
    when 'r_arrestation' then '-A-' when 'r_sog' then '-SOG-' when 'r_transfert' then '-T-' when 'r_incident' then '-INC-'
    when 'r_force' then '-UF-' when 'r_audience' then '-AUD-' when 'r_libre' then '-R-' else '-' end
    || lpad(n::text, case when k = 'r_sog' then 3 else 4 end, '0');
end $$;

create or replace function public.norm_plate(p text) returns text
language sql immutable set search_path = '' as $$ select nullif(upper(regexp_replace(trim(coalesce(p, '')), '\s+', ' ', 'g')), '') $$;

-- une plaque connue : créée si besoin, rattachée à son propriétaire
create or replace function public.plate_upsert(o uuid, p_plate text, p_model text, p_color text, p_owner uuid, who text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare pl public.plates; v text := public.norm_plate(p_plate); owner_name text;
begin
  if v is null then return null; end if;
  select * into pl from public.plates where org_id = o and plate = v;
  select first_name || ' ' || last_name into owner_name from public.people where id = p_owner;
  if pl.id is null then
    insert into public.plates (org_id, plate, model, color, owner_id, by_name) values (o, v, nullif(trim(coalesce(p_model, '')), ''), nullif(trim(coalesce(p_color, '')), ''), p_owner, who)
      returning * into pl;
    insert into public.plate_log (plate_id, org_id, kind, text, by_name) values (pl.id, o, 'creation', 'Fiche créée' || coalesce(' · propriétaire : ' || owner_name, ''), who);
  else
    update public.plates set model = coalesce(nullif(trim(coalesce(p_model, '')), ''), model), color = coalesce(nullif(trim(coalesce(p_color, '')), ''), color),
      owner_id = coalesce(p_owner, owner_id), updated_at = now() where id = pl.id;
    if p_owner is not null and p_owner is distinct from pl.owner_id then
      insert into public.plate_log (plate_id, org_id, kind, text, by_name) values (pl.id, o, 'proprietaire', 'Propriétaire : ' || owner_name, who);
    end if;
  end if;
  return pl.id;
end $$;

-- le code pénal de départ d'un service (modifiable ensuite dans les réglages)
create or replace function public.seed_penal(o uuid) returns void
language sql security definer set search_path = '' as $$
  insert into public.penal_code (org_id, code, title, category, fine, jail) values
    (o, 'C-01', 'Excès de vitesse', 'contravention', 500, 0),
    (o, 'C-02', 'Conduite sans permis', 'contravention', 1500, 0),
    (o, 'C-03', 'Stationnement gênant', 'contravention', 200, 0),
    (o, 'C-04', 'Trouble à l’ordre public', 'contravention', 750, 0),
    (o, 'C-05', 'Insulte envers un agent', 'contravention', 1000, 0),
    (o, 'D-01', 'Port d’arme sans permis', 'delit', 5000, 10),
    (o, 'D-02', 'Vol simple', 'delit', 2500, 10),
    (o, 'D-03', 'Vol de véhicule', 'delit', 5000, 15),
    (o, 'D-04', 'Recel', 'delit', 3000, 10),
    (o, 'D-05', 'Possession de stupéfiants', 'delit', 3000, 10),
    (o, 'D-06', 'Trafic de stupéfiants', 'delit', 15000, 30),
    (o, 'D-07', 'Refus d’obtempérer', 'delit', 3000, 10),
    (o, 'D-08', 'Délit de fuite', 'delit', 4000, 15),
    (o, 'D-09', 'Coups et blessures', 'delit', 5000, 15),
    (o, 'D-10', 'Menaces', 'delit', 2000, 5),
    (o, 'D-11', 'Outrage et rébellion', 'delit', 2500, 10),
    (o, 'D-12', 'Entrave à la justice', 'delit', 7500, 20),
    (o, 'D-13', 'Corruption', 'delit', 15000, 30),
    (o, 'CR-01', 'Braquage à main armée', 'crime', 25000, 45),
    (o, 'CR-02', 'Prise d’otage', 'crime', 30000, 60),
    (o, 'CR-03', 'Enlèvement', 'crime', 35000, 60),
    (o, 'CR-04', 'Tentative de meurtre', 'crime', 40000, 60),
    (o, 'CR-05', 'Meurtre', 'crime', 75000, 120),
    (o, 'CR-06', 'Meurtre d’un agent fédéral', 'crime', 100000, 180),
    (o, 'CR-07', 'Évasion', 'crime', 20000, 45),
    (o, 'CR-08', 'Complicité d’évasion', 'crime', 15000, 30),
    (o, 'CR-09', 'Trafic d’armes', 'crime', 40000, 60)
  on conflict (org_id, code) do nothing
$$;
select public.seed_penal(id) from public.orgs where sector = 'service_public';

-- ---------------------------------------------------------------- fiches individus (remplace la version de l'étape 1)
create or replace function public.save_person(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'dossiers', 'dossiers'); pid uuid; v_photo text := nullif(trim(p ->> 'photo'), '');
  v_gang text := nullif(trim(p ->> 'gang'), ''); gid uuid; v_danger text; veh jsonb;
begin
  if length(coalesce(trim(p ->> 'first_name'), '')) < 1 or length(coalesce(trim(p ->> 'last_name'), '')) < 1 then raise exception 'Indique le prénom et le nom.'; end if;
  if v_photo is not null and not public.stored('mdt', p_org, v_photo) then raise exception 'Photo introuvable : envoie-la de nouveau.'; end if;
  v_danger := coalesce(nullif(p ->> 'danger', ''), case when coalesce((p ->> 'dangerous')::boolean, false) then 'elevee' else 'faible' end);
  if v_gang is not null then
    insert into public.gangs (org_id, name, by_name) values (p_org, v_gang, me.name) on conflict (org_id, lower(name)) do nothing;
    select id into gid from public.gangs where org_id = p_org and lower(name) = lower(v_gang);
  end if;
  if p_id is null then
    insert into public.people (org_id, first_name, last_name, by_name) values (p_org, trim(p ->> 'first_name'), trim(p ->> 'last_name'), me.name) returning id into pid;
  else
    select id into pid from public.people where id = p_id and org_id = p_org;
    if pid is null then raise exception 'Individu inconnu.'; end if;
  end if;
  update public.people set first_name = trim(p ->> 'first_name'), last_name = trim(p ->> 'last_name'),
    alias = nullif(trim(p ->> 'alias'), ''), birth = nullif(p ->> 'birth', '')::date, sex = nullif(p ->> 'sex', ''),
    height = nullif(p ->> 'height', '')::integer, eyes = nullif(trim(p ->> 'eyes'), ''), hair = nullif(trim(p ->> 'hair'), ''),
    marks = nullif(trim(p ->> 'marks'), ''), phone = nullif(trim(p ->> 'phone'), ''), address = nullif(trim(p ->> 'address'), ''),
    job = nullif(trim(p ->> 'job'), ''), gang = v_gang, gang_id = gid,
    kind = coalesce(nullif(p ->> 'kind', ''), kind), danger = v_danger, dangerous = v_danger in ('elevee', 'extreme'),
    armed = coalesce((p ->> 'armed')::boolean, false),
    notes = nullif(trim(p ->> 'notes'), ''), photo = coalesce(v_photo, people.photo),
    vehicles = case when jsonb_typeof(p -> 'vehicles') = 'array' then p -> 'vehicles' else '[]' end,
    places = case when jsonb_typeof(p -> 'places') = 'array' then p -> 'places' else '[]' end, updated_at = now()
  where id = pid;
  -- chaque véhicule de la fiche rejoint le fichier des véhicules, avec cet individu pour propriétaire
  for veh in select * from jsonb_array_elements(case when jsonb_typeof(p -> 'vehicles') = 'array' then p -> 'vehicles' else '[]' end) loop
    perform public.plate_upsert(p_org, veh ->> 'plate', veh ->> 'model', veh ->> 'color', pid, me.name);
  end loop;
  return pid;
exception
  when check_violation then raise exception 'Une valeur est refusée : taille (100 à 250 cm), plaque (2 à 10 lettres ou chiffres), longueur des champs (10 véhicules et 10 lieux au plus).';
  when invalid_datetime_format or datetime_field_overflow or invalid_text_representation then raise exception 'Date de naissance ou taille invalide.';
end $$;

create or replace function public.link_people(p_a uuid, p_b uuid, p_kind text, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.people; b public.people; me public.members;
begin
  select * into a from public.people where id = p_a;
  select * into b from public.people where id = p_b;
  if a.id is null or b.id is null or a.org_id <> b.org_id then raise exception 'Individu inconnu.'; end if;
  if a.id = b.id then raise exception 'Choisis une autre personne.'; end if;
  me := public.need(a.org_id, 'dossiers', 'dossiers');
  if p_kind not in ('famille', 'proche', 'complice', 'associe', 'rival') then raise exception 'Type de lien inconnu.'; end if;
  insert into public.person_links (person_id, other_id, org_id, kind, note, by_name) values (least(a.id, b.id), greatest(a.id, b.id), a.org_id, p_kind, left(nullif(trim(coalesce(p_note, '')), ''), 160), me.name)
    on conflict (person_id, other_id) do update set kind = excluded.kind, note = excluded.note, by_name = excluded.by_name;
end $$;

create or replace function public.unlink_people(p_a uuid, p_b uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.people;
begin
  select * into a from public.people where id = p_a;
  if a.id is null then raise exception 'Individu inconnu.'; end if;
  perform public.need(a.org_id, 'dossiers', 'dossiers');
  delete from public.person_links where person_id = least(p_a, p_b) and other_id = greatest(p_a, p_b) and org_id = a.org_id;
end $$;

create or replace function public.save_gang(p_org uuid, p_id uuid, p_name text, p_color text, p_territory text, p_activities text, p_notes text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'dossiers', 'dossiers'); gid uuid;
begin
  if length(trim(coalesce(p_name, ''))) < 2 then raise exception 'Donne un nom à l’organisation.'; end if;
  if p_id is null then
    insert into public.gangs (org_id, name, color, territory, activities, notes, by_name)
      values (p_org, trim(p_name), nullif(p_color, ''), nullif(trim(coalesce(p_territory, '')), ''), nullif(trim(coalesce(p_activities, '')), ''), nullif(trim(coalesce(p_notes, '')), ''), me.name)
      returning id into gid;
  else
    update public.gangs set name = trim(p_name), color = nullif(p_color, ''), territory = nullif(trim(coalesce(p_territory, '')), ''),
      activities = nullif(trim(coalesce(p_activities, '')), ''), notes = nullif(trim(coalesce(p_notes, '')), '') where id = p_id and org_id = p_org returning id into gid;
    if gid is null then raise exception 'Organisation inconnue.'; end if;
    update public.people set gang = trim(p_name) where gang_id = gid;
  end if;
  return gid;
exception
  when unique_violation then raise exception 'L’organisation « % » existe déjà.', trim(p_name);
  when check_violation then raise exception 'Une valeur est refusée : couleur au format #RRVVBB, longueur des champs.';
end $$;

create or replace function public.save_plate(p_org uuid, p_id uuid, p_plate text, p_model text, p_color text, p_owner uuid, p_stolen boolean, p_wanted boolean, p_notes text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'dossiers', 'dossiers'); pl public.plates; pid uuid; v text := public.norm_plate(p_plate); owner_name text;
begin
  if v is null then raise exception 'Indique la plaque.'; end if;
  if p_owner is not null and not exists (select 1 from public.people where id = p_owner and org_id = p_org) then raise exception 'Propriétaire inconnu.'; end if;
  if p_id is null then
    if exists (select 1 from public.plates where org_id = p_org and plate = v) then raise exception 'La plaque % est déjà fichée.', v; end if;
    pid := public.plate_upsert(p_org, v, p_model, p_color, p_owner, me.name);
  else
    select * into pl from public.plates where id = p_id and org_id = p_org;
    if pl.id is null then raise exception 'Véhicule inconnu.'; end if;
    update public.plates set plate = v, model = nullif(trim(coalesce(p_model, '')), ''), color = nullif(trim(coalesce(p_color, '')), ''), owner_id = p_owner, updated_at = now() where id = pl.id;
    if p_owner is distinct from pl.owner_id then
      select first_name || ' ' || last_name into owner_name from public.people where id = p_owner;
      insert into public.plate_log (plate_id, org_id, kind, text, member_id, by_name) values (pl.id, p_org, 'proprietaire', 'Propriétaire : ' || coalesce(owner_name, 'inconnu'), me.id, me.name);
    end if;
    pid := pl.id;
  end if;
  select * into pl from public.plates where id = pid;
  if coalesce(p_stolen, false) <> pl.stolen then
    insert into public.plate_log (plate_id, org_id, kind, text, member_id, by_name) values (pid, p_org, 'vol', case when p_stolen then 'Signalé volé' else 'N’est plus signalé volé' end, me.id, me.name);
  end if;
  if coalesce(p_wanted, false) <> pl.wanted then
    insert into public.plate_log (plate_id, org_id, kind, text, member_id, by_name) values (pid, p_org, 'recherche', case when p_wanted then 'Véhicule recherché' else 'N’est plus recherché' end, me.id, me.name);
  end if;
  update public.plates set stolen = coalesce(p_stolen, false), wanted = coalesce(p_wanted, false), notes = nullif(trim(coalesce(p_notes, '')), '') where id = pid;
  return pid;
exception
  when unique_violation then raise exception 'La plaque % est déjà fichée.', v;
  when check_violation then raise exception 'Plaque invalide : 2 à 10 lettres ou chiffres (espace et tiret permis).';
end $$;

create or replace function public.plate_note(p_plate uuid, p_kind text, p_text text) returns void
language plpgsql security definer set search_path = '' as $$
declare pl public.plates; me public.members;
begin
  select * into pl from public.plates where id = p_plate;
  if pl.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(pl.org_id, 'dossiers', null);
  if p_kind not in ('observation', 'note') then raise exception 'Type d’entrée inconnu.'; end if;
  if length(trim(coalesce(p_text, ''))) < 2 then raise exception 'Écris quelque chose.'; end if;
  insert into public.plate_log (plate_id, org_id, kind, text, member_id, by_name) values (pl.id, pl.org_id, p_kind, left(trim(p_text), 500), me.id, me.name);
end $$;

-- recherche globale : individus, véhicules, organisations et documents (dossier, mandat, rapport, scellé)
create or replace function public.mdt_search(p_org uuid, p_q text) returns json
language plpgsql stable security definer set search_path = '' as $$
declare q text := lower(trim(coalesce(p_q, ''))); like_q text;
begin
  perform public.need(p_org, 'dossiers', null);
  if length(q) < 2 then raise exception 'Tape au moins 2 caractères.'; end if;
  like_q := '%' || replace(replace(replace(q, '\', '\\'), '%', '\%'), '_', '\_') || '%';
  return json_build_object(
    'people', (select coalesce(json_agg(x), '[]'::json) from (select p.id, p.first_name, p.last_name, p.alias, p.phone, p.photo, p.kind, p.danger, p.gang
      from public.people p where p.org_id = p_org and (lower(p.first_name || ' ' || p.last_name) like like_q or lower(p.last_name || ' ' || p.first_name) like like_q
        or lower(coalesce(p.alias, '')) like like_q or replace(lower(coalesce(p.phone, '')), ' ', '') like replace(like_q, ' ', ''))
      order by p.last_name limit 20) x),
    'plates', (select coalesce(json_agg(x), '[]'::json) from (select v.id, v.plate, v.model, v.color, v.stolen, v.wanted, v.owner_id,
        (select o.first_name || ' ' || o.last_name from public.people o where o.id = v.owner_id) as owner
      from public.plates v where v.org_id = p_org and (replace(lower(v.plate), ' ', '') like replace(like_q, ' ', '') or lower(coalesce(v.model, '')) like like_q)
      order by v.plate limit 20) x),
    'gangs', (select coalesce(json_agg(x), '[]'::json) from (select g.id, g.name, g.color from public.gangs g where g.org_id = p_org and lower(g.name) like like_q order by g.name limit 10) x),
    'docs', (select coalesce(json_agg(x), '[]'::json) from (
      select 'dossier' as type, c.id, c.num, c.title from public.cases c where c.org_id = p_org and (lower(c.num) like like_q or lower(c.title) like like_q)
      union all select 'mandat', w.id, w.num, w.target from public.warrants w where w.org_id = p_org and lower(w.num) like like_q
      union all select 'rapport', r.id, r.num, r.title from public.reports r where r.org_id = p_org and lower(r.num) like like_q
        and (r.status <> 'brouillon' or r.author_id = (public.caller_member(p_org)).id)
      union all select 'preuve', e.id, e.num, e.description from public.evidence e where e.org_id = p_org and lower(e.num) like like_q
      limit 20) x));
end $$;

-- ---------------------------------------------------------------- code pénal
create or replace function public.save_penal(p_org uuid, p_id uuid, p_code text, p_title text, p_category text, p_fine numeric, p_jail integer, p_archived boolean) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'rapports', 'equipe'); pid uuid;
begin
  if p_id is null then
    insert into public.penal_code (org_id, code, title, category, fine, jail) values (p_org, upper(trim(p_code)), trim(p_title), p_category, round(coalesce(p_fine, 0), 2), coalesce(p_jail, 0)) returning id into pid;
  else
    update public.penal_code set code = upper(trim(p_code)), title = trim(p_title), category = p_category, fine = round(coalesce(p_fine, 0), 2), jail = coalesce(p_jail, 0),
      archived = coalesce(p_archived, archived) where id = p_id and org_id = p_org returning id into pid;
    if pid is null then raise exception 'Infraction inconnue.'; end if;
  end if;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Code pénal : ' || upper(trim(p_code)) || ' · ' || trim(p_title));
  return pid;
exception
  when unique_violation then raise exception 'Le code « % » existe déjà.', upper(trim(p_code));
  when check_violation then raise exception 'Une valeur est refusée : code de 1 à 10 caractères (lettres, chiffres, point, tiret), intitulé de 3 à 120 caractères, montants positifs.';
end $$;

-- ---------------------------------------------------------------- rapports
create or replace function public.save_report(p_org uuid, p_id uuid, p_kind text, p_title text, p_person uuid, p_case uuid, p_occurred timestamptz, p_place text,
  p_fields jsonb, p_narrative text, p_charges jsonb, p_firearm boolean, p_participants uuid[]) returns json
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'rapports', null); r public.reports; ch jsonb; tf numeric; tj integer; rid uuid;
begin
  if p_kind not in ('arrestation', 'sog', 'transfert', 'incident', 'force', 'audience', 'libre') then raise exception 'Type de rapport inconnu.'; end if;
  if length(trim(coalesce(p_title, ''))) < 3 then raise exception 'Donne un titre au rapport.'; end if;
  if p_person is not null and not exists (select 1 from public.people where id = p_person and org_id = p_org) then raise exception 'Individu inconnu.'; end if;
  if p_case is not null and not exists (select 1 from public.cases where id = p_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  if exists (select 1 from unnest(coalesce(p_participants, '{}')) x where not exists (select 1 from public.members m where m.id = x and m.org_id = p_org)) then raise exception 'Agent inconnu.'; end if;
  if jsonb_typeof(coalesce(p_charges, '[]')) <> 'array' or jsonb_array_length(coalesce(p_charges, '[]')) > 40 then raise exception 'Liste d’infractions invalide.'; end if;
  -- les infractions sont recopiées depuis le code pénal du service : montants et peines ne viennent jamais du navigateur
  select coalesce(jsonb_agg(jsonb_build_object('id', pc.id, 'code', pc.code, 'title', pc.title, 'category', pc.category, 'fine', pc.fine, 'jail', pc.jail, 'qty', x.qty) order by pc.code), '[]'),
         coalesce(sum(pc.fine * x.qty), 0), coalesce(sum(pc.jail * x.qty), 0)
    into ch, tf, tj
    from (select (e ->> 'id')::uuid as id, least(greatest(coalesce((e ->> 'qty')::integer, 1), 1), 20) as qty from jsonb_array_elements(coalesce(p_charges, '[]')) e) x
    join public.penal_code pc on pc.id = x.id and pc.org_id = p_org;
  if jsonb_array_length(ch) <> jsonb_array_length(coalesce(p_charges, '[]')) then raise exception 'Infraction inconnue du code pénal.'; end if;
  if p_id is null then
    insert into public.reports (org_id, num, kind, title, author_id, author_name)
      values (p_org, public.next_num(p_org, 'r_' || p_kind), p_kind, trim(p_title), me.id, me.name) returning id into rid;
  else
    select * into r from public.reports where id = p_id and org_id = p_org for update;
    if r.id is null then raise exception 'Rapport inconnu.'; end if;
    if r.author_id is distinct from me.id then raise exception 'Seul l’auteur modifie son rapport.'; end if;
    if r.status not in ('brouillon', 'a_corriger') then raise exception 'Ce rapport est soumis : il ne se modifie plus.'; end if;
    if r.kind <> p_kind then raise exception 'Le type d’un rapport ne change pas.'; end if;
    rid := r.id;
  end if;
  update public.reports set title = left(trim(p_title), 120), person_id = p_person, case_id = p_case, occurred_at = coalesce(p_occurred, occurred_at),
    place = left(nullif(trim(coalesce(p_place, '')), ''), 120), fields = case when jsonb_typeof(p_fields) = 'object' then p_fields else '{}' end,
    narrative = left(coalesce(p_narrative, ''), 6000), charges = ch, total_fine = tf, total_jail = tj,
    firearm = coalesce(p_firearm, false) or coalesce(p_fields ->> 'force_type', '') = 'Arme à feu',
    participants = coalesce(p_participants, '{}'), updated_at = now()
  where id = rid;
  return json_build_object('id', rid, 'num', (select num from public.reports where id = rid));
end $$;

create or replace function public.submit_report(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare r public.reports; me public.members; f jsonb; ia text;
begin
  select * into r from public.reports where id = p_id for update;
  if r.id is null then raise exception 'Rapport inconnu.'; end if;
  me := public.need(r.org_id, 'rapports', null);
  if r.author_id is distinct from me.id then raise exception 'Seul l’auteur soumet son rapport.'; end if;
  if r.status not in ('brouillon', 'a_corriger') then raise exception 'Ce rapport est déjà soumis.'; end if;
  f := r.fields;
  -- champs obligatoires selon le type
  if length(trim(r.narrative)) < 20 then raise exception 'Le récit doit faire au moins 20 caractères.'; end if;
  if r.kind in ('arrestation', 'sog', 'incident', 'force') and r.place is null then raise exception 'Indique le lieu.'; end if;
  if r.kind in ('arrestation', 'transfert') and r.person_id is null then raise exception 'Indique l’individu concerné.'; end if;
  if r.kind = 'arrestation' and jsonb_array_length(r.charges) = 0 then raise exception 'Ajoute au moins une infraction du code pénal.'; end if;
  if r.kind = 'arrestation' and coalesce(f ->> 'rights', '') <> 'true' then raise exception 'Confirme que les droits ont été lus à l’individu.'; end if;
  if r.kind = 'sog' and coalesce(trim(f ->> 'objective'), '') = '' then raise exception 'Indique l’objectif de l’opération.'; end if;
  if r.kind = 'transfert' and (coalesce(trim(f ->> 'from'), '') = '' or coalesce(trim(f ->> 'to'), '') = '') then raise exception 'Indique le départ et l’arrivée du transfert.'; end if;
  if r.kind = 'incident' and coalesce(trim(f ->> 'nature'), '') = '' then raise exception 'Indique la nature de l’incident.'; end if;
  if r.kind = 'force' and coalesce(trim(f ->> 'force_type'), '') = '' then raise exception 'Indique le type de force employée.'; end if;
  if r.kind = 'audience' and (coalesce(trim(f ->> 'court'), '') = '' or coalesce(trim(f ->> 'verdict'), '') = '') then raise exception 'Indique le tribunal et le verdict.'; end if;
  update public.reports set status = 'soumis', submitted_at = now() where id = r.id;
  -- un tir avec une arme à feu ouvre un dossier Affaires internes (une seule fois par rapport)
  if r.firearm and not exists (select 1 from public.ia_cases where report_id = r.id) then
    ia := public.next_num(r.org_id, 'ia');
    insert into public.ia_cases (org_id, num, member_id, report_id, reason, opened_by) values (r.org_id, ia, r.author_id, r.id, 'Usage d’une arme à feu · rapport ' || r.num, 'Ouverture automatique');
    insert into public.journal (org_id, who, what) values (r.org_id, 'Affaires internes', 'Dossier ' || ia || ' ouvert automatiquement (arme à feu, rapport ' || r.num || ')');
  end if;
  if r.case_id is not null then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (r.case_id, r.org_id, 'note', 'Rapport ' || r.num || ' soumis : ' || r.title, me.id, me.name);
  end if;
end $$;

create or replace function public.review_report(p_id uuid, p_ok boolean, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare r public.reports; me public.members; note text := nullif(trim(coalesce(p_note, '')), '');
begin
  select * into r from public.reports where id = p_id for update;
  if r.id is null then raise exception 'Rapport inconnu.'; end if;
  me := public.need(r.org_id, 'rapports', 'valider');
  if r.author_id = me.id then raise exception 'Un autre responsable valide ton rapport.'; end if;
  if r.status <> 'soumis' then raise exception 'Ce rapport n’attend pas de validation.'; end if;
  if not p_ok and length(coalesce(note, '')) < 3 then raise exception 'Explique ce qu’il faut corriger.'; end if;
  update public.reports set status = case when p_ok then 'valide' else 'a_corriger' end, reviewer = me.name, review_note = left(note, 1000), reviewed_at = now() where id = r.id;
  insert into public.journal (org_id, who, what) values (r.org_id, me.name, 'Rapport ' || r.num || case when p_ok then ' validé' else ' renvoyé pour correction' end);
  if r.case_id is not null and p_ok then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (r.case_id, r.org_id, 'note', 'Rapport ' || r.num || ' validé par ' || me.name, me.id, me.name);
  end if;
end $$;

create or replace function public.delete_report(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare r public.reports; me public.members;
begin
  select * into r from public.reports where id = p_id;
  if r.id is null then raise exception 'Rapport inconnu.'; end if;
  me := public.need(r.org_id, 'rapports', null);
  if r.author_id is distinct from me.id or r.status <> 'brouillon' then raise exception 'Seul un brouillon se supprime, par son auteur.'; end if;
  delete from public.reports where id = r.id;
end $$;

-- ---------------------------------------------------------------- preuves
create or replace function public.add_evidence(p_org uuid, p jsonb) returns json
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'preuves', 'dossiers'); eid uuid; n text; v_photo text := nullif(trim(p ->> 'photo'), '');
  v_case uuid := nullif(p ->> 'case_id', '')::uuid; v_report uuid := nullif(p ->> 'report_id', '')::uuid; v_person uuid := nullif(p ->> 'person_id', '')::uuid;
begin
  if length(trim(coalesce(p ->> 'description', ''))) < 3 then raise exception 'Décris la preuve.'; end if;
  if v_photo is not null and not public.stored('mdt', p_org, v_photo) then raise exception 'Photo introuvable : envoie-la de nouveau.'; end if;
  if v_case is not null and not exists (select 1 from public.cases where id = v_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  if v_report is not null and not exists (select 1 from public.reports where id = v_report and org_id = p_org) then raise exception 'Rapport inconnu.'; end if;
  if v_person is not null and not exists (select 1 from public.people where id = v_person and org_id = p_org) then raise exception 'Individu inconnu.'; end if;
  n := public.next_num(p_org, 'evidence');
  insert into public.evidence (org_id, num, kind, description, qty, unit, amount, photo, seized_at, place, case_id, report_id, person_id, storage, holder, by_name)
    values (p_org, n, coalesce(nullif(p ->> 'kind', ''), 'autre'), trim(p ->> 'description'), coalesce(nullif(p ->> 'qty', '')::numeric, 1), coalesce(nullif(trim(p ->> 'unit'), ''), 'unité'),
      nullif(p ->> 'amount', '')::numeric, v_photo, coalesce(nullif(p ->> 'seized_at', '')::timestamptz, now()), nullif(trim(p ->> 'place'), ''),
      v_case, v_report, v_person, nullif(trim(p ->> 'storage'), ''), me.name, me.name)
    returning id into eid;
  insert into public.evidence_log (evidence_id, org_id, action, text, member_id, by_name) values (eid, p_org, 'saisie', coalesce('Saisie · ' || nullif(trim(p ->> 'place'), ''), 'Saisie'), me.id, me.name);
  if v_case is not null then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (v_case, p_org, 'note', 'Preuve ' || n || ' : ' || left(trim(p ->> 'description'), 120), me.id, me.name);
  end if;
  return json_build_object('id', eid, 'num', n);
exception
  when check_violation then raise exception 'Une valeur est refusée : type, quantité positive, longueur des champs.';
  when invalid_text_representation or invalid_datetime_format then raise exception 'Quantité, montant ou date invalide.';
end $$;

-- chaîne de possession : chaque manipulation est notée ; restituée ou détruite, la preuve ne bouge plus
create or replace function public.evidence_action(p_id uuid, p_action text, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare e public.evidence; me public.members; note text := nullif(trim(coalesce(p_note, '')), '');
begin
  select * into e from public.evidence where id = p_id for update;
  if e.id is null then raise exception 'Preuve inconnue.'; end if;
  me := public.need(e.org_id, 'preuves', 'dossiers');
  if p_action not in ('transfert', 'consultation', 'tribunal', 'restitution', 'destruction', 'note') then raise exception 'Action inconnue.'; end if;
  if e.status in ('restituee', 'detruite') and p_action <> 'note' then raise exception 'Cette preuve est sortie du registre.'; end if;
  if p_action in ('transfert', 'restitution', 'destruction', 'note') and note is null then raise exception 'Précise à qui, pourquoi ou comment.'; end if;
  update public.evidence set
    status = case p_action when 'tribunal' then 'tribunal' when 'restitution' then 'restituee' when 'destruction' then 'detruite' else status end,
    holder = case when p_action = 'transfert' then left(note, 60) else holder end
  where id = e.id;
  insert into public.evidence_log (evidence_id, org_id, action, text, member_id, by_name) values (e.id, e.org_id, p_action, left(note, 300), me.id, me.name);
  if p_action in ('tribunal', 'restitution', 'destruction') then
    insert into public.journal (org_id, who, what) values (e.org_id, me.name, 'Preuve ' || e.num || ' : ' || case p_action when 'tribunal' then 'transmise au tribunal' when 'restitution' then 'restituée' else 'détruite' end);
  end if;
end $$;

-- ---------------------------------------------------------------- un service créé reçoit aussi son code pénal
create or replace function public.create_service(p_code text, p_name text, p_owner_name text, p_owner_login text, p_prefix text, p_modules text[], p_weeks integer) returns json
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_editeur(); oid uuid; top uuid; mid uuid; tok text;
begin
  insert into public.orgs (code, name, kind, owner_name, sector, id_prefix, modules, licence_until)
    values (lower(trim(p_code)), trim(p_name), 'US Marshals Service', trim(p_owner_name), 'service_public', upper(trim(coalesce(p_prefix, 'USMS'))),
      public.check_modules(p_modules), now() + make_interval(days => 7 * greatest(1, p_weeks)))
    returning id into oid;
  insert into public.grades (org_id, name, rank, caps, badge, max_seats) values (oid, 'Director', 100, public.all_caps(), '★★★★', 1) returning id into top;
  insert into public.grades (org_id, name, rank, caps, badge, max_seats) values
    (oid, 'Chief Deputy', 90, array['effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces', 'equipe'], '★★★', 1),
    (oid, 'Supervisory', 80, array['effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces'], '★★', 2),
    (oid, 'Inspector', 70, array['dossiers', 'mandats', 'wanted'], '★', 4),
    (oid, 'Deputy', 60, array['dossiers'], 'DUSM', 12),
    (oid, 'Recrue', 40, '{}', 'R', 6);
  insert into public.units (org_id, code, name, restricted) values
    (oid, 'IOD', 'Investigative Operations Division · fugitifs', false),
    (oid, 'POD', 'Prisoner Operations Division · transferts', false),
    (oid, 'JSD', 'Judicial Security Division · tribunal', false),
    (oid, 'SOG', 'Special Operations Group', false),
    (oid, 'WITSEC', 'Witness Security · protection des témoins', true);
  insert into public.qualifications (org_id, name) values (oid, 'SOG'), (oid, 'FTO'), (oid, 'Sniper'), (oid, 'Négociateur'), (oid, 'Conducteur de convoi');
  perform public.seed_penal(oid);
  insert into public.members (org_id, name, login, grade_id, callsign) values (oid, trim(p_owner_name), lower(trim(p_owner_login)), top, 'Marshal-1') returning id into mid;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (oid, mid, 'recrutement', 'Prise de fonction · Director', who);
  insert into public.invitations (member_id) values (mid) returning token into tok;
  insert into public.journal (org_id, who, what) values (oid, who, 'Service créé par l’éditeur');
  return json_build_object('org_id', oid, 'token', tok);
exception when unique_violation then
  raise exception 'L’adresse « % » est déjà prise.', lower(trim(p_code));
end $$;

-- ---------------------------------------------------------------- règles de lecture
alter table public.gangs enable row level security;
alter table public.person_links enable row level security;
alter table public.plates enable row level security;
alter table public.plate_log enable row level security;
alter table public.penal_code enable row level security;
alter table public.reports enable row level security;
alter table public.ia_cases enable row level security;
alter table public.evidence enable row level security;
alter table public.evidence_log enable row level security;

create policy gangs_read on public.gangs for select to authenticated using (public.is_member(org_id));
create policy person_links_read on public.person_links for select to authenticated using (public.is_member(org_id));
create policy plates_read on public.plates for select to authenticated using (public.is_member(org_id));
create policy plate_log_read on public.plate_log for select to authenticated using (public.is_member(org_id));
create policy penal_code_read on public.penal_code for select to authenticated using (public.is_member(org_id));
-- un brouillon n'est visible que de son auteur et des responsables qui valident
create policy reports_read on public.reports for select to authenticated
  using (public.is_member(org_id) and (status <> 'brouillon' or author_id = (public.caller_member(org_id)).id or public.has_cap(org_id, 'valider')));
create policy ia_cases_read on public.ia_cases for select to authenticated using (public.has_cap(org_id, 'affaires'));
create policy evidence_read on public.evidence for select to authenticated using (public.is_member(org_id));
create policy evidence_log_read on public.evidence_log for select to authenticated using (public.is_member(org_id));

-- ---------------------------------------------------------------- droits
do $$
declare
  t text;
  f regprocedure;
  tables text[] := array['gangs', 'person_links', 'plates', 'plate_log', 'penal_code', 'reports', 'ia_cases', 'evidence', 'evidence_log'];
  internal text[] := array['next_num', 'norm_plate', 'plate_upsert', 'seed_penal'];
  api text[] := array['save_person', 'link_people', 'unlink_people', 'save_gang', 'save_plate', 'plate_note', 'mdt_search', 'save_penal',
    'save_report', 'submit_report', 'review_report', 'delete_report', 'add_evidence', 'evidence_action', 'create_service'];
begin
  foreach t in array tables loop
    execute format('revoke all on public.%I from anon, authenticated', t);
    execute format('grant select on public.%I to authenticated', t);
  end loop;
  for f in select p.oid::regprocedure from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = any (internal) loop
    execute format('revoke execute on function %s from public, anon, authenticated', f);
  end loop;
  for f in select p.oid::regprocedure from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = any (api) loop
    execute format('revoke execute on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end $$;
