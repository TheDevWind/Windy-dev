-- Windy Desk · services publics, étape 3 (US Marshals) : transferts de détenus (POD), tribunal et protection judiciaire (JSD),
-- WITSEC (accès ultra-restreint, noms masqués, journal d'audit), SOG (opérateurs, activation, briefing), renseignement
-- (informateurs, paiements, notes classifiées) et alertes internes.

-- ---------------------------------------------------------------- modules et accès
insert into public.modules (id, name, price, description, sort, sector) values
  ('transferts', 'Transferts de détenus', 200, 'Ordres de transfert, convoi, itinéraires réservés au convoi, suivi en direct et registre des détenus.', 165, 'service_public'),
  ('tribunal', 'Tribunal et protection', 200, 'Calendrier des audiences, plan de sécurité, magistrats protégés et registre des menaces.', 170, 'service_public'),
  ('witsec', 'WITSEC', 250, 'Témoins protégés sous code, nom révélé seulement par action volontaire et journalisée, planques, escortes, alertes au Director.', 175, 'service_public'),
  ('sog', 'SOG', 200, 'Opérateurs certifiés, activation et alerte, limite de sorties, briefing, retour d’expérience, tests et recertifications.', 180, 'service_public'),
  ('renseignement', 'Renseignement', 200, 'Informateurs sous nom de code, fiabilité, paiements, notes classifiées et graphe des relations.', 185, 'service_public');
update public.orgs set modules = modules || array['transferts', 'tribunal', 'witsec', 'sog', 'renseignement'] where sector = 'service_public' and not ('transferts' = any (modules));

create or replace function public.all_caps() returns text[]
language sql immutable set search_path = '' as $$
  select array['encaisser', 'stock', 'fabriquer', 'compta', 'declarer', 'paie', 'equipe', 'vehicules', 'evenements',
    'effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces', 'affaires', 'transferts', 'tribunal', 'witsec', 'sog', 'renseignement']
$$;
-- les grades du préréglage des services existants reçoivent les nouveaux accès
update public.grades g set caps = array(select distinct c from unnest(g.caps || x.add) c)
  from (values ('Chief Deputy', array['transferts', 'tribunal', 'sog', 'renseignement']), ('Supervisory', array['transferts', 'tribunal', 'renseignement']), ('Inspector', array['renseignement'])) x(name, add)
  where g.name = x.name and g.org_id in (select id from public.orgs where sector = 'service_public');

-- WITSEC : l'unité WITSEC, le Director (grade le plus haut) et ceux qui ont reçu l'accès « witsec »
create or replace function public.witsec_access(o uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.has_cap(o, 'witsec') or exists (select 1 from public.member_units mu join public.units u on u.id = mu.unit_id join public.members m on m.id = mu.member_id
    where m.org_id = o and m.user_id = auth.uid() and m.active and u.code = 'WITSEC')
$$;

-- ---------------------------------------------------------------- alertes internes (tableau de bord ; Discord à l'étape 5)
create table public.alerts (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  at timestamptz not null default now(),
  kind text not null check (kind in ('witsec', 'sog', 'transfert', 'menace', 'autre')),
  text text not null check (length(text) between 3 and 300),
  link text check (length(link) <= 200),
  read_at timestamptz
);
create index alerts_member on public.alerts (member_id, at desc);
create index alerts_org on public.alerts (org_id);

create or replace function public.alert_members(o uuid, ids uuid[], k text, t text, l text) returns void
language sql security definer set search_path = '' as $$
  insert into public.alerts (org_id, member_id, kind, text, link) select o, x, k, left(t, 300), l from unnest(ids) x
$$;
-- les membres du grade le plus haut (le Director)
create or replace function public.top_members(o uuid) returns uuid[]
language sql stable security definer set search_path = '' as $$
  select coalesce(array_agg(m.id), '{}') from public.members m join public.grades g on g.id = m.grade_id
  where m.org_id = o and m.active and g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o)
$$;

create or replace function public.read_alerts(p_org uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.caller_member(p_org);
begin
  if me.id is null then raise exception 'Accès refusé.'; end if;
  update public.alerts set read_at = now() where member_id = me.id and read_at is null;
end $$;

-- ---------------------------------------------------------------- numérotation
create or replace function public.next_num(o uuid, k text) returns text
language plpgsql security definer set search_path = '' as $$
declare n integer; p text;
begin
  update public.orgs set counters = jsonb_set(counters, array[k], to_jsonb(coalesce((counters ->> k)::integer, 0) + 1))
    where id = o returning (counters ->> k)::integer, id_prefix into n, p;
  return p || case k when 'case' then '-IOD-' when 'warrant' then '-M-' when 'ia' then '-AI-' when 'evidence' then '-P-'
    when 'r_arrestation' then '-A-' when 'r_sog' then '-SOG-' when 'r_transfert' then '-T-' when 'r_incident' then '-INC-'
    when 'r_force' then '-UF-' when 'r_audience' then '-AUD-' when 'r_libre' then '-R-' when 'transfer' then '-TR-' when 'sog_op' then '-OPS-' else '-' end
    || lpad(n::text, case when k in ('r_sog', 'sog_op') then 3 else 4 end, '0');
end $$;

-- ---------------------------------------------------------------- transferts de détenus et registre des détenus
create table public.transfers (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  person_id uuid not null references public.people (id) on delete restrict,
  from_place text not null check (length(from_place) between 2 and 120),
  to_place text not null check (length(to_place) between 2 and 120),
  depart_at timestamptz not null,
  risk text not null default 'moyen' check (risk in ('faible', 'moyen', 'eleve', 'extreme')),
  leader_id uuid references public.members (id) on delete set null,
  vehicles text check (length(vehicles) <= 200),
  sog boolean not null default false,
  case_id uuid references public.cases (id) on delete set null,
  status text not null default 'prevu' check (status in ('prevu', 'en_route', 'arrive', 'incident', 'annule')),
  incident text check (length(incident) <= 500),
  started_at timestamptz,
  arrived_at timestamptz,
  created_at timestamptz not null default now(),
  by_name text not null,
  unique (org_id, num)
);
create index transfers_org on public.transfers (org_id, depart_at desc);
create index transfers_person on public.transfers (person_id);
create index transfers_leader on public.transfers (leader_id);
create index transfers_case on public.transfers (case_id);
create table public.transfer_agents (
  transfer_id uuid not null references public.transfers (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  primary key (transfer_id, member_id)
);
create index transfer_agents_member on public.transfer_agents (member_id);
create index transfer_agents_org on public.transfer_agents (org_id);
-- les itinéraires : lus seulement par le convoi et ceux qui planifient les transferts
create table public.transfer_routes (
  transfer_id uuid primary key references public.transfers (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  main text check (length(main) <= 600),
  backup text check (length(backup) <= 600)
);
create index transfer_routes_org on public.transfer_routes (org_id);
create table public.detentions (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  person_id uuid not null references public.people (id) on delete cascade,
  place text not null check (length(place) between 2 and 120),
  since timestamptz not null default now(),
  until timestamptz,
  reason text check (length(reason) <= 200),
  case_id uuid references public.cases (id) on delete set null,
  released_at timestamptz,
  release_note text check (length(release_note) <= 200),
  by_name text not null
);
create index detentions_org on public.detentions (org_id, since desc);
create index detentions_person on public.detentions (person_id);
create index detentions_case on public.detentions (case_id);
create unique index detentions_one_open on public.detentions (person_id) where released_at is null;

create or replace function public.in_convoy(t uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.transfers x join public.members m on m.org_id = x.org_id and m.user_id = auth.uid() and m.active
    where x.id = t and (x.leader_id = m.id or exists (select 1 from public.transfer_agents a where a.transfer_id = x.id and a.member_id = m.id)))
$$;

create or replace function public.save_transfer(p_org uuid, p_id uuid, p jsonb, p_agents uuid[]) returns json
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'transferts', 'transferts'); tid uuid; n text; v_person uuid := nullif(p ->> 'person_id', '')::uuid;
  v_leader uuid := nullif(p ->> 'leader_id', '')::uuid; v_case uuid := nullif(p ->> 'case_id', '')::uuid; t public.transfers;
begin
  if v_person is null or not exists (select 1 from public.people where id = v_person and org_id = p_org) then raise exception 'Choisis le détenu.'; end if;
  if v_leader is not null and not exists (select 1 from public.members where id = v_leader and org_id = p_org and active) then raise exception 'Chef de convoi inconnu.'; end if;
  if v_case is not null and not exists (select 1 from public.cases where id = v_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  if exists (select 1 from unnest(coalesce(p_agents, '{}')) x where not exists (select 1 from public.members m where m.id = x and m.org_id = p_org and m.active)) then raise exception 'Agent inconnu.'; end if;
  if length(trim(coalesce(p ->> 'from_place', ''))) < 2 or length(trim(coalesce(p ->> 'to_place', ''))) < 2 then raise exception 'Indique le départ et l’arrivée.'; end if;
  if p_id is null then
    n := public.next_num(p_org, 'transfer');
    insert into public.transfers (org_id, num, person_id, from_place, to_place, depart_at, by_name)
      values (p_org, n, v_person, trim(p ->> 'from_place'), trim(p ->> 'to_place'), coalesce(nullif(p ->> 'depart_at', '')::timestamptz, now()), me.name) returning id into tid;
  else
    select * into t from public.transfers where id = p_id and org_id = p_org;
    if t.id is null then raise exception 'Transfert inconnu.'; end if;
    if t.status not in ('prevu', 'en_route') then raise exception 'Ce transfert est terminé.'; end if;
    tid := t.id; n := t.num;
  end if;
  update public.transfers set person_id = v_person, from_place = trim(p ->> 'from_place'), to_place = trim(p ->> 'to_place'),
    depart_at = coalesce(nullif(p ->> 'depart_at', '')::timestamptz, depart_at), risk = coalesce(nullif(p ->> 'risk', ''), risk), leader_id = v_leader,
    vehicles = nullif(trim(coalesce(p ->> 'vehicles', '')), ''), sog = coalesce((p ->> 'sog')::boolean, false), case_id = v_case where id = tid;
  insert into public.transfer_routes (transfer_id, org_id, main, backup) values (tid, p_org, nullif(trim(coalesce(p ->> 'route_main', '')), ''), nullif(trim(coalesce(p ->> 'route_backup', '')), ''))
    on conflict (transfer_id) do update set main = excluded.main, backup = excluded.backup;
  delete from public.transfer_agents where transfer_id = tid and not member_id = any (coalesce(p_agents, '{}'));
  insert into public.transfer_agents (transfer_id, member_id, org_id) select tid, x, p_org from unnest(coalesce(p_agents, '{}')) x on conflict do nothing;
  if p_id is null then
    perform public.alert_members(p_org, array(select distinct x from unnest(coalesce(p_agents, '{}') || v_leader) x where x is not null and x <> me.id), 'transfert',
      'Tu es affecté au transfert ' || n || ' (' || trim(p ->> 'from_place') || ' → ' || trim(p ->> 'to_place') || ')', '/transferts');
  end if;
  return json_build_object('id', tid, 'num', n);
exception
  when check_violation then raise exception 'Une valeur est refusée : niveau de risque, longueur des champs.';
  when invalid_datetime_format or invalid_text_representation then raise exception 'Date de départ invalide.';
end $$;

-- suivi du convoi : prévu → en route → arrivé ou incident ; à l'arrivée, le registre des détenus suit
create or replace function public.transfer_status(p_id uuid, p_status text, p_note text, p_escape boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare t public.transfers; me public.members; note text := nullif(trim(coalesce(p_note, '')), ''); ia text; d public.detentions;
begin
  select * into t from public.transfers where id = p_id for update;
  if t.id is null then raise exception 'Transfert inconnu.'; end if;
  me := public.need(t.org_id, 'transferts', null);
  if not (public.has_cap(t.org_id, 'transferts') or public.in_convoy(t.id)) then raise exception 'Seuls le convoi et les responsables suivent ce transfert.'; end if;
  if p_status not in ('en_route', 'arrive', 'incident', 'annule') then raise exception 'Statut inconnu.'; end if;
  if t.status in ('arrive', 'annule') then raise exception 'Ce transfert est terminé.'; end if;
  if p_status = 'en_route' and t.status <> 'prevu' then raise exception 'Le convoi est déjà parti.'; end if;
  if p_status = 'incident' and length(coalesce(note, '')) < 5 then raise exception 'Décris l’incident.'; end if;
  update public.transfers set status = p_status, incident = case when p_status = 'incident' then left(note, 500) else incident end,
    started_at = case when p_status = 'en_route' then now() else started_at end, arrived_at = case when p_status = 'arrive' then now() else arrived_at end where id = t.id;
  if p_status = 'arrive' then
    select * into d from public.detentions where person_id = t.person_id and released_at is null;
    if d.id is null then
      insert into public.detentions (org_id, person_id, place, reason, case_id, by_name) values (t.org_id, t.person_id, t.to_place, 'Transfert ' || t.num, t.case_id, me.name);
    else
      update public.detentions set place = t.to_place where id = d.id;
    end if;
  end if;
  if p_status = 'incident' and coalesce(p_escape, false) then
    ia := public.next_num(t.org_id, 'ia');
    insert into public.ia_cases (org_id, num, member_id, reason, opened_by) values (t.org_id, ia, t.leader_id, left('Évasion pendant le transfert ' || t.num || ' : ' || note, 300), 'Ouverture automatique');
    insert into public.journal (org_id, who, what) values (t.org_id, 'Affaires internes', 'Dossier ' || ia || ' ouvert automatiquement (évasion, transfert ' || t.num || ')');
    perform public.alert_members(t.org_id, public.top_members(t.org_id), 'transfert', 'Évasion pendant le transfert ' || t.num, '/transferts');
  end if;
  insert into public.journal (org_id, who, what) values (t.org_id, me.name, 'Transfert ' || t.num || ' : ' || case p_status when 'en_route' then 'en route' when 'arrive' then 'arrivé' when 'incident' then 'incident' else 'annulé' end);
end $$;

create or replace function public.save_detention(p_org uuid, p_person uuid, p_place text, p_until timestamptz, p_reason text, p_case uuid) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'transferts', 'transferts'); did uuid;
begin
  if not exists (select 1 from public.people where id = p_person and org_id = p_org) then raise exception 'Individu inconnu.'; end if;
  if length(trim(coalesce(p_place, ''))) < 2 then raise exception 'Indique le lieu de détention.'; end if;
  insert into public.detentions (org_id, person_id, place, until, reason, case_id, by_name)
    values (p_org, p_person, trim(p_place), p_until, nullif(trim(coalesce(p_reason, '')), ''), p_case, me.name) returning id into did;
  return did;
exception when unique_violation then raise exception 'Cet individu est déjà inscrit comme détenu.';
end $$;

create or replace function public.release_detention(p_id uuid, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare d public.detentions; me public.members;
begin
  select * into d from public.detentions where id = p_id for update;
  if d.id is null then raise exception 'Détention inconnue.'; end if;
  me := public.need(d.org_id, 'transferts', 'transferts');
  if d.released_at is not null then raise exception 'Déjà libéré.'; end if;
  update public.detentions set released_at = now(), release_note = left(nullif(trim(coalesce(p_note, '')), ''), 200) where id = d.id;
end $$;

-- ---------------------------------------------------------------- tribunal et protection judiciaire
create table public.hearings (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  starts_at timestamptz not null,
  room text not null check (length(room) between 1 and 60),
  person_id uuid references public.people (id) on delete set null,
  judge text check (length(judge) <= 80),
  case_id uuid references public.cases (id) on delete set null,
  risk text not null default 'moyen' check (risk in ('faible', 'moyen', 'eleve', 'extreme')),
  notes text check (length(notes) <= 1000),
  status text not null default 'prevue' check (status in ('prevue', 'terminee', 'reportee', 'annulee')),
  created_at timestamptz not null default now(),
  by_name text not null
);
create index hearings_org on public.hearings (org_id, starts_at);
create index hearings_person on public.hearings (person_id);
create index hearings_case on public.hearings (case_id);
create table public.hearing_posts (
  id bigint generated always as identity primary key,
  hearing_id uuid not null references public.hearings (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  post text not null check (length(post) between 2 and 60),
  member_id uuid references public.members (id) on delete set null
);
create index hearing_posts_hearing on public.hearing_posts (hearing_id);
create index hearing_posts_member on public.hearing_posts (member_id);
create index hearing_posts_org on public.hearing_posts (org_id);
create table public.protectees (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 2 and 80),
  role text not null default 'juge' check (role in ('juge', 'procureur', 'avocat', 'greffier', 'temoin', 'autre')),
  threat text not null default 'faible' check (threat in ('faible', 'moyen', 'eleve', 'extreme')),
  notes text check (length(notes) <= 1000),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  by_name text not null
);
create index protectees_org on public.protectees (org_id);
create table public.threats (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  protectee_id uuid not null references public.protectees (id) on delete cascade,
  at timestamptz not null default now(),
  person_id uuid references public.people (id) on delete set null,
  author text check (length(author) <= 80),
  description text not null check (length(description) between 5 and 1000),
  level text not null default 'moyen' check (level in ('faible', 'moyen', 'eleve', 'extreme')),
  status text not null default 'ouverte' check (status in ('ouverte', 'traitee', 'classee')),
  by_name text not null
);
create index threats_org on public.threats (org_id, at desc);
create index threats_protectee on public.threats (protectee_id);
create index threats_person on public.threats (person_id);

create or replace function public.save_hearing(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'tribunal', 'tribunal'); hid uuid; v_person uuid := nullif(p ->> 'person_id', '')::uuid; v_case uuid := nullif(p ->> 'case_id', '')::uuid;
begin
  if v_person is not null and not exists (select 1 from public.people where id = v_person and org_id = p_org) then raise exception 'Accusé inconnu.'; end if;
  if v_case is not null and not exists (select 1 from public.cases where id = v_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  if length(trim(coalesce(p ->> 'room', ''))) < 1 then raise exception 'Indique la salle.'; end if;
  if p_id is null then
    insert into public.hearings (org_id, starts_at, room, by_name) values (p_org, coalesce(nullif(p ->> 'starts_at', '')::timestamptz, now()), trim(p ->> 'room'), me.name) returning id into hid;
  else
    select id into hid from public.hearings where id = p_id and org_id = p_org;
    if hid is null then raise exception 'Audience inconnue.'; end if;
  end if;
  update public.hearings set starts_at = coalesce(nullif(p ->> 'starts_at', '')::timestamptz, starts_at), room = trim(p ->> 'room'), person_id = v_person,
    judge = nullif(trim(coalesce(p ->> 'judge', '')), ''), case_id = v_case, risk = coalesce(nullif(p ->> 'risk', ''), risk), notes = nullif(trim(coalesce(p ->> 'notes', '')), '') where id = hid;
  return hid;
exception
  when check_violation then raise exception 'Une valeur est refusée : niveau de risque, longueur des champs.';
  when invalid_datetime_format or invalid_text_representation then raise exception 'Date de l’audience invalide.';
end $$;

-- le plan de sécurité : postes et agents ; chaque agent affecté est alerté
create or replace function public.set_hearing_posts(p_id uuid, p_posts jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare hr public.hearings; me public.members; ids uuid[];
begin
  select * into hr from public.hearings where id = p_id;
  if hr.id is null then raise exception 'Audience inconnue.'; end if;
  me := public.need(hr.org_id, 'tribunal', 'tribunal');
  if jsonb_typeof(coalesce(p_posts, '[]')) <> 'array' or jsonb_array_length(coalesce(p_posts, '[]')) > 30 then raise exception 'Plan de sécurité invalide.'; end if;
  if exists (select 1 from jsonb_array_elements(coalesce(p_posts, '[]')) e where nullif(e ->> 'member_id', '') is not null
      and not exists (select 1 from public.members m where m.id = (e ->> 'member_id')::uuid and m.org_id = hr.org_id and m.active)) then raise exception 'Agent inconnu.'; end if;
  delete from public.hearing_posts where hearing_id = hr.id;
  insert into public.hearing_posts (hearing_id, org_id, post, member_id)
    select hr.id, hr.org_id, trim(e ->> 'post'), nullif(e ->> 'member_id', '')::uuid from jsonb_array_elements(coalesce(p_posts, '[]')) e where length(trim(coalesce(e ->> 'post', ''))) >= 2;
  ids := array(select distinct member_id from public.hearing_posts where hearing_id = hr.id and member_id is not null and member_id <> me.id);
  perform public.alert_members(hr.org_id, ids, 'autre', 'Affecté à la sécurité de l’audience du ' || to_char(hr.starts_at at time zone 'Europe/Paris', 'DD/MM à HH24:MI') || ' (salle ' || hr.room || ')', '/tribunal');
end $$;

create or replace function public.hearing_status(p_id uuid, p_status text) returns void
language plpgsql security definer set search_path = '' as $$
declare hr public.hearings;
begin
  select * into hr from public.hearings where id = p_id;
  if hr.id is null then raise exception 'Audience inconnue.'; end if;
  perform public.need(hr.org_id, 'tribunal', 'tribunal');
  if p_status not in ('prevue', 'terminee', 'reportee', 'annulee') then raise exception 'Statut inconnu.'; end if;
  update public.hearings set status = p_status where id = hr.id;
end $$;

create or replace function public.save_protectee(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'tribunal', 'tribunal'); pid uuid;
begin
  if length(trim(coalesce(p ->> 'name', ''))) < 2 then raise exception 'Indique le nom.'; end if;
  if p_id is null then
    insert into public.protectees (org_id, name, by_name) values (p_org, trim(p ->> 'name'), me.name) returning id into pid;
  else
    select id into pid from public.protectees where id = p_id and org_id = p_org;
    if pid is null then raise exception 'Personne protégée inconnue.'; end if;
  end if;
  update public.protectees set name = trim(p ->> 'name'), role = coalesce(nullif(p ->> 'role', ''), role), threat = coalesce(nullif(p ->> 'threat', ''), threat),
    notes = nullif(trim(coalesce(p ->> 'notes', '')), ''), active = coalesce((p ->> 'active')::boolean, active) where id = pid;
  return pid;
exception when check_violation then raise exception 'Une valeur est refusée : fonction, niveau de menace, longueur des champs.';
end $$;

-- une menace peut être signalée par tout agent ; elle remonte le niveau de la personne visée et alerte les responsables
create or replace function public.add_threat(p_org uuid, p jsonb) returns bigint
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'tribunal', null); pr public.protectees; tid bigint; v_level text := coalesce(nullif(p ->> 'level', ''), 'moyen');
  v_person uuid := nullif(p ->> 'person_id', '')::uuid;
begin
  select * into pr from public.protectees where id = nullif(p ->> 'protectee_id', '')::uuid and org_id = p_org;
  if pr.id is null then raise exception 'Choisis la personne visée.'; end if;
  if v_person is not null and not exists (select 1 from public.people where id = v_person and org_id = p_org) then raise exception 'Individu inconnu.'; end if;
  if length(trim(coalesce(p ->> 'description', ''))) < 5 then raise exception 'Décris la menace.'; end if;
  insert into public.threats (org_id, protectee_id, person_id, author, description, level, by_name)
    values (p_org, pr.id, v_person, nullif(trim(coalesce(p ->> 'author', '')), ''), trim(p ->> 'description'), v_level, me.name) returning id into tid;
  if array_position(array['faible', 'moyen', 'eleve', 'extreme'], v_level) > array_position(array['faible', 'moyen', 'eleve', 'extreme'], pr.threat) then
    update public.protectees set threat = v_level where id = pr.id;
  end if;
  perform public.alert_members(p_org, array(select m.id from public.members m join public.grades g on g.id = m.grade_id
      where m.org_id = p_org and m.active and m.id <> me.id and ('tribunal' = any (g.caps) or g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = p_org))),
    'menace', 'Menace contre ' || pr.name || ' : ' || left(trim(p ->> 'description'), 120), '/tribunal');
  return tid;
exception when check_violation then raise exception 'Une valeur est refusée : niveau de menace, longueur de la description.';
end $$;

create or replace function public.threat_status(p_id bigint, p_status text) returns void
language plpgsql security definer set search_path = '' as $$
declare t public.threats;
begin
  select * into t from public.threats where id = p_id;
  if t.id is null then raise exception 'Menace inconnue.'; end if;
  perform public.need(t.org_id, 'tribunal', 'tribunal');
  if p_status not in ('ouverte', 'traitee', 'classee') then raise exception 'Statut inconnu.'; end if;
  update public.threats set status = p_status where id = t.id;
end $$;

-- ---------------------------------------------------------------- WITSEC
create table public.safehouses (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  code text not null check (code ~ '^[A-Z0-9][A-Z0-9-]{1,19}$'),
  location_code text not null check (length(location_code) between 2 and 120),
  capacity integer not null default 1 check (capacity between 1 and 20),
  available boolean not null default true,
  notes text check (length(notes) <= 500),
  unique (org_id, code)
);
create table public.witnesses (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  code text not null,
  threat text not null default 'eleve' check (threat in ('faible', 'moyen', 'eleve', 'extreme')),
  case_id uuid references public.cases (id) on delete set null,
  against text check (length(against) <= 300),
  safehouse_id uuid references public.safehouses (id) on delete set null,
  identity_status text not null default 'a_faire' check (identity_status in ('a_faire', 'en_cours', 'faite')),
  identity_note text check (length(identity_note) <= 500),
  status text not null default 'actif' check (status in ('actif', 'termine', 'perdu')),
  notes text check (length(notes) <= 2000),
  created_at timestamptz not null default now(),
  by_name text not null,
  unique (org_id, code)
);
create index witnesses_case on public.witnesses (case_id);
create index witnesses_safehouse on public.witnesses (safehouse_id);
-- le vrai nom : aucune règle de lecture, il ne sort que par reveal_witness (journalisé)
create table public.witness_secrets (
  witness_id uuid primary key references public.witnesses (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  real_name text not null check (length(real_name) between 2 and 80),
  new_identity text check (length(new_identity) <= 80)
);
create index witness_secrets_org on public.witness_secrets (org_id);
create table public.witsec_events (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  witness_id uuid not null references public.witnesses (id) on delete cascade,
  kind text not null check (kind in ('temoignage', 'escorte', 'transfert', 'autre')),
  starts_at timestamptz not null,
  place text check (length(place) <= 120),
  notes text check (length(notes) <= 300),
  by_name text not null
);
create index witsec_events_witness on public.witsec_events (witness_id, starts_at);
create index witsec_events_org on public.witsec_events (org_id);
create table public.witsec_audit (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  witness_id uuid references public.witnesses (id) on delete set null,
  at timestamptz not null default now(),
  member_id uuid references public.members (id) on delete set null,
  by_name text not null,
  action text not null check (action in ('consultation', 'revelation', 'creation', 'modification')),
  note text check (length(note) <= 200)
);
create index witsec_audit_org on public.witsec_audit (org_id, at desc);
create index witsec_audit_witness on public.witsec_audit (witness_id);
create index witsec_audit_member on public.witsec_audit (member_id);

create or replace function public.witsec_me(o uuid) returns public.members
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(o, 'witsec', null);
begin
  if not public.witsec_access(o) then raise exception 'WITSEC : accès réservé à l’unité WITSEC et au Director.'; end if;
  return me;
end $$;

create or replace function public.witness_code(n integer) returns text
language sql immutable set search_path = '' as $$
  select 'WS-' || (array['ALPHA', 'BRAVO', 'CHARLIE', 'DELTA', 'ECHO', 'FOXTROT', 'GOLF', 'HOTEL', 'INDIA', 'JULIET', 'KILO', 'LIMA', 'MIKE', 'NOVEMBER',
    'OSCAR', 'PAPA', 'QUEBEC', 'ROMEO', 'SIERRA', 'TANGO', 'UNIFORM', 'VICTOR', 'WHISKEY', 'XRAY', 'YANKEE', 'ZULU'])[((n - 1) % 26) + 1] || '-' || lpad((((n - 1) / 26) + 1)::text, 2, '0')
$$;

create or replace function public.save_witness(p_org uuid, p_id uuid, p jsonb) returns json
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.witsec_me(p_org); wid uuid; v_code text; n integer; v_case uuid := nullif(p ->> 'case_id', '')::uuid; v_sh uuid := nullif(p ->> 'safehouse_id', '')::uuid;
begin
  if v_case is not null and not exists (select 1 from public.cases where id = v_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  if v_sh is not null and not exists (select 1 from public.safehouses where id = v_sh and org_id = p_org) then raise exception 'Planque inconnue.'; end if;
  if p_id is null then
    if length(trim(coalesce(p ->> 'real_name', ''))) < 2 then raise exception 'Indique le vrai nom du témoin (il restera masqué).'; end if;
    update public.orgs set counters = jsonb_set(counters, '{witness}', to_jsonb(coalesce((counters ->> 'witness')::integer, 0) + 1)) where id = p_org returning (counters ->> 'witness')::integer into n;
    v_code := public.witness_code(n);
    insert into public.witnesses (org_id, code, by_name) values (p_org, v_code, me.name) returning id into wid;
    insert into public.witness_secrets (witness_id, org_id, real_name) values (wid, p_org, trim(p ->> 'real_name'));
    insert into public.witsec_audit (org_id, witness_id, member_id, by_name, action) values (p_org, wid, me.id, me.name, 'creation');
  else
    select w.id, w.code into wid, v_code from public.witnesses w where w.id = p_id and w.org_id = p_org;
    if wid is null then raise exception 'Témoin inconnu.'; end if;
    insert into public.witsec_audit (org_id, witness_id, member_id, by_name, action) values (p_org, wid, me.id, me.name, 'modification');
  end if;
  update public.witnesses set threat = coalesce(nullif(p ->> 'threat', ''), threat), case_id = v_case, against = nullif(trim(coalesce(p ->> 'against', '')), ''), safehouse_id = v_sh,
    identity_status = coalesce(nullif(p ->> 'identity_status', ''), identity_status), identity_note = nullif(trim(coalesce(p ->> 'identity_note', '')), ''),
    status = coalesce(nullif(p ->> 'status', ''), status), notes = nullif(trim(coalesce(p ->> 'notes', '')), '') where id = wid;
  if nullif(trim(coalesce(p ->> 'new_identity', '')), '') is not null then update public.witness_secrets set new_identity = trim(p ->> 'new_identity') where witness_id = wid; end if;
  if coalesce(p ->> 'status', '') = 'perdu' then
    perform public.alert_members(p_org, public.top_members(p_org), 'witsec', 'Témoin ' || v_code || ' déclaré perdu', '/witsec');
  end if;
  return json_build_object('id', wid, 'code', v_code);
exception when check_violation then raise exception 'Une valeur est refusée : niveau de menace, statut, longueur des champs.';
end $$;

-- ouvrir une fiche : la consultation est journalisée et le Director est alerté
create or replace function public.open_witness(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.witnesses; me public.members;
begin
  select * into w from public.witnesses where id = p_id;
  if w.id is null then raise exception 'Témoin inconnu.'; end if;
  me := public.witsec_me(w.org_id);
  insert into public.witsec_audit (org_id, witness_id, member_id, by_name, action) values (w.org_id, w.id, me.id, me.name, 'consultation');
  perform public.alert_members(w.org_id, array(select x from unnest(public.top_members(w.org_id)) x where x <> me.id), 'witsec', 'Fiche WITSEC ' || w.code || ' consultée par ' || me.name, '/witsec');
end $$;

-- révéler le vrai nom : action volontaire, motif obligatoire, journalisée, alerte au Director
create or replace function public.reveal_witness(p_id uuid, p_reason text) returns json
language plpgsql security definer set search_path = '' as $$
declare w public.witnesses; me public.members; s public.witness_secrets;
begin
  select * into w from public.witnesses where id = p_id;
  if w.id is null then raise exception 'Témoin inconnu.'; end if;
  me := public.witsec_me(w.org_id);
  if length(trim(coalesce(p_reason, ''))) < 5 then raise exception 'Indique pourquoi tu as besoin du vrai nom.'; end if;
  select * into s from public.witness_secrets where witness_id = w.id;
  insert into public.witsec_audit (org_id, witness_id, member_id, by_name, action, note) values (w.org_id, w.id, me.id, me.name, 'revelation', left(trim(p_reason), 200));
  perform public.alert_members(w.org_id, array(select x from unnest(public.top_members(w.org_id)) x where x <> me.id), 'witsec', 'Vrai nom du témoin ' || w.code || ' révélé à ' || me.name, '/witsec');
  return json_build_object('real_name', s.real_name, 'new_identity', s.new_identity);
end $$;

create or replace function public.save_safehouse(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.witsec_me(p_org); sid uuid;
begin
  if p_id is null then
    insert into public.safehouses (org_id, code, location_code) values (p_org, upper(trim(coalesce(p ->> 'code', ''))), trim(coalesce(p ->> 'location_code', ''))) returning id into sid;
  else
    select id into sid from public.safehouses where id = p_id and org_id = p_org;
    if sid is null then raise exception 'Planque inconnue.'; end if;
  end if;
  update public.safehouses set code = upper(trim(coalesce(p ->> 'code', code))), location_code = trim(coalesce(p ->> 'location_code', location_code)),
    capacity = coalesce(nullif(p ->> 'capacity', '')::integer, capacity), available = coalesce((p ->> 'available')::boolean, available), notes = nullif(trim(coalesce(p ->> 'notes', '')), '') where id = sid;
  return sid;
exception
  when unique_violation then raise exception 'Ce code de planque existe déjà.';
  when check_violation then raise exception 'Code de 2 à 20 lettres ou chiffres, localisation codée de 2 à 120 caractères, capacité de 1 à 20.';
end $$;

create or replace function public.save_witsec_event(p_org uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.witsec_me(p_org); eid uuid; v_w uuid := nullif(p ->> 'witness_id', '')::uuid;
begin
  if v_w is null or not exists (select 1 from public.witnesses where id = v_w and org_id = p_org) then raise exception 'Choisis le témoin.'; end if;
  insert into public.witsec_events (org_id, witness_id, kind, starts_at, place, notes, by_name)
    values (p_org, v_w, coalesce(nullif(p ->> 'kind', ''), 'temoignage'), coalesce(nullif(p ->> 'starts_at', '')::timestamptz, now()), nullif(trim(coalesce(p ->> 'place', '')), ''),
      nullif(trim(coalesce(p ->> 'notes', '')), ''), me.name) returning id into eid;
  return eid;
exception
  when check_violation then raise exception 'Une valeur est refusée : type, longueur des champs.';
  when invalid_datetime_format then raise exception 'Date invalide.';
end $$;

-- ---------------------------------------------------------------- SOG
create table public.sog_operators (
  member_id uuid primary key references public.members (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  specialty text not null default 'Assaut' check (length(specialty) between 2 and 40),
  available boolean not null default true,
  certified_at date not null default current_date,
  recert_due date,
  notes text check (length(notes) <= 300)
);
create index sog_operators_org on public.sog_operators (org_id);
create table public.sog_tests (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  at timestamptz not null default now(),
  kind text not null check (kind in ('selection', 'recertification')),
  passed boolean not null,
  score integer check (score between 0 and 100),
  notes text check (length(notes) <= 300),
  by_name text not null
);
create index sog_tests_member on public.sog_tests (member_id, at desc);
create index sog_tests_org on public.sog_tests (org_id);
create table public.sog_missions (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  title text not null check (length(title) between 3 and 80),
  objective text check (length(objective) <= 500),
  plan text check (length(plan) <= 3000),
  map_path text check (length(map_path) <= 300),
  roles jsonb not null default '[]' check (jsonb_typeof(roles) = 'array'),
  case_id uuid references public.cases (id) on delete set null,
  status text not null default 'activee' check (status in ('activee', 'en_cours', 'terminee', 'annulee')),
  activated_at timestamptz not null default now(),
  activated_by text not null,
  ended_at timestamptz,
  report_id uuid references public.reports (id) on delete set null,
  debrief text check (length(debrief) <= 3000),
  unique (org_id, num)
);
create index sog_missions_org on public.sog_missions (org_id, activated_at desc);
create index sog_missions_case on public.sog_missions (case_id);
create index sog_missions_report on public.sog_missions (report_id);

create or replace function public.save_operator(p_member uuid, p_specialty text, p_available boolean, p_notes text) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'sog', 'sog');
  insert into public.sog_operators (member_id, org_id, specialty, available, recert_due, notes)
    values (mem.id, mem.org_id, coalesce(nullif(trim(coalesce(p_specialty, '')), ''), 'Assaut'), coalesce(p_available, true), current_date + 60, nullif(trim(coalesce(p_notes, '')), ''))
    on conflict (member_id) do update set specialty = excluded.specialty, available = excluded.available, notes = excluded.notes;
exception when check_violation then raise exception 'Spécialité de 2 à 40 caractères.';
end $$;

create or replace function public.remove_operator(p_member uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare o public.sog_operators;
begin
  select * into o from public.sog_operators where member_id = p_member;
  if o.member_id is null then raise exception 'Cet agent n’est pas opérateur SOG.'; end if;
  perform public.need(o.org_id, 'sog', 'sog');
  delete from public.sog_operators where member_id = o.member_id;
end $$;

create or replace function public.set_sog_available(p_org uuid, p_available boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'sog', null);
begin
  update public.sog_operators set available = coalesce(p_available, true) where member_id = me.id;
  if not found then raise exception 'Tu n’es pas opérateur SOG.'; end if;
end $$;

-- test de sélection réussi : l'agent devient opérateur ; recertification réussie : 60 jours de plus
create or replace function public.add_sog_test(p_member uuid, p_kind text, p_passed boolean, p_score integer, p_notes text) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'sog', 'sog');
  if p_kind not in ('selection', 'recertification') then raise exception 'Type de test inconnu.'; end if;
  insert into public.sog_tests (org_id, member_id, kind, passed, score, notes, by_name) values (mem.org_id, mem.id, p_kind, coalesce(p_passed, false), p_score, nullif(trim(coalesce(p_notes, '')), ''), me.name);
  if coalesce(p_passed, false) then
    insert into public.sog_operators (member_id, org_id, recert_due) values (mem.id, mem.org_id, current_date + 60)
      on conflict (member_id) do update set recert_due = current_date + 60, certified_at = case when p_kind = 'selection' then current_date else sog_operators.certified_at end;
  end if;
exception when check_violation then raise exception 'Note de 0 à 100, remarque de 300 caractères au plus.';
end $$;

-- activation (Director, Chief Deputy) : alerte immédiate à tous les opérateurs ; 2 sorties par semaine au plus
create or replace function public.activate_sog(p_org uuid, p_title text, p_objective text, p_case uuid) returns json
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'sog', 'sog'); b record; used integer; mid uuid; n text;
begin
  if length(trim(coalesce(p_title, ''))) < 3 then raise exception 'Donne un nom à l’opération.'; end if;
  if p_case is not null and not exists (select 1 from public.cases where id = p_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  select * into b from public.week_bounds(public.week_of(now()));
  select count(*) into used from public.sog_missions where org_id = p_org and status <> 'annulee' and activated_at >= b.t0 and activated_at < b.t1;
  if used >= 2 then raise exception 'Limite atteinte : 2 sorties SOG cette semaine.'; end if;
  n := public.next_num(p_org, 'sog_op');
  insert into public.sog_missions (org_id, num, title, objective, case_id, activated_by) values (p_org, n, trim(p_title), nullif(trim(coalesce(p_objective, '')), ''), p_case, me.name) returning id into mid;
  perform public.alert_members(p_org, array(select o.member_id from public.sog_operators o join public.members m on m.id = o.member_id where o.org_id = p_org and m.active and o.member_id <> me.id),
    'sog', 'Activation SOG ' || n || ' : ' || trim(p_title), '/sog/' || mid);
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Activation SOG ' || n || ' : ' || trim(p_title));
  return json_build_object('id', mid, 'num', n, 'week_count', used + 1);
end $$;

create or replace function public.save_briefing(p_id uuid, p jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare m public.sog_missions; me public.members; v_map text := nullif(trim(coalesce(p ->> 'map_path', '')), '');
begin
  select * into m from public.sog_missions where id = p_id;
  if m.id is null then raise exception 'Opération inconnue.'; end if;
  me := public.need(m.org_id, 'sog', 'sog');
  if m.status in ('terminee', 'annulee') then raise exception 'Cette opération est close.'; end if;
  if v_map is not null and not public.stored('mdt', m.org_id, v_map) then raise exception 'Carte introuvable : envoie-la de nouveau.'; end if;
  if jsonb_typeof(coalesce(p -> 'roles', '[]')) <> 'array' then raise exception 'Répartition des rôles invalide.'; end if;
  update public.sog_missions set objective = nullif(trim(coalesce(p ->> 'objective', '')), ''), plan = nullif(trim(coalesce(p ->> 'plan', '')), ''),
    map_path = coalesce(v_map, map_path), roles = coalesce(p -> 'roles', '[]'), status = case when status = 'activee' then 'en_cours' else status end where id = m.id;
exception when check_violation then raise exception 'Le plan fait 3 000 caractères au plus, l’objectif 500.';
end $$;

create or replace function public.close_sog(p_id uuid, p_status text, p_debrief text, p_report uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare m public.sog_missions; me public.members;
begin
  select * into m from public.sog_missions where id = p_id for update;
  if m.id is null then raise exception 'Opération inconnue.'; end if;
  me := public.need(m.org_id, 'sog', 'sog');
  if p_status not in ('terminee', 'annulee') then raise exception 'Statut inconnu.'; end if;
  if m.status in ('terminee', 'annulee') then raise exception 'Cette opération est déjà close.'; end if;
  if p_report is not null and not exists (select 1 from public.reports where id = p_report and org_id = m.org_id) then raise exception 'Rapport inconnu.'; end if;
  if p_status = 'terminee' and length(trim(coalesce(p_debrief, ''))) < 10 then raise exception 'Écris le retour d’expérience (10 caractères au moins).'; end if;
  update public.sog_missions set status = p_status, ended_at = now(), debrief = left(nullif(trim(coalesce(p_debrief, '')), ''), 3000), report_id = coalesce(p_report, report_id) where id = m.id;
  insert into public.journal (org_id, who, what) values (m.org_id, me.name, 'Opération SOG ' || m.num || case when p_status = 'terminee' then ' terminée' else ' annulée' end);
end $$;

-- ---------------------------------------------------------------- renseignement
create table public.informants (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  code text not null check (code ~ '^[A-Z0-9][A-Z0-9-]{1,19}$'),
  real_name text check (length(real_name) <= 80),
  reliability text not null default 'C' check (reliability in ('A', 'B', 'C', 'D', 'E')),
  handler_id uuid references public.members (id) on delete set null,
  gang_id uuid references public.gangs (id) on delete set null,
  notes text check (length(notes) <= 1000),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  by_name text not null,
  unique (org_id, code)
);
create index informants_handler on public.informants (handler_id);
create index informants_gang on public.informants (gang_id);
create table public.informant_payments (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  informant_id uuid not null references public.informants (id) on delete cascade,
  at timestamptz not null default now(),
  amount numeric(12, 2) not null check (amount > 0),
  reason text check (length(reason) <= 200),
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index informant_payments_informant on public.informant_payments (informant_id, at desc);
create index informant_payments_org on public.informant_payments (org_id);
create index informant_payments_member on public.informant_payments (member_id);
create table public.intel (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  title text not null check (length(title) between 3 and 120),
  body text not null check (length(body) between 3 and 4000),
  classification text not null default 'confidentiel' check (classification in ('diffusion', 'confidentiel', 'secret', 'tres_secret')),
  reliability text check (reliability in ('A', 'B', 'C', 'D', 'E')),
  informant_id uuid references public.informants (id) on delete set null,
  gang_id uuid references public.gangs (id) on delete set null,
  person_id uuid references public.people (id) on delete set null,
  case_id uuid references public.cases (id) on delete set null,
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index intel_org on public.intel (org_id, at desc);
create index intel_informant on public.intel (informant_id);
create index intel_gang on public.intel (gang_id);
create index intel_person on public.intel (person_id);
create index intel_case on public.intel (case_id);
create index intel_member on public.intel (member_id);

create or replace function public.save_informant(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'renseignement', 'renseignement'); iid uuid; v_handler uuid := nullif(p ->> 'handler_id', '')::uuid; v_gang uuid := nullif(p ->> 'gang_id', '')::uuid;
begin
  if v_handler is not null and not exists (select 1 from public.members where id = v_handler and org_id = p_org) then raise exception 'Agent traitant inconnu.'; end if;
  if v_gang is not null and not exists (select 1 from public.gangs where id = v_gang and org_id = p_org) then raise exception 'Organisation inconnue.'; end if;
  if p_id is null then
    insert into public.informants (org_id, code, handler_id, by_name) values (p_org, upper(trim(coalesce(p ->> 'code', ''))), coalesce(v_handler, me.id), me.name) returning id into iid;
  else
    select id into iid from public.informants where id = p_id and org_id = p_org;
    if iid is null then raise exception 'Informateur inconnu.'; end if;
  end if;
  update public.informants set code = upper(trim(coalesce(p ->> 'code', code))), real_name = nullif(trim(coalesce(p ->> 'real_name', '')), ''),
    reliability = coalesce(nullif(p ->> 'reliability', ''), reliability), handler_id = coalesce(v_handler, handler_id), gang_id = v_gang,
    notes = nullif(trim(coalesce(p ->> 'notes', '')), ''), active = coalesce((p ->> 'active')::boolean, active) where id = iid;
  return iid;
exception
  when unique_violation then raise exception 'Ce nom de code existe déjà.';
  when check_violation then raise exception 'Nom de code de 2 à 20 lettres, chiffres ou tirets ; fiabilité de A à E.';
end $$;

create or replace function public.pay_informant(p_informant uuid, p_amount numeric, p_reason text) returns void
language plpgsql security definer set search_path = '' as $$
declare i public.informants; me public.members;
begin
  select * into i from public.informants where id = p_informant;
  if i.id is null then raise exception 'Informateur inconnu.'; end if;
  me := public.need(i.org_id, 'renseignement', 'renseignement');
  if coalesce(p_amount, 0) <= 0 then raise exception 'Indique le montant.'; end if;
  insert into public.informant_payments (org_id, informant_id, amount, reason, member_id, by_name) values (i.org_id, i.id, round(p_amount, 2), left(nullif(trim(coalesce(p_reason, '')), ''), 200), me.id, me.name);
  insert into public.journal (org_id, who, what) values (i.org_id, me.name, 'Paiement à l’informateur ' || i.code || ' : ' || public.fmt_money(p_amount));
end $$;

-- une note de renseignement ; « secret » et « très secret » demandent l'accès Renseignement
create or replace function public.add_intel(p_org uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'renseignement', 'dossiers'); nid uuid; v_class text := coalesce(nullif(p ->> 'classification', ''), 'confidentiel');
  v_inf uuid := nullif(p ->> 'informant_id', '')::uuid; v_gang uuid := nullif(p ->> 'gang_id', '')::uuid; v_person uuid := nullif(p ->> 'person_id', '')::uuid; v_case uuid := nullif(p ->> 'case_id', '')::uuid;
begin
  if v_class in ('secret', 'tres_secret') and not public.has_cap(p_org, 'renseignement') then raise exception 'Il faut l’accès Renseignement pour classer une note Secret ou Très secret.'; end if;
  if v_inf is not null and not exists (select 1 from public.informants where id = v_inf and org_id = p_org) then raise exception 'Informateur inconnu.'; end if;
  if v_gang is not null and not exists (select 1 from public.gangs where id = v_gang and org_id = p_org) then raise exception 'Organisation inconnue.'; end if;
  if v_person is not null and not exists (select 1 from public.people where id = v_person and org_id = p_org) then raise exception 'Individu inconnu.'; end if;
  if v_case is not null and not exists (select 1 from public.cases where id = v_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  if length(trim(coalesce(p ->> 'title', ''))) < 3 or length(trim(coalesce(p ->> 'body', ''))) < 3 then raise exception 'Donne un titre et un contenu à la note.'; end if;
  insert into public.intel (org_id, title, body, classification, reliability, informant_id, gang_id, person_id, case_id, member_id, by_name)
    values (p_org, trim(p ->> 'title'), trim(p ->> 'body'), v_class, nullif(p ->> 'reliability', ''), v_inf, v_gang, v_person, v_case, me.id, me.name) returning id into nid;
  return nid;
exception when check_violation then raise exception 'Une valeur est refusée : classification, fiabilité (A à E), longueur des champs.';
end $$;

-- ---------------------------------------------------------------- un service créé reçoit les nouveaux accès dans ses grades
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
    (oid, 'Chief Deputy', 90, array['effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces', 'equipe', 'transferts', 'tribunal', 'sog', 'renseignement'], '★★★', 1),
    (oid, 'Supervisory', 80, array['effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces', 'transferts', 'tribunal', 'renseignement'], '★★', 2),
    (oid, 'Inspector', 70, array['dossiers', 'mandats', 'wanted', 'renseignement'], '★', 4),
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

-- l'espace de chacun dit aussi s'il entre dans WITSEC (le menu n'affiche WITSEC qu'à ceux-là)
create or replace function public.my_spaces() returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object(
    'role', (select r.role from public.platform_roles r where r.user_id = auth.uid()),
    'display_name', (select r.display_name from public.platform_roles r where r.user_id = auth.uid()),
    'orgs', coalesce((
      select json_agg(json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'is_association', o.is_association, 'sector', o.sector,
        'id_prefix', o.id_prefix, 'modules', o.modules, 'licence_until', o.licence_until, 'member_id', m.id, 'member_name', m.name, 'grade', g.name,
        'signature_name', m.signature_name, 'signature_style', m.signature_style, 'witsec', o.sector = 'service_public' and public.witsec_access(o.id),
        'caps', case when g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o.id) then public.all_caps() else g.caps end) order by o.name)
      from public.members m join public.orgs o on o.id = m.org_id join public.grades g on g.id = m.grade_id
      where m.user_id = auth.uid() and m.active), '[]'::json))
$$;

-- ---------------------------------------------------------------- règles de lecture
alter table public.alerts enable row level security;
alter table public.transfers enable row level security;
alter table public.transfer_agents enable row level security;
alter table public.transfer_routes enable row level security;
alter table public.detentions enable row level security;
alter table public.hearings enable row level security;
alter table public.hearing_posts enable row level security;
alter table public.protectees enable row level security;
alter table public.threats enable row level security;
alter table public.safehouses enable row level security;
alter table public.witnesses enable row level security;
alter table public.witness_secrets enable row level security;
alter table public.witsec_events enable row level security;
alter table public.witsec_audit enable row level security;
alter table public.sog_operators enable row level security;
alter table public.sog_tests enable row level security;
alter table public.sog_missions enable row level security;
alter table public.informants enable row level security;
alter table public.informant_payments enable row level security;
alter table public.intel enable row level security;

create policy alerts_read on public.alerts for select to authenticated using (member_id = (public.caller_member(org_id)).id);
create policy transfers_read on public.transfers for select to authenticated using (public.is_member(org_id));
create policy transfer_agents_read on public.transfer_agents for select to authenticated using (public.is_member(org_id));
create policy transfer_routes_read on public.transfer_routes for select to authenticated using (public.has_cap(org_id, 'transferts') or public.in_convoy(transfer_id));
create policy detentions_read on public.detentions for select to authenticated using (public.is_member(org_id));
create policy hearings_read on public.hearings for select to authenticated using (public.is_member(org_id));
create policy hearing_posts_read on public.hearing_posts for select to authenticated using (public.is_member(org_id));
create policy protectees_read on public.protectees for select to authenticated using (public.has_cap(org_id, 'tribunal'));
create policy threats_read on public.threats for select to authenticated using (public.has_cap(org_id, 'tribunal'));
create policy safehouses_read on public.safehouses for select to authenticated using (public.witsec_access(org_id));
create policy witnesses_read on public.witnesses for select to authenticated using (public.witsec_access(org_id));
create policy witsec_events_read on public.witsec_events for select to authenticated using (public.witsec_access(org_id));
create policy witsec_audit_read on public.witsec_audit for select to authenticated using (public.has_cap(org_id, 'witsec'));
create policy sog_operators_read on public.sog_operators for select to authenticated using (public.is_member(org_id));
create policy sog_tests_read on public.sog_tests for select to authenticated using (public.has_cap(org_id, 'sog') or member_id = (public.caller_member(org_id)).id);
create policy sog_missions_read on public.sog_missions for select to authenticated using (public.is_member(org_id));
create policy informants_read on public.informants for select to authenticated using (public.has_cap(org_id, 'renseignement'));
create policy informant_payments_read on public.informant_payments for select to authenticated using (public.has_cap(org_id, 'renseignement'));
create policy intel_read on public.intel for select to authenticated using (public.is_member(org_id) and (classification = 'diffusion'
  or (classification = 'confidentiel' and (public.has_cap(org_id, 'dossiers') or public.has_cap(org_id, 'renseignement'))) or public.has_cap(org_id, 'renseignement')));
comment on table public.witness_secrets is 'Aucune règle d’accès voulue : le vrai nom ne sort que par reveal_witness, qui journalise et alerte le Director.';

-- ---------------------------------------------------------------- droits
do $$
declare
  t text;
  f regprocedure;
  tables text[] := array['alerts', 'transfers', 'transfer_agents', 'transfer_routes', 'detentions', 'hearings', 'hearing_posts', 'protectees', 'threats',
    'safehouses', 'witnesses', 'witsec_events', 'witsec_audit', 'sog_operators', 'sog_tests', 'sog_missions', 'informants', 'informant_payments', 'intel'];
  internal text[] := array['next_num', 'alert_members', 'top_members', 'witsec_me', 'witness_code'];
  api text[] := array['read_alerts', 'witsec_access', 'in_convoy', 'save_transfer', 'transfer_status', 'save_detention', 'release_detention',
    'save_hearing', 'set_hearing_posts', 'hearing_status', 'save_protectee', 'add_threat', 'threat_status',
    'save_witness', 'open_witness', 'reveal_witness', 'save_safehouse', 'save_witsec_event',
    'save_operator', 'remove_operator', 'set_sog_available', 'add_sog_test', 'activate_sog', 'save_briefing', 'close_sog',
    'save_informant', 'pay_informant', 'add_intel', 'create_service'];
begin
  foreach t in array tables loop
    execute format('revoke all on public.%I from anon, authenticated', t);
    execute format('grant select on public.%I to authenticated', t);
  end loop;
  revoke all on public.witness_secrets from anon, authenticated;
  for f in select p.oid::regprocedure from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = any (internal) loop
    execute format('revoke execute on function %s from public, anon, authenticated', f);
  end loop;
  for f in select p.oid::regprocedure from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = any (api) loop
    execute format('revoke execute on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end $$;
