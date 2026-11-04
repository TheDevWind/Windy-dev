-- Windy Desk · services publics, étape 1 (US Marshals) : type de client « service public », effectifs, présence,
-- dossiers fugitifs, mandats signés par un compte Justice, Most Wanted (page publique et signalements anonymes), annonces.
-- Même principe que le socle : lecture par les règles RLS, écriture uniquement par des fonctions qui vérifient
-- le module (offre du client) et le grade.

-- ---------------------------------------------------------------- type de client, numérotation, rôle Justice
alter table public.orgs
  add column sector text not null default 'entreprise' check (sector in ('entreprise', 'service_public')),
  add column id_prefix text not null default 'AG' check (id_prefix ~ '^[A-Z0-9]{2,8}$'),
  add column counters jsonb not null default '{}',
  add column wanted_size integer not null default 10 check (wanted_size in (5, 10));

alter table public.modules add column sector text not null default 'entreprise' check (sector in ('entreprise', 'service_public'));
insert into public.modules (id, name, price, description, sort, sector) values
  ('effectifs', 'Effectifs', 300, 'Fiches agents (matricule, indicatif, unités, qualifications), organigramme, carrière, départs et liste noire.', 110, 'service_public'),
  ('presence', 'Présence', 150, 'Prise de service et statut en direct, absences validées, planning des opérations, classement et inactivité.', 120, 'service_public'),
  ('dossiers', 'Dossiers', 350, 'Individus recherchés et dossiers d’enquête : statut, priorité, agents, journal et pièces jointes.', 130, 'service_public'),
  ('mandats', 'Mandats', 200, 'Demandes de mandat envoyées à la Justice, signature, expiration et exécution.', 140, 'service_public'),
  ('wanted', 'Most Wanted', 200, 'Classement des plus recherchés, historique de la semaine, affiche, page publique et signalements anonymes.', 150, 'service_public'),
  ('annonces', 'Annonces', 100, 'Annonces du commandement avec accusé de lecture.', 160, 'service_public');

alter table public.platform_roles drop constraint platform_roles_role_check,
  add constraint platform_roles_role_check check (role in ('editeur', 'fisc', 'justice'));
alter table public.platform_claims drop constraint platform_claims_role_check,
  add constraint platform_claims_role_check check (role in ('editeur', 'fisc', 'justice'));

create or replace function public.all_caps() returns text[]
language sql immutable set search_path = '' as $$
  select array['encaisser', 'stock', 'fabriquer', 'compta', 'declarer', 'paie', 'equipe', 'vehicules', 'evenements',
    'effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces']
$$;

-- ---------------------------------------------------------------- fiche agent et grades
alter table public.members
  add column matricule text,
  add column callsign text check (length(callsign) between 2 and 20),
  add column photo text check (length(photo) <= 300),
  add column discord_id text check (discord_id ~ '^[0-9]{5,25}$'),
  add column fivem_id text check (length(fivem_id) <= 60),
  add column rank_since timestamptz not null default now(),
  add column stage text not null default 'titulaire' check (stage in ('recrue', 'formation', 'titulaire')),
  add column duty_status text not null default 'dispo' check (duty_status in ('dispo', 'intervention', 'pause'));
create unique index members_matricule on public.members (org_id, matricule) where matricule is not null;
create unique index members_callsign on public.members (org_id, lower(callsign)) where callsign is not null;

alter table public.grades
  add column badge text check (length(badge) <= 12),
  add column max_seats integer check (max_seats between 1 and 500),
  add column salary numeric(10, 2) check (salary >= 0);

-- ---------------------------------------------------------------- tables
create table public.units (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  code text not null check (code ~ '^[A-Z0-9]{2,8}$'),
  name text not null check (length(name) between 2 and 60),
  restricted boolean not null default false,
  archived boolean not null default false,
  unique (org_id, code)
);
create table public.member_units (
  member_id uuid not null references public.members (id) on delete cascade,
  unit_id uuid not null references public.units (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  main boolean not null default false,
  since timestamptz not null default now(),
  primary key (member_id, unit_id)
);
create index member_units_unit on public.member_units (unit_id);
create index member_units_org on public.member_units (org_id);

create table public.qualifications (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 2 and 40),
  archived boolean not null default false,
  unique (org_id, name)
);
create table public.member_qualifications (
  member_id uuid not null references public.members (id) on delete cascade,
  qualification_id uuid not null references public.qualifications (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  obtained_at date not null default current_date,
  by_name text not null,
  primary key (member_id, qualification_id)
);
create index member_qualifications_q on public.member_qualifications (qualification_id);
create index member_qualifications_org on public.member_qualifications (org_id);

create table public.career_events (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  at timestamptz not null default now(),
  kind text not null check (kind in ('recrutement', 'promotion', 'retrogradation', 'unite', 'qualification', 'formation',
    'felicitation', 'sanction', 'note', 'commandement', 'depart', 'retour')),
  text text not null check (length(text) between 2 and 300),
  by_name text not null
);
create index career_events_member on public.career_events (member_id, at desc);
create index career_events_org on public.career_events (org_id);

create table public.blacklist (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 2 and 60),
  discord_id text check (discord_id ~ '^[0-9]{5,25}$'),
  reason text not null check (length(reason) between 3 and 200),
  at timestamptz not null default now(),
  by_name text not null
);
create index blacklist_org on public.blacklist (org_id);

create table public.absences (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  starts_on date not null,
  ends_on date not null,
  reason text not null check (length(reason) between 3 and 200),
  status text not null default 'attente' check (status in ('attente', 'validee', 'refusee')),
  decided_by text,
  decided_at timestamptz,
  created_at timestamptz not null default now(),
  check (ends_on >= starts_on)
);
create index absences_org on public.absences (org_id, starts_on desc);
create index absences_member on public.absences (member_id);

create table public.agenda (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  title text not null check (length(title) between 3 and 80),
  kind text not null default 'operation' check (kind in ('patrouille', 'operation', 'formation', 'reunion', 'autre')),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  place text check (length(place) <= 80),
  unit_id uuid references public.units (id) on delete set null,
  note text check (length(note) <= 300),
  by_name text not null,
  created_at timestamptz not null default now(),
  check (ends_at > starts_at)
);
create index agenda_org on public.agenda (org_id, starts_at);
create index agenda_unit on public.agenda (unit_id);
create table public.agenda_staff (
  agenda_id uuid not null references public.agenda (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  primary key (agenda_id, member_id)
);
create index agenda_staff_member on public.agenda_staff (member_id);
create index agenda_staff_org on public.agenda_staff (org_id);

create table public.people (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  first_name text not null check (length(first_name) between 1 and 40),
  last_name text not null check (length(last_name) between 1 and 40),
  alias text check (length(alias) <= 60),
  birth date,
  sex text check (sex in ('H', 'F', 'X')),
  height integer check (height between 100 and 250),
  eyes text check (length(eyes) <= 20),
  hair text check (length(hair) <= 20),
  marks text check (length(marks) <= 200),
  phone text check (length(phone) <= 20),
  address text check (length(address) <= 120),
  job text check (length(job) <= 60),
  gang text check (length(gang) <= 60),
  dangerous boolean not null default false,
  armed boolean not null default false,
  notes text check (length(notes) <= 2000),
  photo text check (length(photo) <= 300),
  vehicles jsonb not null default '[]' check (jsonb_typeof(vehicles) = 'array' and jsonb_array_length(vehicles) <= 10),
  places jsonb not null default '[]' check (jsonb_typeof(places) = 'array' and jsonb_array_length(places) <= 10),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  by_name text not null
);
create index people_org on public.people (org_id, last_name);

create table public.cases (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  person_id uuid not null references public.people (id) on delete restrict,
  title text not null check (length(title) between 3 and 80),
  charges text check (length(charges) <= 500),
  status text not null default 'ouvert' check (status in ('ouvert', 'en_cours', 'localise', 'arrete', 'rendu', 'decede', 'abandonne')),
  priority text not null default 'normale' check (priority in ('normale', 'haute', 'most_wanted')),
  lead_id uuid references public.members (id) on delete set null,
  last_seen text check (length(last_seen) <= 160),
  last_seen_at timestamptz,
  opened_at timestamptz not null default now(),
  closed_at timestamptz,
  by_name text not null,
  unique (org_id, num)
);
create index cases_org on public.cases (org_id, opened_at desc);
create index cases_person on public.cases (person_id);
create index cases_lead on public.cases (lead_id);
create table public.case_agents (
  case_id uuid not null references public.cases (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  primary key (case_id, member_id)
);
create index case_agents_member on public.case_agents (member_id);
create index case_agents_org on public.case_agents (org_id);
create table public.case_log (
  id bigint generated always as identity primary key,
  case_id uuid not null references public.cases (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  kind text not null check (kind in ('note', 'piste', 'observation', 'statut', 'agent', 'fichier', 'mandat', 'wanted')),
  text text not null check (length(text) between 2 and 1000),
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index case_log_case on public.case_log (case_id, at desc);
create index case_log_org on public.case_log (org_id);
create index case_log_member on public.case_log (member_id);
create table public.case_files (
  id bigint generated always as identity primary key,
  case_id uuid not null references public.cases (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  path text not null check (length(path) <= 300),
  name text not null check (length(name) between 1 and 120),
  mime text not null check (length(mime) <= 80),
  size integer not null check (size between 0 and 52428800),
  at timestamptz not null default now(),
  by_name text not null
);
create index case_files_case on public.case_files (case_id);
create index case_files_org on public.case_files (org_id);

create table public.warrants (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  kind text not null check (kind in ('arret', 'perquisition', 'ecoute', 'saisie')),
  person_id uuid references public.people (id) on delete set null,
  case_id uuid references public.cases (id) on delete set null,
  target text not null check (length(target) between 2 and 120),
  place text check (length(place) <= 120),
  reason text not null check (length(reason) between 10 and 1500),
  valid_days integer not null default 7 check (valid_days between 1 and 30),
  status text not null default 'brouillon' check (status in ('brouillon', 'envoye', 'signe', 'refuse', 'execute', 'annule')),
  requested_by text not null,
  requested_member uuid references public.members (id) on delete set null,
  created_at timestamptz not null default now(),
  sent_at timestamptz,
  decided_at timestamptz,
  decided_by text,
  judge_note text check (length(judge_note) <= 500),
  expires_at timestamptz,
  executed_at timestamptz,
  executed_by text,
  exec_note text check (length(exec_note) <= 300),
  unique (org_id, num)
);
create index warrants_org on public.warrants (org_id, created_at desc);
create index warrants_status on public.warrants (status);
create index warrants_person on public.warrants (person_id);
create index warrants_case on public.warrants (case_id);
create index warrants_member on public.warrants (requested_member);

create table public.wanted (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  person_id uuid not null references public.people (id) on delete cascade,
  case_id uuid references public.cases (id) on delete set null,
  rank integer not null check (rank between 1 and 10),
  reward numeric(12, 2) not null default 0 check (reward >= 0),
  reason text not null check (length(reason) between 3 and 200),
  published boolean not null default false,
  public_photo text check (length(public_photo) <= 300),
  added_at timestamptz not null default now(),
  by_name text not null,
  unique (org_id, person_id),
  constraint wanted_rank_unique unique (org_id, rank) deferrable initially deferred
);
create index wanted_person on public.wanted (person_id);
create index wanted_case on public.wanted (case_id);
create table public.wanted_history (
  org_id uuid not null references public.orgs (id) on delete cascade,
  week_start date not null,
  list jsonb not null,
  updated_at timestamptz not null default now(),
  primary key (org_id, week_start)
);
create table public.tips (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  wanted_id uuid references public.wanted (id) on delete set null,
  person_name text,
  at timestamptz not null default now(),
  text text not null check (length(text) between 10 and 1000),
  contact text check (length(contact) <= 80),
  status text not null default 'nouveau' check (status in ('nouveau', 'utile', 'inutile', 'traite')),
  handled_by text
);
create index tips_org on public.tips (org_id, at desc);
create index tips_wanted on public.tips (wanted_id);

create table public.announcements (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  title text not null check (length(title) between 3 and 80),
  body text not null check (length(body) between 3 and 2000),
  level text not null default 'info' check (level in ('info', 'important', 'urgent')),
  pinned boolean not null default false,
  by_name text not null
);
create index announcements_org on public.announcements (org_id, at desc);
create table public.announcement_reads (
  announcement_id bigint not null references public.announcements (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  primary key (announcement_id, member_id)
);
create index announcement_reads_member on public.announcement_reads (member_id);
create index announcement_reads_org on public.announcement_reads (org_id);

-- ---------------------------------------------------------------- outils internes
-- numéro suivant d'un service : matricule USMS-0001, dossier USMS-IOD-0001, mandat USMS-M-0001
create or replace function public.next_num(o uuid, k text) returns text
language plpgsql security definer set search_path = '' as $$
declare n integer; p text;
begin
  update public.orgs set counters = jsonb_set(counters, array[k], to_jsonb(coalesce((counters ->> k)::integer, 0) + 1))
    where id = o returning (counters ->> k)::integer, id_prefix into n, p;
  return p || case k when 'case' then '-IOD-' when 'warrant' then '-M-' else '-' end || lpad(n::text, 4, '0');
end $$;

create or replace function public.mdt_label(k text) returns text
language sql immutable set search_path = '' as $$
  select case k
    when 'ouvert' then 'Ouvert' when 'en_cours' then 'En cours' when 'localise' then 'Localisé' when 'arrete' then 'Arrêté'
    when 'rendu' then 'S’est rendu' when 'decede' then 'Décédé' when 'abandonne' then 'Abandonné'
    when 'normale' then 'Normale' when 'haute' then 'Haute' when 'most_wanted' then 'Most Wanted'
    when 'arret' then 'Mandat d’arrêt' when 'perquisition' then 'Mandat de perquisition' when 'ecoute' then 'Mise sur écoute' when 'saisie' then 'Mandat de saisie'
    when 'recrue' then 'Recrue' when 'formation' then 'En formation' when 'titulaire' then 'Titulaire'
    else k end
$$;

-- un service public : matricule automatique, liste noire, places limitées par grade
create or replace function public.mdt_members_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
declare o public.orgs; g public.grades; used integer;
begin
  select * into o from public.orgs where id = new.org_id;
  if o.sector is distinct from 'service_public' then return new; end if;
  if tg_op = 'INSERT' and new.matricule is null then new.matricule := public.next_num(new.org_id, 'matricule'); end if;
  if new.active and (tg_op = 'INSERT' or not old.active) and exists (select 1 from public.blacklist b where b.org_id = new.org_id
      and (lower(b.name) = lower(new.name) or (b.discord_id is not null and b.discord_id = new.discord_id))) then
    raise exception '% est sur la liste noire de %.', new.name, o.name;
  end if;
  if new.active and (tg_op = 'INSERT' or not old.active or new.grade_id is distinct from old.grade_id) then
    select * into g from public.grades where id = new.grade_id;
    if g.max_seats is not null then
      select count(*) into used from public.members m where m.grade_id = g.id and m.active and m.id <> new.id;
      if used >= g.max_seats then raise exception 'Plus de place en % : %/%.', g.name, used, g.max_seats; end if;
    end if;
  end if;
  if tg_op = 'UPDATE' and new.grade_id is distinct from old.grade_id then new.rank_since := now(); end if;
  return new;
end $$;
create trigger mdt_members_guard before insert or update of grade_id, active on public.members
  for each row execute function public.mdt_members_guard();

-- les modules d'un client correspondent à son type (entreprise ou service public)
create or replace function public.orgs_modules_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
declare bad text;
begin
  select x.name into bad from public.modules x where x.id = any (new.modules) and x.sector <> new.sector limit 1;
  if bad is not null then raise exception 'Le module « % » ne correspond pas à ce type de client.', bad; end if;
  return new;
end $$;
create trigger orgs_modules_guard before insert or update of modules, sector on public.orgs
  for each row execute function public.orgs_modules_guard();

-- l'agent connecté peut-il gérer cette fiche (accès Effectifs, grade pas plus haut que le sien) ;
-- sur sa propre fiche, seulement le grade le plus haut et seulement si p_self_ok
create or replace function public.manage_agent(p_member uuid, p_self_ok boolean,
  out member_id uuid, out org_id uuid, out name text, out by_name text, out my_rank integer, out their_rank integer)
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members; top integer;
begin
  select * into mem from public.members m where m.id = p_member;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'effectifs', 'effectifs');
  select g.rank into my_rank from public.grades g where g.id = me.grade_id;
  select g.rank into their_rank from public.grades g where g.id = mem.grade_id;
  select max(g.rank) into top from public.grades g where g.org_id = mem.org_id;
  if me.id = mem.id and not (p_self_ok and my_rank = top) then raise exception 'Tu ne peux pas faire ça sur ta propre fiche.'; end if;
  if their_rank > my_rank then raise exception 'Tu ne peux pas gérer un agent plus gradé que toi.'; end if;
  member_id := mem.id; org_id := mem.org_id; name := mem.name; by_name := me.name;
end $$;

create or replace function public.member_of_path(p text) returns boolean
language sql stable security definer set search_path = '' as $$
  select case when split_part(p, '/', 1) ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    then public.is_member(split_part(p, '/', 1)::uuid) else false end
$$;
create or replace function public.cap_of_path(p text, cap text) returns boolean
language sql stable security definer set search_path = '' as $$
  select case when split_part(p, '/', 1) ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    then public.has_cap(split_part(p, '/', 1)::uuid, cap) else false end
$$;
-- un fichier déposé dans le stockage, rangé dans le dossier du service
create or replace function public.stored(b text, o uuid, p text) returns boolean
language sql stable security definer set search_path = '' as $$
  select split_part(p, '/', 1) = o::text and exists (select 1 from storage.objects x where x.bucket_id = b and x.name = p)
$$;

-- ---------------------------------------------------------------- effectifs
create or replace function public.recruit_agent(p_org uuid, p_name text, p_login text, p_grade uuid, p_callsign text, p_discord text) returns json
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'effectifs', 'effectifs'); my_rank integer; g public.grades; low integer; mid uuid; mat text; tok text;
begin
  if length(trim(coalesce(p_name, ''))) < 2 then raise exception 'Indique le nom de l’agent.'; end if;
  select rank into my_rank from public.grades where id = me.grade_id;
  select * into g from public.grades where id = p_grade and org_id = p_org;
  if g.id is null then raise exception 'Grade inconnu.'; end if;
  if g.rank > my_rank then raise exception 'Tu ne peux pas donner un grade plus haut que le tien.'; end if;
  select min(rank) into low from public.grades where org_id = p_org;
  insert into public.members (org_id, name, login, grade_id, callsign, discord_id, stage)
    values (p_org, trim(p_name), lower(trim(p_login)), p_grade, nullif(trim(coalesce(p_callsign, '')), ''), nullif(trim(coalesce(p_discord, '')), ''),
      case when g.rank = low then 'recrue' else 'titulaire' end)
    returning id, matricule into mid, mat;
  insert into public.invitations (member_id) values (mid) returning token into tok;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (p_org, mid, 'recrutement', 'Recrutement · ' || g.name, me.name);
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Recrutement de ' || trim(p_name) || ' (' || mat || ')');
  return json_build_object('member_id', mid, 'matricule', mat, 'token', tok);
exception when unique_violation then
  raise exception 'L’identifiant « % » ou l’indicatif est déjà pris.', lower(trim(p_login));
end $$;

-- l'agent modifie ses coordonnées ; l'indicatif est donné par les Effectifs
create or replace function public.save_agent(p_member uuid, p_callsign text, p_phone text, p_discord text, p_fivem text, p_photo text) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members; boss boolean; v_photo text := nullif(trim(coalesce(p_photo, '')), '');
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'effectifs', null);
  boss := public.has_cap(mem.org_id, 'effectifs')
    and (select rank from public.grades where id = mem.grade_id) <= (select rank from public.grades where id = me.grade_id);
  if me.id <> mem.id and not boss then raise exception 'Tu ne peux modifier que ta propre fiche.'; end if;
  if v_photo is not null and not public.stored('mdt', mem.org_id, v_photo) then raise exception 'Photo introuvable : envoie-la de nouveau.'; end if;
  update public.members set
    callsign = case when boss then nullif(trim(coalesce(p_callsign, '')), '') else callsign end,
    phone = nullif(trim(coalesce(p_phone, '')), ''),
    discord_id = nullif(trim(coalesce(p_discord, '')), ''),
    fivem_id = nullif(trim(coalesce(p_fivem, '')), ''),
    photo = coalesce(v_photo, members.photo)
  where id = mem.id;
exception
  when unique_violation then raise exception 'L’indicatif « % » est déjà pris.', trim(p_callsign);
  when check_violation then raise exception 'Valeur refusée : l’identifiant Discord ne contient que des chiffres, l’indicatif fait 2 à 20 caractères.';
end $$;

create or replace function public.promote_agent(p_member uuid, p_grade uuid, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare r record; g public.grades; old_g public.grades;
begin
  select * into r from public.manage_agent(p_member, false);
  select * into g from public.grades where id = p_grade and org_id = r.org_id;
  if g.id is null then raise exception 'Grade inconnu.'; end if;
  if g.rank > r.my_rank then raise exception 'Tu ne peux pas donner un grade plus haut que le tien.'; end if;
  select x.* into old_g from public.grades x join public.members m on m.grade_id = x.id where m.id = r.member_id;
  if old_g.id = g.id then raise exception '% est déjà %.', r.name, g.name; end if;
  update public.members set grade_id = g.id where id = r.member_id;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (r.org_id, r.member_id,
    case when g.rank > old_g.rank then 'promotion' else 'retrogradation' end,
    old_g.name || ' → ' || g.name || coalesce(' · ' || nullif(trim(p_note), ''), ''), r.by_name);
  insert into public.journal (org_id, who, what) values (r.org_id, r.by_name, r.name || ' : ' || old_g.name || ' → ' || g.name);
end $$;

create or replace function public.set_agent_units(p_member uuid, p_units uuid[], p_main uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare r record; lbl text;
begin
  select * into r from public.manage_agent(p_member, true);
  if exists (select 1 from unnest(coalesce(p_units, '{}')) u where not exists (select 1 from public.units x where x.id = u and x.org_id = r.org_id and not x.archived)) then
    raise exception 'Unité inconnue.';
  end if;
  if p_main is not null and not p_main = any (coalesce(p_units, '{}')) then raise exception 'L’unité principale doit faire partie des unités choisies.'; end if;
  delete from public.member_units where member_id = r.member_id and not unit_id = any (coalesce(p_units, '{}'));
  insert into public.member_units (member_id, unit_id, org_id) select r.member_id, u, r.org_id from unnest(coalesce(p_units, '{}')) u on conflict do nothing;
  update public.member_units set main = (unit_id = p_main) where member_id = r.member_id;
  select string_agg(x.code || case when x.id = p_main then ' (principale)' else '' end, ', ' order by x.code) into lbl
    from public.units x where x.id = any (coalesce(p_units, '{}'));
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (r.org_id, r.member_id, 'unite', 'Unités : ' || coalesce(lbl, 'aucune'), r.by_name);
end $$;

create or replace function public.set_qualification(p_member uuid, p_qualification uuid, p_on boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare r record; q text; n integer;
begin
  select * into r from public.manage_agent(p_member, true);
  select name into q from public.qualifications where id = p_qualification and org_id = r.org_id;
  if q is null then raise exception 'Qualification inconnue.'; end if;
  if p_on then
    insert into public.member_qualifications (member_id, qualification_id, org_id, by_name) values (r.member_id, p_qualification, r.org_id, r.by_name) on conflict do nothing;
  else
    delete from public.member_qualifications where member_id = r.member_id and qualification_id = p_qualification;
  end if;
  get diagnostics n = row_count;
  if n > 0 then
    insert into public.career_events (org_id, member_id, kind, text, by_name) values (r.org_id, r.member_id, 'qualification', case when p_on then 'Qualification obtenue : ' else 'Qualification retirée : ' end || q, r.by_name);
  end if;
end $$;

create or replace function public.set_stage(p_member uuid, p_stage text) returns void
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  select * into r from public.manage_agent(p_member, false);
  if p_stage not in ('recrue', 'formation', 'titulaire') then raise exception 'Étape inconnue.'; end if;
  update public.members set stage = p_stage where id = r.member_id;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (r.org_id, r.member_id, 'formation', 'Parcours : ' || public.mdt_label(p_stage), r.by_name);
end $$;

-- félicitation, sanction, note visible de l'agent ; « commandement » : note interne, invisible de l'agent
create or replace function public.career_note(p_member uuid, p_kind text, p_text text) returns void
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  select * into r from public.manage_agent(p_member, false);
  if p_kind not in ('felicitation', 'sanction', 'note', 'commandement') then raise exception 'Type de note inconnu.'; end if;
  if length(trim(coalesce(p_text, ''))) < 3 then raise exception 'Écris la note.'; end if;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (r.org_id, r.member_id, p_kind, left(trim(p_text), 300), r.by_name);
end $$;

create or replace function public.agent_leave(p_member uuid, p_reason text, p_blacklist boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare r record; mem public.members;
begin
  select * into r from public.manage_agent(p_member, false);
  if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'Indique le motif du départ.'; end if;
  select * into mem from public.members where id = r.member_id;
  if not mem.active then raise exception '% a déjà quitté le service.', r.name; end if;
  update public.members set active = false, left_at = now(), left_reason = trim(p_reason) where id = r.member_id;
  update public.shifts set ended_at = now() where member_id = r.member_id and ended_at is null;
  delete from public.member_units where member_id = r.member_id;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (r.org_id, r.member_id, 'depart', left('Départ : ' || trim(p_reason), 300), r.by_name);
  if p_blacklist then
    insert into public.blacklist (org_id, name, discord_id, reason, by_name) values (r.org_id, mem.name, mem.discord_id, left(trim(p_reason), 200), r.by_name);
  end if;
  insert into public.journal (org_id, who, what) values (r.org_id, r.by_name, 'Départ de ' || r.name || case when p_blacklist then ' (liste noire)' else '' end || ' : ' || trim(p_reason));
end $$;

create or replace function public.agent_return(p_member uuid, p_grade uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare r record; g public.grades;
begin
  select * into r from public.manage_agent(p_member, false);
  select * into g from public.grades where id = p_grade and org_id = r.org_id;
  if g.id is null then raise exception 'Grade inconnu.'; end if;
  if g.rank > r.my_rank then raise exception 'Tu ne peux pas donner un grade plus haut que le tien.'; end if;
  update public.members set active = true, left_at = null, left_reason = null, grade_id = g.id where id = r.member_id;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (r.org_id, r.member_id, 'retour', 'Retour au service · ' || g.name, r.by_name);
  insert into public.journal (org_id, who, what) values (r.org_id, r.by_name, 'Retour de ' || r.name);
end $$;

create or replace function public.blacklist_add(p_org uuid, p_name text, p_discord text, p_reason text) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'effectifs', 'effectifs');
begin
  if length(trim(coalesce(p_name, ''))) < 2 then raise exception 'Indique le nom.'; end if;
  if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'Indique le motif.'; end if;
  insert into public.blacklist (org_id, name, discord_id, reason, by_name) values (p_org, trim(p_name), nullif(trim(coalesce(p_discord, '')), ''), trim(p_reason), me.name);
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Liste noire : ajout de ' || trim(p_name));
exception when check_violation then raise exception 'Valeur refusée : l’identifiant Discord ne contient que des chiffres, le motif fait 200 caractères au plus.';
end $$;

create or replace function public.blacklist_remove(p_id bigint) returns void
language plpgsql security definer set search_path = '' as $$
declare b public.blacklist; me public.members;
begin
  select * into b from public.blacklist where id = p_id;
  if b.id is null then raise exception 'Entrée inconnue.'; end if;
  me := public.need(b.org_id, 'effectifs', 'effectifs');
  delete from public.blacklist where id = b.id;
  insert into public.journal (org_id, who, what) values (b.org_id, me.name, 'Liste noire : retrait de ' || b.name);
end $$;

create or replace function public.save_unit(p_org uuid, p_id uuid, p_code text, p_name text, p_restricted boolean, p_archived boolean) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'effectifs', 'equipe'); uid uuid;
begin
  if p_id is null then
    insert into public.units (org_id, code, name, restricted) values (p_org, upper(trim(p_code)), trim(p_name), coalesce(p_restricted, false)) returning id into uid;
  else
    update public.units set code = upper(trim(p_code)), name = trim(p_name), restricted = coalesce(p_restricted, restricted), archived = coalesce(p_archived, archived)
      where id = p_id and org_id = p_org returning id into uid;
    if uid is null then raise exception 'Unité inconnue.'; end if;
  end if;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Unité ' || upper(trim(p_code)) || ' enregistrée');
  return uid;
exception
  when unique_violation then raise exception 'Le code « % » existe déjà.', upper(trim(p_code));
  when check_violation then raise exception 'Code de 2 à 8 lettres ou chiffres, nom de 2 à 60 caractères.';
end $$;

create or replace function public.save_qualification(p_org uuid, p_id uuid, p_name text, p_archived boolean) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'effectifs', 'equipe'); qid uuid;
begin
  if p_id is null then
    insert into public.qualifications (org_id, name) values (p_org, trim(p_name)) returning id into qid;
  else
    update public.qualifications set name = trim(p_name), archived = coalesce(p_archived, archived) where id = p_id and org_id = p_org returning id into qid;
    if qid is null then raise exception 'Qualification inconnue.'; end if;
  end if;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Qualification « ' || trim(p_name) || ' » enregistrée');
  return qid;
exception
  when unique_violation then raise exception 'La qualification « % » existe déjà.', trim(p_name);
  when check_violation then raise exception 'Nom de 2 à 40 caractères.';
end $$;

create or replace function public.set_grade_meta(p_grade uuid, p_badge text, p_seats integer, p_salary numeric) returns void
language plpgsql security definer set search_path = '' as $$
declare g public.grades; me public.members; used integer;
begin
  select * into g from public.grades where id = p_grade;
  if g.id is null then raise exception 'Grade inconnu.'; end if;
  me := public.need(g.org_id, 'effectifs', 'equipe');
  select count(*) into used from public.members where grade_id = g.id and active;
  if p_seats is not null and p_seats < used then raise exception 'Il y a déjà % agent(s) en % : mets au moins % places.', used, g.name, used; end if;
  update public.grades set badge = nullif(trim(coalesce(p_badge, '')), ''), max_seats = p_seats, salary = p_salary where id = g.id;
  insert into public.journal (org_id, who, what) values (g.org_id, me.name, 'Grade ' || g.name || ' : ' || coalesce(p_seats::text, 'sans limite') || ' place(s)');
exception when check_violation then raise exception 'Insigne de 12 caractères au plus, de 1 à 500 places, salaire positif.';
end $$;

-- ---------------------------------------------------------------- présence
create or replace function public.mdt_duty(p_org uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'presence', null); sid bigint;
begin
  update public.shifts set ended_at = now() where member_id = me.id and ended_at is null returning id into sid;
  if sid is not null then return false; end if;
  insert into public.shifts (org_id, member_id) values (p_org, me.id);
  update public.members set duty_status = 'dispo' where id = me.id;
  return true;
end $$;

create or replace function public.set_duty_status(p_org uuid, p_status text) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'presence', null);
begin
  if p_status not in ('dispo', 'intervention', 'pause') then raise exception 'Statut inconnu.'; end if;
  if not exists (select 1 from public.shifts where member_id = me.id and ended_at is null) then raise exception 'Prends d’abord ton service.'; end if;
  update public.members set duty_status = p_status where id = me.id;
end $$;

-- un Supervisory met fin au service d'un agent qui a oublié
create or replace function public.end_duty(p_member uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members; n integer;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'presence', 'valider');
  update public.shifts set ended_at = now() where member_id = mem.id and ended_at is null;
  get diagnostics n = row_count;
  if n = 0 then raise exception '% n’est pas en service.', mem.name; end if;
  insert into public.journal (org_id, who, what) values (mem.org_id, me.name, 'Fin de service forcée pour ' || mem.name);
end $$;

create or replace function public.declare_absence(p_org uuid, p_from date, p_to date, p_reason text) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'presence', null);
begin
  if p_from is null or p_to is null or p_to < p_from then raise exception 'Vérifie les dates.'; end if;
  if p_to > p_from + 60 then raise exception 'Une absence dure 60 jours au plus.'; end if;
  if p_to < current_date then raise exception 'Cette absence est déjà passée.'; end if;
  if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'Indique le motif.'; end if;
  insert into public.absences (org_id, member_id, starts_on, ends_on, reason) values (p_org, me.id, p_from, p_to, left(trim(p_reason), 200));
end $$;

create or replace function public.decide_absence(p_id bigint, p_ok boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.absences; me public.members; who text;
begin
  select * into a from public.absences where id = p_id for update;
  if a.id is null then raise exception 'Absence inconnue.'; end if;
  me := public.need(a.org_id, 'presence', 'valider');
  if a.member_id = me.id then raise exception 'Un autre responsable doit valider ta propre absence.'; end if;
  if a.status <> 'attente' then raise exception 'Cette absence a déjà été traitée.'; end if;
  update public.absences set status = case when p_ok then 'validee' else 'refusee' end, decided_by = me.name, decided_at = now() where id = a.id;
  select name into who from public.members where id = a.member_id;
  insert into public.journal (org_id, who, what) values (a.org_id, me.name, 'Absence de ' || who || case when p_ok then ' validée' else ' refusée' end);
end $$;

create or replace function public.save_agenda(p_org uuid, p_id uuid, p_title text, p_kind text, p_starts timestamptz, p_ends timestamptz,
  p_place text, p_unit uuid, p_note text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'presence', 'valider'); aid uuid;
begin
  if length(trim(coalesce(p_title, ''))) < 3 then raise exception 'Donne un titre.'; end if;
  if p_starts is null or p_ends is null or p_ends <= p_starts then raise exception 'Vérifie l’heure de début et de fin.'; end if;
  if p_unit is not null and not exists (select 1 from public.units where id = p_unit and org_id = p_org) then raise exception 'Unité inconnue.'; end if;
  if p_id is null then
    insert into public.agenda (org_id, title, kind, starts_at, ends_at, place, unit_id, note, by_name)
      values (p_org, trim(p_title), coalesce(p_kind, 'operation'), p_starts, p_ends, nullif(trim(coalesce(p_place, '')), ''), p_unit, nullif(trim(coalesce(p_note, '')), ''), me.name)
      returning id into aid;
  else
    update public.agenda set title = trim(p_title), kind = coalesce(p_kind, kind), starts_at = p_starts, ends_at = p_ends, place = nullif(trim(coalesce(p_place, '')), ''),
      unit_id = p_unit, note = nullif(trim(coalesce(p_note, '')), '') where id = p_id and org_id = p_org returning id into aid;
    if aid is null then raise exception 'Créneau inconnu.'; end if;
  end if;
  return aid;
end $$;

create or replace function public.delete_agenda(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.agenda;
begin
  select * into a from public.agenda where id = p_id;
  if a.id is null then raise exception 'Créneau inconnu.'; end if;
  perform public.need(a.org_id, 'presence', 'valider');
  delete from public.agenda where id = a.id;
end $$;

create or replace function public.agenda_join(p_id uuid, p_on boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.agenda; me public.members;
begin
  select * into a from public.agenda where id = p_id;
  if a.id is null then raise exception 'Créneau inconnu.'; end if;
  me := public.need(a.org_id, 'presence', null);
  if a.ends_at < now() then raise exception 'Ce créneau est terminé.'; end if;
  if p_on then
    insert into public.agenda_staff (agenda_id, member_id, org_id) values (a.id, me.id, a.org_id) on conflict do nothing;
  else
    delete from public.agenda_staff where agenda_id = a.id and member_id = me.id;
  end if;
end $$;

-- ---------------------------------------------------------------- individus et dossiers
create or replace function public.save_person(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'dossiers', 'dossiers'); pid uuid; v_photo text := nullif(trim(p ->> 'photo'), '');
begin
  if length(coalesce(trim(p ->> 'first_name'), '')) < 1 or length(coalesce(trim(p ->> 'last_name'), '')) < 1 then raise exception 'Indique le prénom et le nom.'; end if;
  if v_photo is not null and not public.stored('mdt', p_org, v_photo) then raise exception 'Photo introuvable : envoie-la de nouveau.'; end if;
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
    job = nullif(trim(p ->> 'job'), ''), gang = nullif(trim(p ->> 'gang'), ''),
    dangerous = coalesce((p ->> 'dangerous')::boolean, false), armed = coalesce((p ->> 'armed')::boolean, false),
    notes = nullif(trim(p ->> 'notes'), ''), photo = coalesce(v_photo, people.photo),
    vehicles = case when jsonb_typeof(p -> 'vehicles') = 'array' then p -> 'vehicles' else '[]' end,
    places = case when jsonb_typeof(p -> 'places') = 'array' then p -> 'places' else '[]' end, updated_at = now()
  where id = pid;
  return pid;
exception
  when check_violation then raise exception 'Une valeur est refusée : vérifie la taille (100 à 250 cm) et la longueur des champs (10 véhicules et 10 lieux au plus).';
  when invalid_datetime_format or datetime_field_overflow or invalid_text_representation then raise exception 'Date de naissance ou taille invalide.';
end $$;

create or replace function public.open_case(p_org uuid, p_person uuid, p_title text, p_priority text, p_charges text, p_lead uuid) returns json
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'dossiers', 'dossiers'); cid uuid; n text; who text;
begin
  select first_name || ' ' || last_name into who from public.people where id = p_person and org_id = p_org;
  if who is null then raise exception 'Individu inconnu.'; end if;
  if length(trim(coalesce(p_title, ''))) < 3 then raise exception 'Donne un titre au dossier.'; end if;
  if p_lead is not null and not exists (select 1 from public.members where id = p_lead and org_id = p_org and active) then raise exception 'Agent inconnu.'; end if;
  n := public.next_num(p_org, 'case');
  insert into public.cases (org_id, num, person_id, title, priority, charges, lead_id, by_name)
    values (p_org, n, p_person, trim(p_title), coalesce(p_priority, 'normale'), nullif(trim(coalesce(p_charges, '')), ''), coalesce(p_lead, me.id), me.name)
    returning id into cid;
  insert into public.case_agents (case_id, member_id, org_id) values (cid, coalesce(p_lead, me.id), p_org);
  insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (cid, p_org, 'statut', 'Dossier ouvert : ' || who, me.id, me.name);
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Dossier ' || n || ' ouvert (' || who || ')');
  return json_build_object('id', cid, 'num', n);
end $$;

create or replace function public.update_case(p_case uuid, p_title text, p_status text, p_priority text, p_lead uuid, p_charges text, p_last_seen text) returns void
language plpgsql security definer set search_path = '' as $$
declare c public.cases; me public.members; seen text := nullif(trim(coalesce(p_last_seen, '')), '');
begin
  select * into c from public.cases where id = p_case for update;
  if c.id is null then raise exception 'Dossier inconnu.'; end if;
  me := public.need(c.org_id, 'dossiers', 'dossiers');
  if p_lead is not null and not exists (select 1 from public.members where id = p_lead and org_id = c.org_id and active) then raise exception 'Agent inconnu.'; end if;
  update public.cases set title = coalesce(nullif(trim(coalesce(p_title, '')), ''), title), status = coalesce(p_status, status), priority = coalesce(p_priority, priority),
    lead_id = coalesce(p_lead, lead_id), charges = nullif(trim(coalesce(p_charges, '')), ''),
    last_seen = coalesce(seen, last_seen), last_seen_at = case when seen is distinct from c.last_seen and seen is not null then now() else last_seen_at end,
    closed_at = case when coalesce(p_status, status) in ('arrete', 'rendu', 'decede', 'abandonne') then coalesce(closed_at, now()) else null end
  where id = c.id;
  if p_lead is not null then insert into public.case_agents (case_id, member_id, org_id) values (c.id, p_lead, c.org_id) on conflict do nothing; end if;
  if p_status is not null and p_status <> c.status then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (c.id, c.org_id, 'statut', 'Statut : ' || public.mdt_label(p_status), me.id, me.name);
    insert into public.journal (org_id, who, what) values (c.org_id, me.name, 'Dossier ' || c.num || ' : ' || public.mdt_label(p_status));
  end if;
  if p_priority is not null and p_priority <> c.priority then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (c.id, c.org_id, 'statut', 'Priorité : ' || public.mdt_label(p_priority), me.id, me.name);
  end if;
  if seen is distinct from c.last_seen and seen is not null then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (c.id, c.org_id, 'observation', 'Dernière localisation : ' || seen, me.id, me.name);
  end if;
exception when check_violation then raise exception 'Une valeur est refusée : vérifie le statut, la priorité et la longueur des champs.';
end $$;

create or replace function public.case_note(p_case uuid, p_kind text, p_text text) returns void
language plpgsql security definer set search_path = '' as $$
declare c public.cases; me public.members;
begin
  select * into c from public.cases where id = p_case;
  if c.id is null then raise exception 'Dossier inconnu.'; end if;
  me := public.need(c.org_id, 'dossiers', 'dossiers');
  if p_kind not in ('note', 'piste', 'observation') then raise exception 'Type d’entrée inconnu.'; end if;
  if length(trim(coalesce(p_text, ''))) < 2 then raise exception 'Écris quelque chose.'; end if;
  insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (c.id, c.org_id, p_kind, left(trim(p_text), 1000), me.id, me.name);
end $$;

create or replace function public.set_case_agents(p_case uuid, p_members uuid[]) returns void
language plpgsql security definer set search_path = '' as $$
declare c public.cases; me public.members; lbl text;
begin
  select * into c from public.cases where id = p_case;
  if c.id is null then raise exception 'Dossier inconnu.'; end if;
  me := public.need(c.org_id, 'dossiers', 'dossiers');
  if exists (select 1 from unnest(coalesce(p_members, '{}')) x where not exists (select 1 from public.members m where m.id = x and m.org_id = c.org_id and m.active)) then
    raise exception 'Agent inconnu.';
  end if;
  delete from public.case_agents where case_id = c.id and not member_id = any (coalesce(p_members, '{}'));
  insert into public.case_agents (case_id, member_id, org_id) select c.id, x, c.org_id from unnest(coalesce(p_members, '{}')) x on conflict do nothing;
  select string_agg(m.name, ', ' order by m.name) into lbl from public.members m where m.id = any (coalesce(p_members, '{}'));
  insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (c.id, c.org_id, 'agent', 'Agents : ' || coalesce(lbl, 'aucun'), me.id, me.name);
end $$;

create or replace function public.attach_case_file(p_case uuid, p_path text, p_name text, p_mime text, p_size integer) returns void
language plpgsql security definer set search_path = '' as $$
declare c public.cases; me public.members;
begin
  select * into c from public.cases where id = p_case;
  if c.id is null then raise exception 'Dossier inconnu.'; end if;
  me := public.need(c.org_id, 'dossiers', 'dossiers');
  if not public.stored('mdt', c.org_id, p_path) then raise exception 'Fichier introuvable : envoie-le de nouveau.'; end if;
  insert into public.case_files (case_id, org_id, path, name, mime, size, by_name) values (c.id, c.org_id, p_path, left(trim(p_name), 120), left(p_mime, 80), p_size, me.name);
  insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (c.id, c.org_id, 'fichier', 'Pièce jointe : ' || left(trim(p_name), 120), me.id, me.name);
end $$;

-- ---------------------------------------------------------------- mandats
create or replace function public.save_warrant(p_org uuid, p_id uuid, p_kind text, p_person uuid, p_case uuid, p_target text, p_place text, p_reason text, p_days integer) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'mandats', 'mandats'); wid uuid; tgt text := nullif(trim(coalesce(p_target, '')), '');
begin
  if p_person is not null then
    select first_name || ' ' || last_name into tgt from public.people where id = p_person and org_id = p_org;
    if tgt is null then raise exception 'Individu inconnu.'; end if;
  end if;
  if tgt is null then raise exception 'Indique la personne ou le lieu visé.'; end if;
  if p_case is not null and not exists (select 1 from public.cases where id = p_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  if length(trim(coalesce(p_reason, ''))) < 10 then raise exception 'Motive la demande (10 caractères au moins) : la Justice la lit avant de signer.'; end if;
  if p_id is null then
    insert into public.warrants (org_id, num, kind, person_id, case_id, target, place, reason, valid_days, requested_by, requested_member)
      values (p_org, public.next_num(p_org, 'warrant'), p_kind, p_person, p_case, tgt, nullif(trim(coalesce(p_place, '')), ''), trim(p_reason), coalesce(p_days, 7), me.name, me.id)
      returning id into wid;
  else
    update public.warrants set kind = p_kind, person_id = p_person, case_id = p_case, target = tgt, place = nullif(trim(coalesce(p_place, '')), ''),
      reason = trim(p_reason), valid_days = coalesce(p_days, valid_days) where id = p_id and org_id = p_org and status = 'brouillon' returning id into wid;
    if wid is null then raise exception 'Seul un brouillon se modifie.'; end if;
  end if;
  return wid;
exception when check_violation then raise exception 'Une valeur est refusée : type de mandat, validité (1 à 30 jours) ou longueur du motif (1 500 caractères au plus).';
end $$;

create or replace function public.send_warrant(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.warrants; me public.members;
begin
  select * into w from public.warrants where id = p_id for update;
  if w.id is null then raise exception 'Mandat inconnu.'; end if;
  me := public.need(w.org_id, 'mandats', 'mandats');
  if w.status <> 'brouillon' then raise exception 'Ce mandat a déjà été envoyé.'; end if;
  update public.warrants set status = 'envoye', sent_at = now() where id = w.id;
  insert into public.journal (org_id, who, what) values (w.org_id, me.name, 'Mandat ' || w.num || ' envoyé à la Justice');
  if w.case_id is not null then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (w.case_id, w.org_id, 'mandat', public.mdt_label(w.kind) || ' ' || w.num || ' demandé', me.id, me.name);
  end if;
end $$;

create or replace function public.require_justice() returns text
language plpgsql stable security definer set search_path = '' as $$
begin
  if public.platform_role() is distinct from 'justice' then raise exception 'Réservé à la Justice.'; end if;
  return public.caller_name();
end $$;

create or replace function public.decide_warrant(p_id uuid, p_ok boolean, p_note text, p_days integer) returns void
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_justice(); w public.warrants; note text := nullif(trim(coalesce(p_note, '')), ''); d integer;
begin
  select * into w from public.warrants where id = p_id for update;
  if w.id is null then raise exception 'Mandat inconnu.'; end if;
  if w.status <> 'envoye' then raise exception 'Ce mandat n’attend pas de décision.'; end if;
  if not p_ok and length(coalesce(note, '')) < 5 then raise exception 'Motive le refus.'; end if;
  d := least(greatest(coalesce(p_days, w.valid_days), 1), 30);
  update public.warrants set status = case when p_ok then 'signe' else 'refuse' end, decided_at = now(), decided_by = who, judge_note = left(note, 500),
    valid_days = d, expires_at = case when p_ok then now() + make_interval(days => d) end where id = w.id;
  insert into public.journal (org_id, who, what) values (w.org_id, 'Justice · ' || who, 'Mandat ' || w.num || case when p_ok then ' signé (' || d || ' j)' else ' refusé' end);
  if w.case_id is not null then
    insert into public.case_log (case_id, org_id, kind, text, by_name) values (w.case_id, w.org_id, 'mandat', w.num || case when p_ok then ' signé par la Justice' else ' refusé par la Justice' end || coalesce(' : ' || note, ''), 'Justice · ' || who);
  end if;
end $$;

create or replace function public.execute_warrant(p_id uuid, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.warrants; me public.members;
begin
  select * into w from public.warrants where id = p_id for update;
  if w.id is null then raise exception 'Mandat inconnu.'; end if;
  me := public.need(w.org_id, 'mandats', null);
  if w.status <> 'signe' then raise exception 'Seul un mandat signé s’exécute.'; end if;
  if w.expires_at < now() then raise exception 'Ce mandat a expiré le %. Demande un nouveau mandat.', to_char(w.expires_at at time zone 'Europe/Paris', 'DD/MM à HH24:MI'); end if;
  update public.warrants set status = 'execute', executed_at = now(), executed_by = me.name, exec_note = left(nullif(trim(coalesce(p_note, '')), ''), 300) where id = w.id;
  insert into public.journal (org_id, who, what) values (w.org_id, me.name, 'Mandat ' || w.num || ' exécuté');
  if w.case_id is not null then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (w.case_id, w.org_id, 'mandat', w.num || ' exécuté' || coalesce(' : ' || nullif(trim(p_note), ''), ''), me.id, me.name);
  end if;
end $$;

create or replace function public.cancel_warrant(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.warrants; me public.members;
begin
  select * into w from public.warrants where id = p_id for update;
  if w.id is null then raise exception 'Mandat inconnu.'; end if;
  me := public.need(w.org_id, 'mandats', 'mandats');
  if w.status not in ('brouillon', 'envoye', 'signe') then raise exception 'Ce mandat ne s’annule plus.'; end if;
  update public.warrants set status = 'annule' where id = w.id;
  insert into public.journal (org_id, who, what) values (w.org_id, me.name, 'Mandat ' || w.num || ' annulé');
end $$;

-- ce que la Justice voit : toutes les demandes envoyées, de tous les services
create or replace function public.justice_warrants() returns json
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_justice();
  return (select coalesce(json_agg(json_build_object('id', w.id, 'num', w.num, 'kind', w.kind, 'target', w.target, 'place', w.place, 'reason', w.reason,
    'status', w.status, 'valid_days', w.valid_days, 'sent_at', w.sent_at, 'decided_at', w.decided_at, 'decided_by', w.decided_by, 'judge_note', w.judge_note,
    'expires_at', w.expires_at, 'executed_at', w.executed_at, 'requested_by', w.requested_by, 'org', o.name, 'org_code', o.code, 'case_num', c.num,
    'case_title', c.title, 'charges', c.charges, 'dangerous', p.dangerous, 'armed', p.armed, 'alias', p.alias) order by w.sent_at desc), '[]'::json)
    from public.warrants w join public.orgs o on o.id = w.org_id left join public.cases c on c.id = w.case_id left join public.people p on p.id = w.person_id
    where w.status <> 'brouillon');
end $$;

-- ---------------------------------------------------------------- Most Wanted
create or replace function public.wanted_snapshot(o uuid) returns void
language sql security definer set search_path = '' as $$
  insert into public.wanted_history (org_id, week_start, list)
  values (o, public.week_of(now()), (select coalesce(jsonb_agg(jsonb_build_object('rank', w.rank, 'person_id', w.person_id, 'name', p.first_name || ' ' || p.last_name,
    'alias', p.alias, 'reward', w.reward) order by w.rank), '[]'::jsonb) from public.wanted w join public.people p on p.id = w.person_id where w.org_id = o))
  on conflict (org_id, week_start) do update set list = excluded.list, updated_at = now()
$$;

-- place un individu au rang voulu ; les suivants descendent, celui qui sort du classement est retiré
create or replace function public.set_wanted(p_org uuid, p_person uuid, p_rank integer, p_reward numeric, p_reason text, p_case uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'wanted', 'wanted'); size integer; nm text; ids uuid[]; pos integer; wid uuid;
begin
  select wanted_size into size from public.orgs where id = p_org;
  select first_name || ' ' || last_name into nm from public.people where id = p_person and org_id = p_org;
  if nm is null then raise exception 'Individu inconnu.'; end if;
  if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'Indique le motif.'; end if;
  if coalesce(p_reward, 0) < 0 then raise exception 'La récompense ne peut pas être négative.'; end if;
  if p_case is not null and not exists (select 1 from public.cases where id = p_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  ids := array(select w.id from public.wanted w where w.org_id = p_org and w.person_id <> p_person order by w.rank);
  pos := least(greatest(coalesce(p_rank, 1), 1), coalesce(array_length(ids, 1), 0) + 1, size);
  insert into public.wanted (org_id, person_id, case_id, rank, reward, reason, by_name)
    values (p_org, p_person, p_case, pos, round(coalesce(p_reward, 0), 2), left(trim(p_reason), 200), me.name)
    on conflict (org_id, person_id) do update set case_id = excluded.case_id, rank = excluded.rank, reward = excluded.reward, reason = excluded.reason
    returning id into wid;
  ids := ids[1:pos - 1] || wid || ids[pos:];
  delete from public.wanted where org_id = p_org and id = any (ids[size + 1:]);
  update public.wanted w set rank = x.n from unnest(ids[1:size]) with ordinality as x(id, n) where w.id = x.id;
  if p_case is not null then
    update public.cases set priority = 'most_wanted' where id = p_case;
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (p_case, p_org, 'wanted', 'Most Wanted · rang ' || pos, me.id, me.name);
  end if;
  perform public.wanted_snapshot(p_org);
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Most Wanted : ' || nm || ' au rang ' || pos);
end $$;

create or replace function public.remove_wanted(p_id uuid, p_reason text) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.wanted; me public.members; nm text;
begin
  select * into w from public.wanted where id = p_id;
  if w.id is null then raise exception 'Entrée inconnue.'; end if;
  me := public.need(w.org_id, 'wanted', 'wanted');
  select first_name || ' ' || last_name into nm from public.people where id = w.person_id;
  delete from public.wanted where id = w.id;
  update public.wanted x set rank = y.n from (select z.id, row_number() over (order by z.rank) as n from public.wanted z where z.org_id = w.org_id) y where x.id = y.id;
  if w.case_id is not null then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (w.case_id, w.org_id, 'wanted', 'Retiré du Most Wanted' || coalesce(' : ' || nullif(trim(p_reason), ''), ''), me.id, me.name);
  end if;
  perform public.wanted_snapshot(w.org_id);
  insert into public.journal (org_id, who, what) values (w.org_id, me.name, 'Most Wanted : ' || nm || ' retiré' || coalesce(' (' || nullif(trim(p_reason), '') || ')', ''));
end $$;

-- l'affiche publique : la photo est copiée dans le stockage public (« avis ») par le site, puis publiée ici
create or replace function public.publish_wanted(p_id uuid, p_on boolean, p_photo text) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.wanted; me public.members; v_photo text := nullif(trim(coalesce(p_photo, '')), '');
begin
  select * into w from public.wanted where id = p_id;
  if w.id is null then raise exception 'Entrée inconnue.'; end if;
  me := public.need(w.org_id, 'wanted', 'wanted');
  if v_photo is not null and not public.stored('avis', w.org_id, v_photo) then raise exception 'Photo introuvable : envoie-la de nouveau.'; end if;
  if p_on and v_photo is null and w.public_photo is null then raise exception 'Ajoute une photo à l’affiche avant de la publier.'; end if;
  update public.wanted set published = p_on, public_photo = coalesce(v_photo, public_photo) where id = w.id;
end $$;

create or replace function public.set_wanted_size(p_org uuid, p_size integer) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'wanted', 'wanted');
begin
  if p_size not in (5, 10) then raise exception 'Le classement compte 5 ou 10 places.'; end if;
  update public.orgs set wanted_size = p_size where id = p_org;
  delete from public.wanted where org_id = p_org and rank > p_size;
  perform public.wanted_snapshot(p_org);
end $$;

-- page publique (sans connexion) : seulement les avis publiés
create or replace function public.wanted_public(p_code text) returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object('code', o.code, 'name', o.name, 'kind', o.kind, 'list', coalesce((
    select json_agg(json_build_object('id', w.id, 'rank', w.rank, 'name', p.first_name || ' ' || p.last_name, 'alias', p.alias, 'reward', w.reward,
      'reason', w.reason, 'photo', w.public_photo, 'dangerous', p.dangerous, 'armed', p.armed, 'sex', p.sex, 'height', p.height, 'eyes', p.eyes,
      'hair', p.hair, 'marks', p.marks) order by w.rank)
    from public.wanted w join public.people p on p.id = w.person_id where w.org_id = o.id and w.published), '[]'::json))
  from public.orgs o where o.code = lower(trim(p_code)) and o.sector = 'service_public' and 'wanted' = any (o.modules)
$$;

-- signalement anonyme depuis la page publique ; 30 par heure et par service au plus
create or replace function public.send_tip(p_code text, p_wanted uuid, p_text text, p_contact text) returns void
language plpgsql security definer set search_path = '' as $$
declare o uuid; n integer; who text; wid uuid := p_wanted;
begin
  select id into o from public.orgs where code = lower(trim(p_code)) and sector = 'service_public' and 'wanted' = any (modules);
  if o is null then raise exception 'Service inconnu.'; end if;
  if length(trim(coalesce(p_text, ''))) < 10 then raise exception 'Décris ce que tu as vu (10 caractères au moins).'; end if;
  if length(trim(p_text)) > 1000 then raise exception 'Ton message est trop long (1 000 caractères au plus).'; end if;
  select count(*) into n from public.tips where org_id = o and at > now() - interval '1 hour';
  if n >= 30 then raise exception 'Trop de signalements en ce moment. Réessaie dans une heure.'; end if;
  select p.first_name || ' ' || p.last_name into who from public.wanted w join public.people p on p.id = w.person_id where w.id = wid and w.org_id = o and w.published;
  if who is null then wid := null; end if;
  insert into public.tips (org_id, wanted_id, person_name, text, contact) values (o, wid, who, trim(p_text), left(nullif(trim(coalesce(p_contact, '')), ''), 80));
end $$;

create or replace function public.handle_tip(p_id bigint, p_status text) returns void
language plpgsql security definer set search_path = '' as $$
declare t public.tips; me public.members;
begin
  select * into t from public.tips where id = p_id;
  if t.id is null then raise exception 'Signalement inconnu.'; end if;
  me := public.need(t.org_id, 'wanted', 'wanted');
  if p_status not in ('nouveau', 'utile', 'inutile', 'traite') then raise exception 'Statut inconnu.'; end if;
  update public.tips set status = p_status, handled_by = me.name where id = t.id;
end $$;

-- ---------------------------------------------------------------- annonces
create or replace function public.post_announcement(p_org uuid, p_title text, p_body text, p_level text, p_pinned boolean) returns bigint
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'annonces', 'annonces'); aid bigint;
begin
  if length(trim(coalesce(p_title, ''))) < 3 then raise exception 'Donne un titre.'; end if;
  if length(trim(coalesce(p_body, ''))) < 3 then raise exception 'Écris l’annonce.'; end if;
  insert into public.announcements (org_id, title, body, level, pinned, by_name)
    values (p_org, left(trim(p_title), 80), left(trim(p_body), 2000), coalesce(p_level, 'info'), coalesce(p_pinned, false), me.name) returning id into aid;
  insert into public.announcement_reads (announcement_id, member_id, org_id) values (aid, me.id, p_org);
  return aid;
end $$;

create or replace function public.delete_announcement(p_id bigint) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.announcements;
begin
  select * into a from public.announcements where id = p_id;
  if a.id is null then raise exception 'Annonce inconnue.'; end if;
  perform public.need(a.org_id, 'annonces', 'annonces');
  delete from public.announcements where id = a.id;
end $$;

create or replace function public.read_announcement(p_id bigint) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.announcements; me public.members;
begin
  select * into a from public.announcements where id = p_id;
  if a.id is null then raise exception 'Annonce inconnue.'; end if;
  me := public.need(a.org_id, 'annonces', null);
  insert into public.announcement_reads (announcement_id, member_id, org_id) values (a.id, me.id, a.org_id) on conflict do nothing;
end $$;

-- ---------------------------------------------------------------- éditeur : services publics et accès de plateforme
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
  insert into public.members (org_id, name, login, grade_id, callsign) values (oid, trim(p_owner_name), lower(trim(p_owner_login)), top, 'Marshal-1') returning id into mid;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (oid, mid, 'recrutement', 'Prise de fonction · Director', who);
  insert into public.invitations (member_id) values (mid) returning token into tok;
  insert into public.journal (org_id, who, what) values (oid, who, 'Service créé par l’éditeur');
  return json_build_object('org_id', oid, 'token', tok);
exception when unique_violation then
  raise exception 'L’adresse « % » est déjà prise.', lower(trim(p_code));
end $$;

create or replace function public.create_claim(p_role text, p_name text) returns text
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_editeur(); tok text;
begin
  if p_role not in ('fisc', 'justice') then raise exception 'Rôle inconnu.'; end if;
  if length(trim(coalesce(p_name, ''))) < 2 then raise exception 'Indique le nom affiché.'; end if;
  insert into public.platform_claims (role, display_name) values (p_role, left(trim(p_name), 60)) returning token into tok;
  return tok;
end $$;

create or replace function public.platform_access() returns json
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_editeur();
  return json_build_object(
    'accounts', (select coalesce(json_agg(json_build_object('user_id', r.user_id, 'role', r.role, 'display_name', r.display_name, 'me', r.user_id = auth.uid()) order by r.role, r.display_name), '[]'::json) from public.platform_roles r),
    'claims', (select coalesce(json_agg(json_build_object('token', c.token, 'role', c.role, 'display_name', c.display_name, 'expires_at', c.expires_at) order by c.expires_at desc), '[]'::json)
      from public.platform_claims c where c.used_at is null and c.expires_at > now()));
end $$;

create or replace function public.revoke_platform(p_user uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_editeur();
  if p_user = auth.uid() then raise exception 'Tu ne peux pas retirer ton propre accès.'; end if;
  delete from public.platform_roles where user_id = p_user;
end $$;

-- ---------------------------------------------------------------- fonctions du socle qui connaissent maintenant le type de client
create or replace function public.org_public(p_code text) returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'is_association', o.is_association, 'sector', o.sector,
    'discord_login', o.discord_login, 'password_login', o.password_login, 'modules', o.modules)
  from public.orgs o where o.code = lower(trim(p_code))
$$;

create or replace function public.my_spaces() returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object(
    'role', (select r.role from public.platform_roles r where r.user_id = auth.uid()),
    'display_name', (select r.display_name from public.platform_roles r where r.user_id = auth.uid()),
    'orgs', coalesce((
      select json_agg(json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'is_association', o.is_association, 'sector', o.sector,
        'id_prefix', o.id_prefix, 'modules', o.modules, 'licence_until', o.licence_until, 'member_id', m.id, 'member_name', m.name, 'grade', g.name,
        'caps', case when g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o.id) then public.all_caps() else g.caps end) order by o.name)
      from public.members m join public.orgs o on o.id = m.org_id join public.grades g on g.id = m.grade_id
      where m.user_id = auth.uid() and m.active), '[]'::json))
$$;

create or replace function public.editor_overview() returns json
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_editeur();
  return (select coalesce(json_agg(json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'owner_name', o.owner_name, 'is_association', o.is_association,
    'sector', o.sector, 'modules', o.modules, 'licence_until', o.licence_until, 'created_at', o.created_at,
    'price', (select coalesce(sum(m.price), 0) from public.modules m where m.id = any (o.modules)),
    'accounts', (select count(*) from public.members x where x.org_id = o.id and x.user_id is not null and x.active),
    'pending', (select count(*) from public.members x where x.org_id = o.id and x.user_id is null and x.active)) order by o.name), '[]'::json)
  from public.orgs o);
end $$;

-- le fisc ne contrôle que les entreprises
create or replace function public.fisc_overview() returns json
language plpgsql stable security definer set search_path = '' as $$
declare
  last_w date := public.week_of(now()) - 7;
  res json;
begin
  perform public.require_fisc();
  select coalesce(json_agg(x order by x.name), '[]'::json) into res from (
    select o.id, o.code, o.name, o.kind, o.owner_name, o.is_association,
      public.week_book(o.id, last_w) as last_week,
      (select count(*) from public.controls c where c.org_id = o.id and c.closed_at is null) as open_controls,
      (select count(*) from public.ledger l where l.org_id = o.id and l.kind = 'out' and l.category = 'Divers' and l.amount >= 5000 and l.at > now() - interval '30 days') as cash_out,
      (select coalesce(max(greatest(0, bk.revenue - d.revenue)), 0) from public.declarations d
         cross join lateral (select (public.week_book(o.id, d.week_start) ->> 'revenue')::numeric as revenue) bk
         where d.org_id = o.id and d.week_start >= last_w - 21) as underdeclared
    from public.orgs o where o.sector = 'entreprise') x;
  return res;
end $$;

-- ---------------------------------------------------------------- règles de lecture
alter table public.units enable row level security;
alter table public.member_units enable row level security;
alter table public.qualifications enable row level security;
alter table public.member_qualifications enable row level security;
alter table public.career_events enable row level security;
alter table public.blacklist enable row level security;
alter table public.absences enable row level security;
alter table public.agenda enable row level security;
alter table public.agenda_staff enable row level security;
alter table public.people enable row level security;
alter table public.cases enable row level security;
alter table public.case_agents enable row level security;
alter table public.case_log enable row level security;
alter table public.case_files enable row level security;
alter table public.warrants enable row level security;
alter table public.wanted enable row level security;
alter table public.wanted_history enable row level security;
alter table public.tips enable row level security;
alter table public.announcements enable row level security;
alter table public.announcement_reads enable row level security;

create policy units_read on public.units for select to authenticated using (public.is_member(org_id));
create policy member_units_read on public.member_units for select to authenticated using (public.is_member(org_id));
create policy qualifications_read on public.qualifications for select to authenticated using (public.is_member(org_id));
create policy member_qualifications_read on public.member_qualifications for select to authenticated using (public.is_member(org_id));
create policy career_events_read on public.career_events for select to authenticated
  using (public.has_cap(org_id, 'effectifs') or (member_id = (public.caller_member(org_id)).id and kind <> 'commandement'));
create policy blacklist_read on public.blacklist for select to authenticated using (public.has_cap(org_id, 'effectifs'));
create policy absences_read on public.absences for select to authenticated using (public.is_member(org_id));
create policy agenda_read on public.agenda for select to authenticated using (public.is_member(org_id));
create policy agenda_staff_read on public.agenda_staff for select to authenticated using (public.is_member(org_id));
create policy people_read on public.people for select to authenticated using (public.is_member(org_id));
create policy cases_read on public.cases for select to authenticated using (public.is_member(org_id));
create policy case_agents_read on public.case_agents for select to authenticated using (public.is_member(org_id));
create policy case_log_read on public.case_log for select to authenticated using (public.is_member(org_id));
create policy case_files_read on public.case_files for select to authenticated using (public.is_member(org_id));
create policy warrants_read on public.warrants for select to authenticated
  using (public.is_member(org_id) or ((select public.platform_role()) = 'justice' and status <> 'brouillon'));
create policy wanted_read on public.wanted for select to authenticated using (public.is_member(org_id));
create policy wanted_history_read on public.wanted_history for select to authenticated using (public.is_member(org_id));
create policy tips_read on public.tips for select to authenticated using (public.has_cap(org_id, 'wanted'));
create policy announcements_read on public.announcements for select to authenticated using (public.is_member(org_id));
create policy announcement_reads_read on public.announcement_reads for select to authenticated using (public.is_member(org_id));

-- ---------------------------------------------------------------- droits
do $$
declare
  t text;
  f regprocedure;
  tables text[] := array['units', 'member_units', 'qualifications', 'member_qualifications', 'career_events', 'blacklist', 'absences', 'agenda', 'agenda_staff',
    'people', 'cases', 'case_agents', 'case_log', 'case_files', 'warrants', 'wanted', 'wanted_history', 'tips', 'announcements', 'announcement_reads'];
  internal text[] := array['next_num', 'mdt_label', 'mdt_members_guard', 'orgs_modules_guard', 'manage_agent', 'stored', 'wanted_snapshot', 'require_justice'];
  api text[] := array['recruit_agent', 'save_agent', 'promote_agent', 'set_agent_units', 'set_qualification', 'set_stage', 'career_note', 'agent_leave', 'agent_return',
    'blacklist_add', 'blacklist_remove', 'save_unit', 'save_qualification', 'set_grade_meta',
    'mdt_duty', 'set_duty_status', 'end_duty', 'declare_absence', 'decide_absence', 'save_agenda', 'delete_agenda', 'agenda_join',
    'save_person', 'open_case', 'update_case', 'case_note', 'set_case_agents', 'attach_case_file',
    'save_warrant', 'send_warrant', 'decide_warrant', 'execute_warrant', 'cancel_warrant', 'justice_warrants',
    'set_wanted', 'remove_wanted', 'publish_wanted', 'set_wanted_size', 'handle_tip',
    'post_announcement', 'delete_announcement', 'read_announcement',
    'create_service', 'create_claim', 'platform_access', 'revoke_platform',
    'member_of_path', 'cap_of_path'];
  visitors text[] := array['wanted_public', 'send_tip'];
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
  for f in select p.oid::regprocedure from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = any (visitors) loop
    execute format('revoke execute on function %s from public', f);
    execute format('grant execute on function %s to anon, authenticated', f);
  end loop;
end $$;
