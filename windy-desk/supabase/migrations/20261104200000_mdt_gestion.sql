-- Windy Desk · services publics, étape 4 (US Marshals) : académie et recrutement (candidatures en ligne, entretiens, parcours,
-- bibliothèque, examens QCM, évaluations FTO, entraînements, propositions de promotion), affaires internes (plaintes, enquêtes,
-- sanctions appliquées, casier), logistique (armurerie, équipement, parc), statistiques, carte et administration.

-- ---------------------------------------------------------------- modules et accès
insert into public.modules (id, name, price, description, sort, sector) values
  ('academie', 'Académie et recrutement', 200, 'Candidatures en ligne, entretiens, parcours des recrues, bibliothèque, examens QCM, évaluations FTO, entraînements, propositions de promotion.', 190, 'service_public'),
  ('affaires', 'Affaires internes', 150, 'Plaintes internes et publiques, enquêtes, sanctions appliquées automatiquement, casier disciplinaire, journal d’audit.', 195, 'service_public'),
  ('logistique', 'Logistique', 150, 'Armurerie avec numéros de série et attributions, munitions et équipement, parc automobile, réservations et dégâts.', 200, 'service_public'),
  ('statistiques', 'Statistiques', 100, 'Arrestations, résolution des dossiers, heures de service, rapports en retard, sorties SOG, classements, bilan de la semaine.', 205, 'service_public'),
  ('carte', 'Carte', 100, 'Carte de la ville : dernières positions connues, interpellations, territoires des gangs, planques WITSEC (accès restreint).', 210, 'service_public');
update public.orgs set modules = modules || array['academie', 'affaires', 'logistique', 'statistiques', 'carte'] where sector = 'service_public' and not ('academie' = any (modules));

create or replace function public.all_caps() returns text[]
language sql immutable set search_path = '' as $$
  select array['encaisser', 'stock', 'fabriquer', 'compta', 'declarer', 'paie', 'equipe', 'vehicules', 'evenements',
    'effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces', 'affaires', 'transferts', 'tribunal', 'witsec', 'sog', 'renseignement',
    'academie', 'logistique']
$$;
update public.grades g set caps = array(select distinct c from unnest(g.caps || x.add) c)
  from (values ('Chief Deputy', array['academie', 'logistique']), ('Supervisory', array['academie'])) x(name, add)
  where g.name = x.name and g.org_id in (select id from public.orgs where sector = 'service_public');

-- ---------------------------------------------------------------- suspension : un agent suspendu n'a plus aucun accès
alter table public.members
  add column suspended_until timestamptz,
  add column fto_id uuid references public.members (id) on delete set null;
create index members_fto on public.members (fto_id);

create or replace function public.is_member(o uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.members m where m.org_id = o and m.user_id = auth.uid() and m.active and (m.suspended_until is null or m.suspended_until <= now()))
$$;
create or replace function public.has_cap(o uuid, cap text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.members m join public.grades g on g.id = m.grade_id
    where m.org_id = o and m.user_id = auth.uid() and m.active and (m.suspended_until is null or m.suspended_until <= now())
      and (cap = any (g.caps) or g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o)))
$$;
create or replace function public.caller_member(o uuid) returns public.members
language sql stable security definer set search_path = '' as $$
  select m.* from public.members m where m.org_id = o and m.user_id = auth.uid() and m.active and (m.suspended_until is null or m.suspended_until <= now())
$$;
create or replace function public.witsec_access(o uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.has_cap(o, 'witsec') or exists (select 1 from public.member_units mu join public.units u on u.id = mu.unit_id join public.members m on m.id = mu.member_id
    where m.org_id = o and m.user_id = auth.uid() and m.active and (m.suspended_until is null or m.suspended_until <= now()) and u.code = 'WITSEC')
$$;
create or replace function public.in_convoy(t uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.transfers x join public.members m on m.org_id = x.org_id and m.user_id = auth.uid() and m.active and (m.suspended_until is null or m.suspended_until <= now())
    where x.id = t and (x.leader_id = m.id or exists (select 1 from public.transfer_agents a where a.transfer_id = x.id and a.member_id = m.id)))
$$;
-- la personne connectée a le grade le plus haut (le Director)
create or replace function public.is_top(o uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.members m join public.grades g on g.id = m.grade_id
    where m.org_id = o and m.user_id = auth.uid() and m.active and (m.suspended_until is null or m.suspended_until <= now())
      and g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o))
$$;
-- les agents en poste qui ont cet accès (le grade le plus haut a tout)
create or replace function public.cap_members(o uuid, c text) returns uuid[]
language sql stable security definer set search_path = '' as $$
  select coalesce(array_agg(m.id), '{}') from public.members m join public.grades g on g.id = m.grade_id
  where m.org_id = o and m.active and (m.suspended_until is null or m.suspended_until <= now())
    and (c = any (g.caps) or g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o))
$$;

alter table public.alerts drop constraint alerts_kind_check;
alter table public.alerts add constraint alerts_kind_check check (kind in ('witsec', 'sog', 'transfert', 'menace', 'affaires', 'academie', 'logistique', 'autre'));

-- ---------------------------------------------------------------- numérotation
create or replace function public.next_num(o uuid, k text) returns text
language plpgsql security definer set search_path = '' as $$
declare n integer; p text;
begin
  update public.orgs set counters = jsonb_set(counters, array[k], to_jsonb(coalesce((counters ->> k)::integer, 0) + 1))
    where id = o returning (counters ->> k)::integer, id_prefix into n, p;
  return p || case k when 'case' then '-IOD-' when 'warrant' then '-M-' when 'ia' then '-AI-' when 'evidence' then '-P-'
    when 'r_arrestation' then '-A-' when 'r_sog' then '-SOG-' when 'r_transfert' then '-T-' when 'r_incident' then '-INC-'
    when 'r_force' then '-UF-' when 'r_audience' then '-AUD-' when 'r_libre' then '-R-' when 'transfer' then '-TR-' when 'sog_op' then '-OPS-'
    when 'application' then '-CAND-' when 'complaint' then '-PL-' else '-' end
    || lpad(n::text, case when k in ('r_sog', 'sog_op') then 3 else 4 end, '0');
end $$;

-- ---------------------------------------------------------------- administration : ancienneté requise, logo, carte, modèles de documents
alter table public.grades add column min_days integer check (min_days between 0 and 365);
alter table public.orgs
  add column logo_path text check (length(logo_path) <= 300),
  add column map_path text check (length(map_path) <= 300),
  add column recruiting boolean not null default true,
  add column ia_member_id uuid references public.members (id) on delete set null;
create index orgs_ia_member on public.orgs (ia_member_id);

drop function public.set_grade_meta(uuid, text, integer, numeric);
create function public.set_grade_meta(p_grade uuid, p_badge text, p_seats integer, p_salary numeric, p_min_days integer) returns void
language plpgsql security definer set search_path = '' as $$
declare g public.grades; me public.members; used integer;
begin
  select * into g from public.grades where id = p_grade;
  if g.id is null then raise exception 'Grade inconnu.'; end if;
  me := public.need(g.org_id, 'effectifs', 'equipe');
  select count(*) into used from public.members where grade_id = g.id and active;
  if p_seats is not null and p_seats < used then raise exception 'Il y a déjà % agent(s) en % : mets au moins % places.', used, g.name, used; end if;
  update public.grades set badge = nullif(trim(coalesce(p_badge, '')), ''), max_seats = p_seats, salary = p_salary, min_days = p_min_days where id = g.id;
  insert into public.journal (org_id, who, what) values (g.org_id, me.name, 'Grade ' || g.name || ' : ' || coalesce(p_seats::text, 'sans limite') || ' place(s)'
    || coalesce(', ' || p_min_days || ' jour(s) d’ancienneté avant promotion', ''));
exception when check_violation then raise exception 'Insigne de 12 caractères au plus, de 1 à 500 places, salaire positif, ancienneté de 0 à 365 jours.';
end $$;

create or replace function public.save_service_settings(p_org uuid, p jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'effectifs', 'equipe'); v_logo text := nullif(trim(coalesce(p ->> 'logo_path', '')), ''); v_map text := nullif(trim(coalesce(p ->> 'map_path', '')), '');
begin
  if v_logo is not null and not public.stored('avis', p_org, v_logo) then raise exception 'Logo introuvable : envoie-le de nouveau.'; end if;
  if v_map is not null and not public.stored('mdt', p_org, v_map) then raise exception 'Carte introuvable : envoie-la de nouveau.'; end if;
  update public.orgs set logo_path = case when p ? 'logo_path' then v_logo else logo_path end, map_path = case when p ? 'map_path' then v_map else map_path end,
    recruiting = coalesce((p ->> 'recruiting')::boolean, recruiting) where id = p_org;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Réglages du service : ' || concat_ws(', ', case when p ? 'logo_path' then 'logo' end,
    case when p ? 'map_path' then 'carte' end, case when p ? 'recruiting' then case when (p ->> 'recruiting')::boolean then 'recrutement ouvert' else 'recrutement fermé' end end));
end $$;

create table public.doc_templates (
  org_id uuid not null references public.orgs (id) on delete cascade,
  kind text not null check (kind in ('candidature', 'promotion', 'r_arrestation', 'r_sog', 'r_transfert', 'r_incident', 'r_force', 'r_audience', 'r_libre')),
  body jsonb not null check (jsonb_typeof(body) = 'object'),
  updated_at timestamptz not null default now(),
  by_name text not null,
  primary key (org_id, kind)
);
create or replace function public.default_questions() returns jsonb
language sql immutable set search_path = '' as $$
  select '[{"q": "Âge et origine de ton personnage", "long": false}, {"q": "Ton expérience (police, armée, sécurité, autre service)", "long": true},
    {"q": "Pourquoi veux-tu rejoindre les US Marshals ?", "long": true}, {"q": "Tes disponibilités (jours et heures)", "long": false},
    {"q": "As-tu lu le règlement du service et du serveur ?", "long": false}, {"q": "Ton âge réel (hors RP)", "long": false}]'::jsonb
$$;
create or replace function public.save_template(p_org uuid, p_kind text, p_body jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'effectifs', 'equipe'); v jsonb;
begin
  if p_kind = 'candidature' then
    if jsonb_typeof(p_body -> 'questions') is distinct from 'array' or jsonb_array_length(p_body -> 'questions') not between 1 and 20 then raise exception 'Le formulaire compte de 1 à 20 questions.'; end if;
    if exists (select 1 from jsonb_array_elements(p_body -> 'questions') e where jsonb_typeof(e) <> 'object' or length(trim(coalesce(e ->> 'q', ''))) not between 3 and 200) then
      raise exception 'Chaque question fait de 3 à 200 caractères.';
    end if;
    v := jsonb_build_object('questions', (select jsonb_agg(jsonb_build_object('q', trim(e.value ->> 'q'), 'long', coalesce(e.value ->> 'long', '') = 'true') order by e.n)
      from jsonb_array_elements(p_body -> 'questions') with ordinality e(value, n)));
  elsif p_kind in ('promotion', 'r_arrestation', 'r_sog', 'r_transfert', 'r_incident', 'r_force', 'r_audience', 'r_libre') then
    if length(coalesce(p_body ->> 'text', '')) > 4000 then raise exception 'Un modèle fait 4 000 caractères au plus.'; end if;
    v := jsonb_build_object('text', coalesce(p_body ->> 'text', ''));
  else
    raise exception 'Modèle inconnu.';
  end if;
  insert into public.doc_templates (org_id, kind, body, by_name) values (p_org, p_kind, v, me.name)
    on conflict (org_id, kind) do update set body = excluded.body, updated_at = now(), by_name = excluded.by_name;
end $$;

-- ---------------------------------------------------------------- académie : candidatures
create table public.applications (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  name text not null check (length(name) between 2 and 60),
  discord_id text check (discord_id ~ '^[0-9]{5,25}$'),
  contact text check (length(contact) <= 80),
  answers jsonb not null default '[]' check (jsonb_typeof(answers) = 'array'),
  status text not null default 'recue' check (status in ('recue', 'entretien', 'acceptee', 'refusee')),
  interview_at timestamptz,
  interviewer_id uuid references public.members (id) on delete set null,
  member_id uuid references public.members (id) on delete set null,
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  decided_by text,
  unique (org_id, num)
);
create index applications_org on public.applications (org_id, created_at desc);
create index applications_interviewer on public.applications (interviewer_id);
create index applications_member on public.applications (member_id);
create table public.application_notes (
  id bigint generated always as identity primary key,
  application_id uuid not null references public.applications (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  text text not null check (length(text) between 2 and 1000),
  by_name text not null
);
create index application_notes_app on public.application_notes (application_id, at);
create index application_notes_org on public.application_notes (org_id);

-- formulaire public : sans connexion ; 20 candidatures par heure et par service au plus, une seule en cours par compte Discord
create or replace function public.send_application(p_code text, p_name text, p_discord text, p_contact text, p_answers jsonb) returns text
language plpgsql security definer set search_path = '' as $$
declare o public.orgs; n integer; v_num text; v_disc text := nullif(regexp_replace(coalesce(p_discord, ''), '\s', '', 'g'), ''); v_ans jsonb := coalesce(p_answers, '[]');
begin
  select * into o from public.orgs where code = lower(trim(p_code)) and sector = 'service_public' and 'academie' = any (modules);
  if o.id is null then raise exception 'Service inconnu.'; end if;
  if not o.recruiting then raise exception 'Le recrutement est fermé pour le moment.'; end if;
  if length(trim(coalesce(p_name, ''))) not between 2 and 60 then raise exception 'Indique le nom de ton personnage (2 à 60 caractères).'; end if;
  if v_disc is not null and v_disc !~ '^[0-9]{5,25}$' then raise exception 'L’identifiant Discord ne contient que des chiffres.'; end if;
  if jsonb_typeof(v_ans) <> 'array' or jsonb_array_length(v_ans) > 20 then raise exception 'Réponses invalides.'; end if;
  if exists (select 1 from jsonb_array_elements(v_ans) e where jsonb_typeof(e) <> 'object' or length(coalesce(e ->> 'q', '')) > 200 or length(coalesce(e ->> 'a', '')) > 2000) then
    raise exception 'Une réponse fait 2 000 caractères au plus.';
  end if;
  if exists (select 1 from jsonb_array_elements(v_ans) e where length(trim(coalesce(e ->> 'a', ''))) = 0) then raise exception 'Réponds à toutes les questions.'; end if;
  select count(*) into n from public.applications where org_id = o.id and created_at > now() - interval '1 hour';
  if n >= 20 then raise exception 'Trop de candidatures en ce moment. Réessaie dans une heure.'; end if;
  if v_disc is not null and exists (select 1 from public.applications where org_id = o.id and discord_id = v_disc and status in ('recue', 'entretien')) then
    raise exception 'Une candidature avec cet identifiant Discord est déjà en cours d’examen.';
  end if;
  v_num := public.next_num(o.id, 'application');
  insert into public.applications (org_id, num, name, discord_id, contact, answers) values (o.id, v_num, trim(p_name), v_disc, left(nullif(trim(coalesce(p_contact, '')), ''), 80),
    (select coalesce(jsonb_agg(jsonb_build_object('q', left(e.value ->> 'q', 200), 'a', trim(e.value ->> 'a')) order by e.n), '[]') from jsonb_array_elements(v_ans) with ordinality e(value, n)));
  perform public.alert_members(o.id, public.cap_members(o.id, 'academie'), 'academie', 'Nouvelle candidature ' || v_num || ' : ' || trim(p_name), '/academie');
  return v_num;
end $$;

create or replace function public.application_note(p_id uuid, p_text text) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.applications; me public.members;
begin
  select * into a from public.applications where id = p_id;
  if a.id is null then raise exception 'Candidature inconnue.'; end if;
  me := public.need(a.org_id, 'academie', 'academie');
  if length(trim(coalesce(p_text, ''))) < 2 then raise exception 'Écris la note.'; end if;
  insert into public.application_notes (application_id, org_id, text, by_name) values (a.id, a.org_id, left(trim(p_text), 1000), me.name);
end $$;

-- l'entretien entre dans l'agenda du recruteur, qui est alerté
create or replace function public.schedule_interview(p_id uuid, p_at timestamptz, p_interviewer uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.applications; me public.members; iv public.members; aid uuid; t text;
begin
  select * into a from public.applications where id = p_id for update;
  if a.id is null then raise exception 'Candidature inconnue.'; end if;
  me := public.need(a.org_id, 'academie', 'academie');
  if a.status not in ('recue', 'entretien') then raise exception 'Cette candidature est déjà décidée.'; end if;
  if p_at is null or p_at < now() - interval '1 hour' then raise exception 'Choisis une date à venir.'; end if;
  select * into iv from public.members where id = coalesce(p_interviewer, me.id) and org_id = a.org_id and active;
  if iv.id is null then raise exception 'Recruteur inconnu.'; end if;
  t := to_char(p_at at time zone 'Europe/Paris', 'DD/MM à HH24:MI');
  update public.applications set status = 'entretien', interview_at = p_at, interviewer_id = iv.id where id = a.id;
  insert into public.agenda (org_id, title, kind, starts_at, ends_at, note, by_name)
    values (a.org_id, left('Entretien · ' || a.name, 80), 'reunion', p_at, p_at + interval '30 minutes', 'Candidature ' || a.num, me.name) returning id into aid;
  insert into public.agenda_staff (agenda_id, member_id, org_id) values (aid, iv.id, a.org_id);
  insert into public.application_notes (application_id, org_id, text, by_name) values (a.id, a.org_id, 'Entretien prévu le ' || t || ' avec ' || iv.name, me.name);
  if iv.id <> me.id then perform public.alert_members(a.org_id, array[iv.id], 'academie', 'Entretien de ' || a.name || ' le ' || t, '/academie'); end if;
end $$;

create or replace function public.refuse_application(p_id uuid, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.applications; me public.members;
begin
  select * into a from public.applications where id = p_id for update;
  if a.id is null then raise exception 'Candidature inconnue.'; end if;
  me := public.need(a.org_id, 'academie', 'academie');
  if a.status not in ('recue', 'entretien') then raise exception 'Cette candidature est déjà décidée.'; end if;
  if length(trim(coalesce(p_note, ''))) < 3 then raise exception 'Indique le motif du refus.'; end if;
  update public.applications set status = 'refusee', decided_at = now(), decided_by = me.name where id = a.id;
  insert into public.application_notes (application_id, org_id, text, by_name) values (a.id, a.org_id, left('Refusée : ' || trim(p_note), 1000), me.name);
end $$;

-- accepter, c'est recruter : le compte est créé tout de suite (liste noire et places du grade vérifiées)
create or replace function public.accept_application(p_id uuid, p_login text, p_grade uuid, p_callsign text) returns json
language plpgsql security definer set search_path = '' as $$
declare a public.applications; me public.members; r json;
begin
  select * into a from public.applications where id = p_id for update;
  if a.id is null then raise exception 'Candidature inconnue.'; end if;
  me := public.need(a.org_id, 'academie', 'effectifs');
  if a.status not in ('recue', 'entretien') then raise exception 'Cette candidature est déjà décidée.'; end if;
  r := public.recruit_agent(a.org_id, a.name, p_login, p_grade, p_callsign, a.discord_id);
  update public.applications set status = 'acceptee', member_id = (r ->> 'member_id')::uuid, decided_at = now(), decided_by = me.name where id = a.id;
  insert into public.application_notes (application_id, org_id, text, by_name) values (a.id, a.org_id, 'Acceptée · recruté sous le matricule ' || (r ->> 'matricule'), me.name);
  return r;
end $$;

-- ---------------------------------------------------------------- académie : parcours de formation
create table public.training_modules (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 2 and 60),
  description text check (length(description) <= 300),
  sort integer not null default 0,
  archived boolean not null default false,
  unique (org_id, name)
);
create table public.training_progress (
  member_id uuid not null references public.members (id) on delete cascade,
  module_id uuid not null references public.training_modules (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  validated_at timestamptz not null default now(),
  by_name text not null,
  note text check (length(note) <= 200),
  primary key (member_id, module_id)
);
create index training_progress_module on public.training_progress (module_id);
create index training_progress_org on public.training_progress (org_id);

-- formateur : l'accès Académie ou la qualification FTO
create or replace function public.is_fto(o uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.has_cap(o, 'academie') or exists (select 1 from public.member_qualifications mq join public.qualifications q on q.id = mq.qualification_id
    where mq.member_id = (public.caller_member(o)).id and upper(q.name) = 'FTO')
$$;

create or replace function public.save_training_module(p_org uuid, p_id uuid, p_name text, p_description text, p_sort integer, p_archived boolean) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'academie', 'academie'); mid uuid;
begin
  if length(trim(coalesce(p_name, ''))) < 2 then raise exception 'Donne un nom au module.'; end if;
  if p_id is null then
    insert into public.training_modules (org_id, name, description, sort) values (p_org, trim(p_name), nullif(trim(coalesce(p_description, '')), ''),
      coalesce(p_sort, (select coalesce(max(sort), 0) + 1 from public.training_modules where org_id = p_org))) returning id into mid;
  else
    update public.training_modules set name = trim(p_name), description = nullif(trim(coalesce(p_description, '')), ''), sort = coalesce(p_sort, sort), archived = coalesce(p_archived, archived)
      where id = p_id and org_id = p_org returning id into mid;
    if mid is null then raise exception 'Module inconnu.'; end if;
  end if;
  return mid;
exception
  when unique_violation then raise exception 'Ce module existe déjà.';
  when check_violation then raise exception 'Nom de 2 à 60 caractères, description de 300 caractères au plus.';
end $$;

create or replace function public.set_fto(p_member uuid, p_fto uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members; f public.members;
begin
  select * into mem from public.members where id = p_member and active;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'academie', 'academie');
  if p_fto is not null then
    select * into f from public.members where id = p_fto and org_id = mem.org_id and active;
    if f.id is null then raise exception 'Formateur inconnu.'; end if;
    if f.id = mem.id then raise exception 'Un agent ne se forme pas lui-même.'; end if;
  end if;
  update public.members set fto_id = p_fto where id = mem.id;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (mem.org_id, mem.id, 'formation', coalesce('Formateur (FTO) : ' || f.name, 'Formateur retiré'), me.name);
  if f.id is not null and f.id <> me.id then perform public.alert_members(mem.org_id, array[f.id], 'academie', 'Tu formes désormais ' || mem.name, '/effectifs/' || mem.id); end if;
end $$;

create or replace function public.validate_module(p_member uuid, p_module uuid, p_on boolean, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members; md public.training_modules; n integer;
begin
  select * into mem from public.members where id = p_member and active;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'academie', null);
  if not public.is_fto(mem.org_id) then raise exception 'Réservé aux formateurs (qualification FTO) et à l’académie.'; end if;
  if mem.id = me.id then raise exception 'Un autre formateur valide tes modules.'; end if;
  select * into md from public.training_modules where id = p_module and org_id = mem.org_id;
  if md.id is null then raise exception 'Module inconnu.'; end if;
  if p_on then
    insert into public.training_progress (member_id, module_id, org_id, by_name, note) values (mem.id, md.id, mem.org_id, me.name, left(nullif(trim(coalesce(p_note, '')), ''), 200)) on conflict do nothing;
  else
    delete from public.training_progress where member_id = mem.id and module_id = md.id;
  end if;
  get diagnostics n = row_count;
  if n > 0 then
    insert into public.career_events (org_id, member_id, kind, text, by_name) values (mem.org_id, mem.id, 'formation', case when p_on then 'Module validé : ' else 'Module à refaire : ' end || md.name, me.name);
  end if;
end $$;

-- ---------------------------------------------------------------- académie : bibliothèque
create table public.library_docs (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  category text not null check (category in ('reglement', 'procedure', 'radio', 'guide')),
  title text not null check (length(title) between 3 and 120),
  body text not null default '' check (length(body) <= 20000),
  file_path text check (length(file_path) <= 300),
  file_name text check (length(file_name) <= 120),
  sort integer not null default 0,
  updated_at timestamptz not null default now(),
  by_name text not null
);
create index library_docs_org on public.library_docs (org_id, category, sort);

create or replace function public.save_doc(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'academie', 'academie'); did uuid; v_file text := nullif(trim(coalesce(p ->> 'file_path', '')), '');
begin
  if v_file is not null and not public.stored('mdt', p_org, v_file) then raise exception 'Fichier introuvable : envoie-le de nouveau.'; end if;
  if length(trim(coalesce(p ->> 'title', ''))) < 3 then raise exception 'Donne un titre au document.'; end if;
  if p_id is null then
    insert into public.library_docs (org_id, category, title, by_name) values (p_org, coalesce(nullif(p ->> 'category', ''), 'procedure'), trim(p ->> 'title'), me.name) returning id into did;
  else
    select id into did from public.library_docs where id = p_id and org_id = p_org;
    if did is null then raise exception 'Document inconnu.'; end if;
  end if;
  update public.library_docs set category = coalesce(nullif(p ->> 'category', ''), category), title = trim(p ->> 'title'), body = coalesce(p ->> 'body', ''),
    file_path = coalesce(v_file, file_path), file_name = case when v_file is not null then left(nullif(trim(coalesce(p ->> 'file_name', '')), ''), 120) else file_name end,
    sort = coalesce(nullif(p ->> 'sort', '')::integer, sort), updated_at = now(), by_name = me.name where id = did;
  return did;
exception when check_violation then raise exception 'Catégorie inconnue, titre de 3 à 120 caractères, texte de 20 000 caractères au plus.';
end $$;

create or replace function public.delete_doc(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare d public.library_docs;
begin
  select * into d from public.library_docs where id = p_id;
  if d.id is null then raise exception 'Document inconnu.'; end if;
  perform public.need(d.org_id, 'academie', 'academie');
  delete from public.library_docs where id = d.id;
end $$;

-- ---------------------------------------------------------------- académie : examens en ligne (QCM corrigé par le serveur)
create table public.exams (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  title text not null check (length(title) between 3 and 80),
  pass_pct integer not null default 70 check (pass_pct between 1 and 100),
  module_id uuid references public.training_modules (id) on delete set null,
  questions integer not null default 0,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  by_name text not null
);
create index exams_org on public.exams (org_id);
create index exams_module on public.exams (module_id);
-- les énoncés et les bonnes réponses : lus seulement par l'académie ; l'agent passe l'examen par exam_paper et submit_exam
create table public.exam_keys (
  exam_id uuid primary key references public.exams (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  questions jsonb not null check (jsonb_typeof(questions) = 'array')
);
create index exam_keys_org on public.exam_keys (org_id);
create table public.exam_attempts (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  exam_id uuid not null references public.exams (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  at timestamptz not null default now(),
  score integer not null check (score between 0 and 100),
  passed boolean not null
);
create index exam_attempts_exam on public.exam_attempts (exam_id);
create index exam_attempts_member on public.exam_attempts (member_id, at desc);
create index exam_attempts_org on public.exam_attempts (org_id);

create or replace function public.save_exam(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'academie', 'academie'); eid uuid; v_q jsonb := p -> 'questions'; v_mod uuid := nullif(p ->> 'module_id', '')::uuid;
begin
  if length(trim(coalesce(p ->> 'title', ''))) < 3 then raise exception 'Donne un titre à l’examen.'; end if;
  if v_mod is not null and not exists (select 1 from public.training_modules where id = v_mod and org_id = p_org) then raise exception 'Module inconnu.'; end if;
  if jsonb_typeof(v_q) is distinct from 'array' or jsonb_array_length(v_q) not between 1 and 50 then raise exception 'Un examen compte de 1 à 50 questions.'; end if;
  if exists (select 1 from jsonb_array_elements(v_q) e where jsonb_typeof(e) <> 'object' or length(trim(coalesce(e ->> 'q', ''))) not between 3 and 300
      or case when jsonb_typeof(e -> 'choices') = 'array' then jsonb_array_length(e -> 'choices') not between 2 and 6
        or exists (select 1 from jsonb_array_elements(e -> 'choices') c where jsonb_typeof(c) <> 'string' or length(trim(c #>> '{}')) not between 1 and 200) else true end
      or case when jsonb_typeof(e -> 'answer') = 'number' and jsonb_typeof(e -> 'choices') = 'array'
        then (e ->> 'answer')::numeric <> floor((e ->> 'answer')::numeric) or (e ->> 'answer')::numeric not between 0 and jsonb_array_length(e -> 'choices') - 1 else true end) then
    raise exception 'Chaque question a un énoncé (3 à 300 caractères), 2 à 6 réponses et une bonne réponse cochée.';
  end if;
  v_q := (select jsonb_agg(jsonb_build_object('q', trim(e.value ->> 'q'), 'answer', (e.value ->> 'answer')::integer,
      'choices', (select jsonb_agg(trim(c.value #>> '{}') order by c.n) from jsonb_array_elements(e.value -> 'choices') with ordinality c(value, n))) order by e.n)
    from jsonb_array_elements(v_q) with ordinality e(value, n));
  if p_id is null then
    insert into public.exams (org_id, title, by_name) values (p_org, trim(p ->> 'title'), me.name) returning id into eid;
  else
    select id into eid from public.exams where id = p_id and org_id = p_org;
    if eid is null then raise exception 'Examen inconnu.'; end if;
  end if;
  update public.exams set title = trim(p ->> 'title'), pass_pct = coalesce(nullif(p ->> 'pass_pct', '')::integer, pass_pct), module_id = v_mod,
    active = coalesce((p ->> 'active')::boolean, active), questions = jsonb_array_length(v_q) where id = eid;
  insert into public.exam_keys (exam_id, org_id, questions) values (eid, p_org, v_q) on conflict (exam_id) do update set questions = excluded.questions;
  return eid;
exception when check_violation then raise exception 'Titre de 3 à 80 caractères, seuil de réussite de 1 à 100 %%.';
end $$;

-- l'énoncé sans les réponses
create or replace function public.exam_paper(p_exam uuid) returns json
language plpgsql security definer set search_path = '' as $$
declare x public.exams; k public.exam_keys;
begin
  select * into x from public.exams where id = p_exam;
  if x.id is null then raise exception 'Examen inconnu.'; end if;
  perform public.need(x.org_id, 'academie', null);
  if not x.active then raise exception 'Cet examen est fermé.'; end if;
  select * into k from public.exam_keys where exam_id = x.id;
  return json_build_object('id', x.id, 'title', x.title, 'pass_pct', x.pass_pct, 'questions',
    (select coalesce(json_agg(json_build_object('q', e.value ->> 'q', 'choices', e.value -> 'choices') order by e.n), '[]') from jsonb_array_elements(k.questions) with ordinality e(value, n)));
end $$;

-- corrigé par le serveur ; une nouvelle tentative 24 heures après un échec ; réussi, il valide le module lié
create or replace function public.submit_exam(p_exam uuid, p_answers jsonb) returns json
language plpgsql security definer set search_path = '' as $$
declare x public.exams; me public.members; k public.exam_keys; prev public.exam_attempts; total integer; good integer; sc integer; ok boolean;
begin
  select * into x from public.exams where id = p_exam;
  if x.id is null then raise exception 'Examen inconnu.'; end if;
  me := public.need(x.org_id, 'academie', null);
  if not x.active then raise exception 'Cet examen est fermé.'; end if;
  if exists (select 1 from public.exam_attempts where exam_id = x.id and member_id = me.id and passed) then raise exception 'Tu as déjà réussi cet examen.'; end if;
  select * into prev from public.exam_attempts where exam_id = x.id and member_id = me.id order by at desc limit 1;
  if prev.id is not null and prev.at > now() - interval '24 hours' then
    raise exception 'Tu pourras repasser cet examen le %.', to_char((prev.at + interval '24 hours') at time zone 'Europe/Paris', 'DD/MM à HH24:MI');
  end if;
  select * into k from public.exam_keys where exam_id = x.id;
  total := jsonb_array_length(k.questions);
  if jsonb_typeof(coalesce(p_answers, '[]')) <> 'array' or jsonb_array_length(coalesce(p_answers, '[]')) <> total
    or exists (select 1 from jsonb_array_elements(p_answers) a where jsonb_typeof(a) <> 'number') then raise exception 'Réponds à toutes les questions.'; end if;
  select count(*) into good from jsonb_array_elements(k.questions) with ordinality q(value, n) join jsonb_array_elements(p_answers) with ordinality a(value, n) using (n)
    where a.value = q.value -> 'answer';
  sc := round(100.0 * good / total);
  ok := sc >= x.pass_pct;
  insert into public.exam_attempts (org_id, exam_id, member_id, score, passed) values (x.org_id, x.id, me.id, sc, ok);
  if ok then
    insert into public.career_events (org_id, member_id, kind, text, by_name) values (x.org_id, me.id, 'formation', 'Examen réussi : ' || x.title || ' (' || sc || ' %)', 'Examen en ligne');
    if x.module_id is not null then
      insert into public.training_progress (member_id, module_id, org_id, by_name, note) values (me.id, x.module_id, x.org_id, 'Examen en ligne', left(x.title || ' · ' || sc || ' %', 200)) on conflict do nothing;
    end if;
  end if;
  return json_build_object('score', sc, 'passed', ok, 'good', good, 'total', total, 'pass_pct', x.pass_pct);
end $$;

-- ---------------------------------------------------------------- académie : évaluations terrain et entraînements
create table public.field_evals (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  fto_id uuid references public.members (id) on delete set null,
  at timestamptz not null default now(),
  scores jsonb not null check (jsonb_typeof(scores) = 'object'),
  overall integer not null check (overall between 1 and 5),
  comment text check (length(comment) <= 1000),
  by_name text not null
);
create index field_evals_member on public.field_evals (member_id, at desc);
create index field_evals_fto on public.field_evals (fto_id);
create index field_evals_org on public.field_evals (org_id);

create or replace function public.add_field_eval(p_member uuid, p_scores jsonb, p_overall integer, p_comment text) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members;
begin
  select * into mem from public.members where id = p_member and active;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'academie', null);
  if not public.is_fto(mem.org_id) then raise exception 'Réservé aux formateurs (qualification FTO) et à l’académie.'; end if;
  if mem.id = me.id then raise exception 'Un autre formateur t’évalue.'; end if;
  if jsonb_typeof(coalesce(p_scores, '{}')) <> 'object' or (select count(*) from jsonb_object_keys(coalesce(p_scores, '{}'))) > 10
    or exists (select 1 from jsonb_each(coalesce(p_scores, '{}')) s where length(s.key) > 40 or jsonb_typeof(s.value) <> 'number' or (s.value)::text::numeric not in (1, 2, 3, 4, 5)) then
    raise exception 'Chaque critère est noté de 1 à 5.';
  end if;
  if coalesce(p_overall, 0) not between 1 and 5 then raise exception 'Donne une note globale de 1 à 5.'; end if;
  insert into public.field_evals (org_id, member_id, fto_id, scores, overall, comment, by_name)
    values (mem.org_id, mem.id, me.id, coalesce(p_scores, '{}'), p_overall, left(nullif(trim(coalesce(p_comment, '')), ''), 1000), me.name);
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (mem.org_id, mem.id, 'formation', 'Évaluation terrain : ' || p_overall || '/5', me.name);
end $$;

alter table public.agenda_staff add column present boolean;

create or replace function public.plan_training(p_org uuid, p_title text, p_starts timestamptz, p_ends timestamptz, p_place text, p_note text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'academie', 'academie'); aid uuid;
begin
  if length(trim(coalesce(p_title, ''))) < 3 then raise exception 'Donne un titre.'; end if;
  if p_starts is null or p_ends is null or p_ends <= p_starts then raise exception 'Vérifie l’heure de début et de fin.'; end if;
  insert into public.agenda (org_id, title, kind, starts_at, ends_at, place, note, by_name)
    values (p_org, left(trim(p_title), 80), 'formation', p_starts, p_ends, left(nullif(trim(coalesce(p_place, '')), ''), 80), left(nullif(trim(coalesce(p_note, '')), ''), 300), me.name) returning id into aid;
  return aid;
end $$;

create or replace function public.training_join(p_id uuid, p_on boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.agenda; me public.members;
begin
  select * into a from public.agenda where id = p_id and kind = 'formation';
  if a.id is null then raise exception 'Entraînement inconnu.'; end if;
  me := public.need(a.org_id, 'academie', null);
  if a.ends_at < now() then raise exception 'Cet entraînement est terminé.'; end if;
  if p_on then
    insert into public.agenda_staff (agenda_id, member_id, org_id) values (a.id, me.id, a.org_id) on conflict do nothing;
  else
    delete from public.agenda_staff where agenda_id = a.id and member_id = me.id;
  end if;
end $$;

-- la liste de présence d'un entraînement, tenue par le formateur
create or replace function public.mark_presence(p_agenda uuid, p_member uuid, p_present boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare a public.agenda; me public.members;
begin
  select * into a from public.agenda where id = p_agenda and kind = 'formation';
  if a.id is null then raise exception 'Entraînement inconnu.'; end if;
  me := public.need(a.org_id, 'academie', null);
  if not public.is_fto(a.org_id) then raise exception 'Réservé aux formateurs (qualification FTO) et à l’académie.'; end if;
  if not exists (select 1 from public.members where id = p_member and org_id = a.org_id) then raise exception 'Agent inconnu.'; end if;
  if a.starts_at > now() + interval '1 hour' then raise exception 'L’appel se fait au début de l’entraînement.'; end if;
  insert into public.agenda_staff (agenda_id, member_id, org_id, present) values (a.id, p_member, a.org_id, p_present)
    on conflict (agenda_id, member_id) do update set present = excluded.present;
end $$;

-- ---------------------------------------------------------------- affaires internes : accès, casier, plaintes, enquêtes
-- l'accès « Affaires internes » (le Director l'a toujours) ou la personne qu'il désigne
create or replace function public.ia_access(o uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.has_cap(o, 'affaires') or exists (select 1 from public.orgs x join public.members m on m.id = x.ia_member_id
    where x.id = o and m.user_id = auth.uid() and m.active and (m.suspended_until is null or m.suspended_until <= now()))
$$;
create or replace function public.ia_members(o uuid) returns uuid[]
language sql stable security definer set search_path = '' as $$
  select array(select distinct x from unnest(public.cap_members(o, 'affaires') || (select array_remove(array[x.ia_member_id], null) from public.orgs x where x.id = o)) x)
$$;

create or replace function public.set_ia_officer(p_org uuid, p_member uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'affaires', null); n text;
begin
  if not public.is_top(p_org) then raise exception 'Seul le Director désigne la personne chargée des Affaires internes.'; end if;
  if p_member is not null then
    select name into n from public.members where id = p_member and org_id = p_org and active;
    if n is null then raise exception 'Agent inconnu.'; end if;
  end if;
  update public.orgs set ia_member_id = p_member where id = p_org;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Affaires internes : ' || coalesce('confiées à ' || n, 'plus de personne désignée'));
  if p_member is not null and p_member <> me.id then perform public.alert_members(p_org, array[p_member], 'affaires', 'Le Director te confie les Affaires internes.', '/affaires'); end if;
end $$;

alter table public.ia_cases
  add column origin text not null default 'auto' check (origin in ('auto', 'interne', 'plainte')),
  add column facts text check (length(facts) <= 4000),
  add column agent_version text check (length(agent_version) <= 4000),
  add column decision text check (length(decision) <= 2000),
  add column decided_at timestamptz,
  add column decided_by text,
  add column investigator_id uuid references public.members (id) on delete set null;
create index ia_cases_investigator on public.ia_cases (investigator_id);
create index ia_cases_org on public.ia_cases (org_id, opened_at desc);

create table public.complaints (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  source text not null check (source in ('public', 'interne')),
  against_member_id uuid references public.members (id) on delete set null,
  against_text text check (length(against_text) <= 120),
  facts text not null check (length(facts) between 20 and 3000),
  happened text check (length(happened) <= 120),
  complainant text check (length(complainant) <= 80),
  contact text check (length(contact) <= 80),
  by_member_id uuid references public.members (id) on delete set null,
  status text not null default 'recue' check (status in ('recue', 'enquete', 'classee')),
  ia_case_id uuid references public.ia_cases (id) on delete set null,
  created_at timestamptz not null default now(),
  unique (org_id, num)
);
create index complaints_org on public.complaints (org_id, created_at desc);
create index complaints_against on public.complaints (against_member_id);
create index complaints_by on public.complaints (by_member_id);
create index complaints_case on public.complaints (ia_case_id);
alter table public.ia_cases add column complaint_id uuid references public.complaints (id) on delete set null;
create index ia_cases_complaint on public.ia_cases (complaint_id);

create table public.ia_log (
  id bigint generated always as identity primary key,
  case_id uuid not null references public.ia_cases (id) on delete cascade,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  kind text not null check (kind in ('note', 'preuve', 'audition', 'decision')),
  text text not null check (length(text) between 2 and 2000),
  path text check (length(path) <= 300),
  file_name text check (length(file_name) <= 120),
  by_name text not null
);
create index ia_log_case on public.ia_log (case_id, at);
create index ia_log_org on public.ia_log (org_id);

create table public.discipline (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  kind text not null check (kind in ('avertissement', 'blame', 'suspension', 'retrogradation', 'renvoi')),
  reason text not null check (length(reason) between 3 and 300),
  until timestamptz,
  ia_case_id uuid references public.ia_cases (id) on delete set null,
  at timestamptz not null default now(),
  by_name text not null,
  lifted_at timestamptz
);
create index discipline_member on public.discipline (member_id, at desc);
create index discipline_org on public.discipline (org_id);
create index discipline_case on public.discipline (ia_case_id);

-- chaque nouveau dossier (ouverture automatique comprise) alerte les Affaires internes, sauf l'agent visé
create or replace function public.ia_case_alert() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  perform public.alert_members(new.org_id, array(select x from unnest(public.ia_members(new.org_id)) x
      where x is distinct from (public.caller_member(new.org_id)).id and x is distinct from new.member_id),
    'affaires', 'Dossier Affaires internes ' || new.num || ' : ' || left(new.reason, 150), '/affaires/' || new.id);
  return new;
end $$;
create trigger ia_case_alert after insert on public.ia_cases for each row execute function public.ia_case_alert();

-- un témoin WITSEC déclaré perdu ouvre un dossier Affaires internes
create or replace function public.ia_witness_lost() returns trigger
language plpgsql security definer set search_path = '' as $$
declare ia text;
begin
  if new.status = 'perdu' and old.status is distinct from 'perdu' then
    ia := public.next_num(new.org_id, 'ia');
    insert into public.ia_cases (org_id, num, reason, opened_by, origin) values (new.org_id, ia, 'Témoin WITSEC ' || new.code || ' perdu', 'Ouverture automatique', 'auto');
    insert into public.journal (org_id, who, what) values (new.org_id, 'Affaires internes', 'Dossier ' || ia || ' ouvert automatiquement (témoin ' || new.code || ' perdu)');
  end if;
  return new;
end $$;
create trigger ia_witness_lost after update of status on public.witnesses for each row execute function public.ia_witness_lost();

-- plainte d'un civil depuis la page publique : sans connexion, 20 par heure et par service au plus
create or replace function public.send_complaint(p_code text, p jsonb) returns text
language plpgsql security definer set search_path = '' as $$
declare o uuid; n integer; v_num text;
begin
  select id into o from public.orgs where code = lower(trim(p_code)) and sector = 'service_public' and 'affaires' = any (modules);
  if o is null then raise exception 'Service inconnu.'; end if;
  if length(trim(coalesce(p ->> 'facts', ''))) < 20 then raise exception 'Décris les faits (20 caractères au moins).'; end if;
  if length(trim(p ->> 'facts')) > 3000 then raise exception 'Ton récit est trop long (3 000 caractères au plus).'; end if;
  select count(*) into n from public.complaints where org_id = o and source = 'public' and created_at > now() - interval '1 hour';
  if n >= 20 then raise exception 'Trop de plaintes en ce moment. Réessaie dans une heure.'; end if;
  v_num := public.next_num(o, 'complaint');
  insert into public.complaints (org_id, num, source, against_text, facts, happened, complainant, contact)
    values (o, v_num, 'public', left(nullif(trim(coalesce(p ->> 'against', '')), ''), 120), trim(p ->> 'facts'), left(nullif(trim(coalesce(p ->> 'happened', '')), ''), 120),
      left(nullif(trim(coalesce(p ->> 'complainant', '')), ''), 80), left(nullif(trim(coalesce(p ->> 'contact', '')), ''), 80));
  perform public.alert_members(o, public.ia_members(o), 'affaires', 'Plainte ' || v_num || ' reçue par la page publique', '/affaires');
  return v_num;
end $$;

-- plainte d'un agent contre un collègue
create or replace function public.file_complaint(p_org uuid, p jsonb) returns text
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'affaires', null); target public.members; v_num text;
begin
  select * into target from public.members where id = nullif(p ->> 'against_member_id', '')::uuid and org_id = p_org;
  if target.id is null then raise exception 'Choisis l’agent concerné.'; end if;
  if target.id = me.id then raise exception 'Tu ne peux pas porter plainte contre toi-même.'; end if;
  if length(trim(coalesce(p ->> 'facts', ''))) < 20 then raise exception 'Décris les faits (20 caractères au moins).'; end if;
  v_num := public.next_num(p_org, 'complaint');
  insert into public.complaints (org_id, num, source, against_member_id, against_text, facts, happened, complainant, by_member_id)
    values (p_org, v_num, 'interne', target.id, target.name, left(trim(p ->> 'facts'), 3000), left(nullif(trim(coalesce(p ->> 'happened', '')), ''), 120), me.name, me.id);
  perform public.alert_members(p_org, array(select x from unnest(public.ia_members(p_org)) x where x <> me.id and x <> target.id), 'affaires', 'Plainte ' || v_num || ' contre ' || target.name, '/affaires');
  return v_num;
end $$;

create or replace function public.complaint_status(p_id uuid, p_status text) returns void
language plpgsql security definer set search_path = '' as $$
declare c public.complaints; me public.members;
begin
  select * into c from public.complaints where id = p_id;
  if c.id is null then raise exception 'Plainte inconnue.'; end if;
  me := public.need(c.org_id, 'affaires', null);
  if not public.ia_access(c.org_id) then raise exception 'Réservé aux Affaires internes.'; end if;
  if c.against_member_id = me.id and not public.is_top(c.org_id) then raise exception 'Cette plainte te concerne : un autre enquêteur s’en charge.'; end if;
  if p_status not in ('recue', 'classee') then raise exception 'Statut inconnu.'; end if;
  if c.status = 'enquete' then raise exception 'Une enquête est ouverte : elle se clôt dans le dossier.'; end if;
  update public.complaints set status = p_status where id = c.id;
end $$;

-- l'enquêteur des Affaires internes ; un dossier qui le vise lui reste fermé (sauf au Director)
create or replace function public.ia_case_of(p_id uuid) returns public.ia_cases
language plpgsql security definer set search_path = '' as $$
declare c public.ia_cases; me public.members;
begin
  select * into c from public.ia_cases where id = p_id for update;
  if c.id is null then raise exception 'Dossier inconnu.'; end if;
  me := public.need(c.org_id, 'affaires', null);
  if not public.ia_access(c.org_id) then raise exception 'Réservé aux Affaires internes.'; end if;
  if c.member_id = me.id and not public.is_top(c.org_id) then raise exception 'Ce dossier te concerne : un autre enquêteur s’en charge.'; end if;
  return c;
end $$;

create or replace function public.open_ia_case(p_org uuid, p jsonb) returns json
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'affaires', null); c public.complaints; v_member uuid := nullif(p ->> 'member_id', '')::uuid; v_reason text := trim(coalesce(p ->> 'reason', ''));
  v_num text; cid uuid;
begin
  if not public.ia_access(p_org) then raise exception 'Réservé aux Affaires internes.'; end if;
  if nullif(p ->> 'complaint_id', '') is not null then
    select * into c from public.complaints where id = (p ->> 'complaint_id')::uuid and org_id = p_org for update;
    if c.id is null then raise exception 'Plainte inconnue.'; end if;
    if c.status <> 'recue' then raise exception 'Cette plainte est déjà traitée.'; end if;
    v_member := coalesce(v_member, c.against_member_id);
    if v_reason = '' then v_reason := 'Plainte ' || c.num || ' · ' || left(c.facts, 200); end if;
  end if;
  if v_member is not null and not exists (select 1 from public.members where id = v_member and org_id = p_org) then raise exception 'Agent inconnu.'; end if;
  if v_member = me.id then raise exception 'Tu ne peux pas enquêter sur toi-même.'; end if;
  if length(v_reason) < 3 then raise exception 'Indique l’objet de l’enquête.'; end if;
  v_num := public.next_num(p_org, 'ia');
  insert into public.ia_cases (org_id, num, member_id, reason, status, opened_by, origin, complaint_id, investigator_id, facts)
    values (p_org, v_num, v_member, left(v_reason, 300), 'enquete', me.name, case when c.id is null then 'interne' else 'plainte' end, c.id, me.id, case when c.id is not null then c.facts end)
    returning id into cid;
  if c.id is not null then update public.complaints set status = 'enquete', ia_case_id = cid where id = c.id; end if;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Affaires internes : dossier ' || v_num || ' ouvert');
  return json_build_object('id', cid, 'num', v_num);
end $$;

create or replace function public.ia_update(p_id uuid, p jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare c public.ia_cases := public.ia_case_of(p_id); v_inv uuid := nullif(p ->> 'investigator_id', '')::uuid;
begin
  if c.status in ('classe', 'sanction') then raise exception 'Ce dossier est clos.'; end if;
  if v_inv is not null and not exists (select 1 from public.members where id = v_inv and org_id = c.org_id and active) then raise exception 'Enquêteur inconnu.'; end if;
  if v_inv is not null and v_inv = c.member_id then raise exception 'L’agent visé n’enquête pas sur lui-même.'; end if;
  update public.ia_cases set facts = case when p ? 'facts' then left(nullif(trim(p ->> 'facts'), ''), 4000) else facts end,
    agent_version = case when p ? 'agent_version' then left(nullif(trim(p ->> 'agent_version'), ''), 4000) else agent_version end,
    investigator_id = case when p ? 'investigator_id' then v_inv else investigator_id end, status = 'enquete' where id = c.id;
end $$;

create or replace function public.ia_note(p_id uuid, p_kind text, p_text text, p_path text, p_name text) returns void
language plpgsql security definer set search_path = '' as $$
declare c public.ia_cases := public.ia_case_of(p_id); me public.members := public.caller_member(c.org_id); v_path text := nullif(trim(coalesce(p_path, '')), '');
begin
  if c.status in ('classe', 'sanction') then raise exception 'Ce dossier est clos.'; end if;
  if p_kind not in ('note', 'preuve', 'audition') then raise exception 'Type d’entrée inconnu.'; end if;
  if length(trim(coalesce(p_text, ''))) < 2 then raise exception 'Écris le contenu.'; end if;
  if v_path is not null and not public.stored('mdt', c.org_id, v_path) then raise exception 'Fichier introuvable : envoie-le de nouveau.'; end if;
  insert into public.ia_log (case_id, org_id, kind, text, path, file_name, by_name) values (c.id, c.org_id, p_kind, left(trim(p_text), 2000), v_path, left(nullif(trim(coalesce(p_name, '')), ''), 120), me.name);
  update public.ia_cases set status = 'enquete' where id = c.id and status = 'ouvert';
end $$;

-- sanction appliquée tout de suite : avertissement, blâme, suspension (plus aucun accès), rétrogradation, renvoi
create or replace function public.apply_sanction(p_member uuid, p_kind text, p_reason text, p_days integer, p_case uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members; g public.grades; low public.grades; v_until timestamptz; v_reason text := trim(coalesce(p_reason, '')); lbl text;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null or not mem.active then raise exception 'Agent inconnu ou déjà parti.'; end if;
  me := public.need(mem.org_id, 'affaires', null);
  if not public.ia_access(mem.org_id) then raise exception 'Réservé aux Affaires internes.'; end if;
  if mem.id = me.id then raise exception 'Tu ne peux pas te sanctionner toi-même.'; end if;
  select * into g from public.grades where id = mem.grade_id;
  if g.rank = (select max(rank) from public.grades where org_id = mem.org_id) then raise exception 'Le Director ne peut pas être sanctionné depuis le MDT.'; end if;
  if p_kind not in ('avertissement', 'blame', 'suspension', 'retrogradation', 'renvoi') then raise exception 'Sanction inconnue.'; end if;
  if length(v_reason) < 3 then raise exception 'Indique le motif de la sanction.'; end if;
  if p_case is not null and not exists (select 1 from public.ia_cases where id = p_case and org_id = mem.org_id) then raise exception 'Dossier inconnu.'; end if;
  lbl := case p_kind when 'avertissement' then 'Avertissement' when 'blame' then 'Blâme' when 'suspension' then 'Suspension de ' || coalesce(p_days, 0) || ' jour(s)'
    when 'retrogradation' then 'Rétrogradation' else 'Renvoi' end;
  if p_kind = 'suspension' then
    if coalesce(p_days, 0) not between 1 and 60 then raise exception 'Une suspension dure de 1 à 60 jours.'; end if;
    v_until := now() + make_interval(days => p_days);
    update public.members set suspended_until = v_until, duty_status = 'dispo' where id = mem.id;
    update public.shifts set ended_at = now() where member_id = mem.id and ended_at is null;
  elsif p_kind = 'retrogradation' then
    select * into low from public.grades where org_id = mem.org_id and rank < g.rank order by rank desc limit 1;
    if low.id is null then raise exception '% est déjà au grade le plus bas.', mem.name; end if;
    update public.members set grade_id = low.id where id = mem.id;
    lbl := 'Rétrogradation ' || g.name || ' → ' || low.name;
  elsif p_kind = 'renvoi' then
    update public.members set active = false, left_at = now(), left_reason = left('Renvoi : ' || v_reason, 200) where id = mem.id;
    update public.shifts set ended_at = now() where member_id = mem.id and ended_at is null;
    delete from public.member_units where member_id = mem.id;
  end if;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (mem.org_id, mem.id,
    case p_kind when 'retrogradation' then 'retrogradation' when 'renvoi' then 'depart' else 'sanction' end, left(lbl || ' · ' || v_reason, 300), me.name);
  insert into public.discipline (org_id, member_id, kind, reason, until, ia_case_id, by_name) values (mem.org_id, mem.id, p_kind, left(v_reason, 300), v_until, p_case, me.name);
  insert into public.journal (org_id, who, what) values (mem.org_id, me.name, 'Sanction · ' || mem.name || ' : ' || lbl);
  if p_kind in ('avertissement', 'blame', 'retrogradation') then
    perform public.alert_members(mem.org_id, array[mem.id], 'affaires', left(lbl || ' · ' || v_reason, 300), '/effectifs/' || mem.id);
  end if;
end $$;

create or replace function public.lift_suspension(p_member uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'affaires', null);
  if not public.ia_access(mem.org_id) then raise exception 'Réservé aux Affaires internes.'; end if;
  if mem.suspended_until is null or mem.suspended_until <= now() then raise exception '% n’est pas suspendu.', mem.name; end if;
  update public.members set suspended_until = null where id = mem.id;
  update public.discipline set lifted_at = now() where member_id = mem.id and kind = 'suspension' and lifted_at is null and until > now();
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (mem.org_id, mem.id, 'retour', 'Suspension levée', me.name);
  insert into public.journal (org_id, who, what) values (mem.org_id, me.name, 'Suspension levée · ' || mem.name);
end $$;

-- clore l'enquête : classement, ou sanction appliquée tout de suite
create or replace function public.ia_close(p_id uuid, p_decision text, p_sanction text, p_days integer) returns void
language plpgsql security definer set search_path = '' as $$
declare c public.ia_cases := public.ia_case_of(p_id); me public.members := public.caller_member(c.org_id);
begin
  if c.status in ('classe', 'sanction') then raise exception 'Ce dossier est déjà clos.'; end if;
  if length(trim(coalesce(p_decision, ''))) < 10 then raise exception 'Motive la décision (10 caractères au moins).'; end if;
  if nullif(p_sanction, '') is not null then
    if c.member_id is null then raise exception 'Aucun agent n’est visé par ce dossier : désigne-le avant de sanctionner.'; end if;
    perform public.apply_sanction(c.member_id, p_sanction, 'Dossier ' || c.num || ' · ' || left(trim(p_decision), 250), p_days, c.id);
  end if;
  update public.ia_cases set status = case when nullif(p_sanction, '') is null then 'classe' else 'sanction' end, decision = left(trim(p_decision), 2000),
    decided_at = now(), decided_by = me.name where id = c.id;
  insert into public.ia_log (case_id, org_id, kind, text, by_name) values (c.id, c.org_id, 'decision', left(trim(p_decision), 2000), me.name);
  update public.complaints set status = 'classee' where ia_case_id = c.id;
  insert into public.journal (org_id, who, what) values (c.org_id, me.name, 'Affaires internes : dossier ' || c.num || case when nullif(p_sanction, '') is null then ' classé' else ' clos par une sanction' end);
end $$;

-- ---------------------------------------------------------------- logistique : armurerie, équipement, parc automobile
-- l'agent peut-il recevoir ce matériel ? null si oui, sinon la raison
create or replace function public.gear_check(p_member uuid, p_min uuid, p_qual uuid, p_sog boolean) returns text
language sql stable security definer set search_path = '' as $$
  select case
    when p_min is not null and (select g.rank from public.grades g join public.members m on m.grade_id = g.id where m.id = p_member) < (select rank from public.grades where id = p_min)
      then 'grade minimum ' || (select name from public.grades where id = p_min)
    when p_qual is not null and not exists (select 1 from public.member_qualifications where member_id = p_member and qualification_id = p_qual)
      then 'qualification ' || (select name from public.qualifications where id = p_qual) || ' requise'
    when p_sog and not exists (select 1 from public.sog_operators where member_id = p_member)
      and not exists (select 1 from public.member_qualifications mq join public.qualifications q on q.id = mq.qualification_id where mq.member_id = p_member and upper(q.name) = 'SOG')
      then 'réservé au SOG'
  end
$$;

create table public.weapons (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  serial text not null check (serial ~ '^[A-Z0-9][A-Z0-9-]{2,29}$'),
  model text not null check (length(model) between 2 and 60),
  category text not null check (category in ('poing', 'epaule', 'lourde', 'non_letal')),
  status text not null default 'armurerie' check (status in ('armurerie', 'attribuee', 'maintenance', 'perdue', 'detruite')),
  holder_id uuid references public.members (id) on delete set null,
  min_grade_id uuid references public.grades (id) on delete set null,
  qualification_id uuid references public.qualifications (id) on delete set null,
  notes text check (length(notes) <= 300),
  created_at timestamptz not null default now(),
  unique (org_id, serial)
);
create index weapons_holder on public.weapons (holder_id);
create index weapons_min_grade on public.weapons (min_grade_id);
create index weapons_qualification on public.weapons (qualification_id);
create table public.weapon_loans (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  weapon_id uuid not null references public.weapons (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  out_at timestamptz not null default now(),
  back_at timestamptz,
  out_by text not null,
  back_by text,
  note text check (length(note) <= 200)
);
create index weapon_loans_weapon on public.weapon_loans (weapon_id, out_at desc);
create index weapon_loans_member on public.weapon_loans (member_id);
create index weapon_loans_org on public.weapon_loans (org_id);
create table public.equipment (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 2 and 60),
  kind text not null check (kind in ('munitions', 'gilet', 'bouclier', 'radio', 'autre')),
  qty integer not null default 0 check (qty >= 0),
  unit text not null default 'unité' check (length(unit) between 1 and 20),
  alert_below integer check (alert_below >= 0),
  unique (org_id, name)
);
create table public.equipment_moves (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  item_id uuid not null references public.equipment (id) on delete cascade,
  member_id uuid references public.members (id) on delete set null,
  qty integer not null check (qty <> 0),
  at timestamptz not null default now(),
  note text check (length(note) <= 200),
  by_name text not null
);
create index equipment_moves_item on public.equipment_moves (item_id, at desc);
create index equipment_moves_member on public.equipment_moves (member_id);
create index equipment_moves_org on public.equipment_moves (org_id);
create table public.fleet (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  model text not null check (length(model) between 2 and 60),
  plate text not null check (plate ~ '^[A-Z0-9][A-Z0-9 -]{1,9}$'),
  kind text not null check (kind in ('marque', 'banalise', 'blinde', 'moto', 'utilitaire', 'aerien')),
  state text not null default 'ok' check (state in ('ok', 'abime', 'reparation', 'hors_service')),
  min_grade_id uuid references public.grades (id) on delete set null,
  qualification_id uuid references public.qualifications (id) on delete set null,
  notes text check (length(notes) <= 300),
  unique (org_id, plate)
);
create index fleet_min_grade on public.fleet (min_grade_id);
create index fleet_qualification on public.fleet (qualification_id);
create table public.fleet_trips (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  vehicle_id uuid not null references public.fleet (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  status text not null check (status in ('reservee', 'sortie', 'rendue', 'annulee')),
  starts_at timestamptz not null,
  ends_at timestamptz,
  out_at timestamptz,
  back_at timestamptz,
  purpose text check (length(purpose) <= 120),
  by_name text not null
);
create index fleet_trips_vehicle on public.fleet_trips (vehicle_id, starts_at desc);
create index fleet_trips_member on public.fleet_trips (member_id);
create index fleet_trips_org on public.fleet_trips (org_id);
create unique index fleet_one_out on public.fleet_trips (vehicle_id) where status = 'sortie';
create table public.fleet_damage (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  vehicle_id uuid not null references public.fleet (id) on delete cascade,
  at timestamptz not null default now(),
  member_id uuid references public.members (id) on delete set null,
  description text not null check (length(description) between 3 and 500),
  repaired_at timestamptz,
  repaired_by text,
  by_name text not null
);
create index fleet_damage_vehicle on public.fleet_damage (vehicle_id, at desc);
create index fleet_damage_member on public.fleet_damage (member_id);
create index fleet_damage_org on public.fleet_damage (org_id);

create or replace function public.save_weapon(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'logistique', 'logistique'); wid uuid; v_min uuid := nullif(p ->> 'min_grade_id', '')::uuid; v_q uuid := nullif(p ->> 'qualification_id', '')::uuid;
begin
  if v_min is not null and not exists (select 1 from public.grades where id = v_min and org_id = p_org) then raise exception 'Grade inconnu.'; end if;
  if v_q is not null and not exists (select 1 from public.qualifications where id = v_q and org_id = p_org) then raise exception 'Qualification inconnue.'; end if;
  if p_id is null then
    insert into public.weapons (org_id, serial, model, category) values (p_org, upper(trim(coalesce(p ->> 'serial', ''))), trim(coalesce(p ->> 'model', '')), coalesce(nullif(p ->> 'category', ''), 'poing'))
      returning id into wid;
    insert into public.journal (org_id, who, what) values (p_org, me.name, 'Armurerie : entrée de l’arme ' || upper(trim(p ->> 'serial')));
  else
    select id into wid from public.weapons where id = p_id and org_id = p_org;
    if wid is null then raise exception 'Arme inconnue.'; end if;
  end if;
  update public.weapons set serial = upper(trim(coalesce(p ->> 'serial', serial))), model = trim(coalesce(p ->> 'model', model)), category = coalesce(nullif(p ->> 'category', ''), category),
    min_grade_id = v_min, qualification_id = v_q, notes = nullif(trim(coalesce(p ->> 'notes', '')), '') where id = wid;
  return wid;
exception
  when unique_violation then raise exception 'Ce numéro de série existe déjà.';
  when check_violation then raise exception 'Numéro de série de 3 à 30 lettres, chiffres ou tirets ; modèle de 2 à 60 caractères ; catégorie connue.';
end $$;

create or replace function public.issue_weapon(p_weapon uuid, p_member uuid, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.weapons; me public.members; mem public.members; why text;
begin
  select * into w from public.weapons where id = p_weapon for update;
  if w.id is null then raise exception 'Arme inconnue.'; end if;
  me := public.need(w.org_id, 'logistique', 'logistique');
  if w.status <> 'armurerie' then raise exception 'Cette arme n’est pas disponible à l’armurerie.'; end if;
  select * into mem from public.members where id = p_member and org_id = w.org_id and active;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  if mem.suspended_until > now() then raise exception '% est suspendu.', mem.name; end if;
  why := public.gear_check(mem.id, w.min_grade_id, w.qualification_id, w.category = 'lourde');
  if why is not null then raise exception 'Impossible de remettre cette arme à % : %.', mem.name, why; end if;
  update public.weapons set status = 'attribuee', holder_id = mem.id where id = w.id;
  insert into public.weapon_loans (org_id, weapon_id, member_id, out_by, note) values (w.org_id, w.id, mem.id, me.name, left(nullif(trim(coalesce(p_note, '')), ''), 200));
  if mem.id <> me.id then perform public.alert_members(w.org_id, array[mem.id], 'logistique', 'Arme remise : ' || w.model || ' (' || w.serial || ')', '/logistique'); end if;
end $$;

create or replace function public.return_weapon(p_weapon uuid, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.weapons; me public.members;
begin
  select * into w from public.weapons where id = p_weapon for update;
  if w.id is null then raise exception 'Arme inconnue.'; end if;
  me := public.need(w.org_id, 'logistique', 'logistique');
  if w.status <> 'attribuee' then raise exception 'Cette arme n’est attribuée à personne.'; end if;
  update public.weapon_loans set back_at = now(), back_by = me.name, note = coalesce(left(nullif(trim(coalesce(p_note, '')), ''), 200), note) where weapon_id = w.id and back_at is null;
  update public.weapons set status = 'armurerie', holder_id = null where id = w.id;
end $$;

create or replace function public.weapon_status(p_weapon uuid, p_status text, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare w public.weapons; me public.members;
begin
  select * into w from public.weapons where id = p_weapon for update;
  if w.id is null then raise exception 'Arme inconnue.'; end if;
  me := public.need(w.org_id, 'logistique', 'logistique');
  if p_status not in ('armurerie', 'maintenance', 'perdue', 'detruite') then raise exception 'Statut inconnu.'; end if;
  if w.status = 'attribuee' and p_status <> 'perdue' then raise exception 'Fais d’abord rendre l’arme.'; end if;
  update public.weapon_loans set back_at = now(), back_by = me.name, note = left('Perdue · ' || coalesce(trim(p_note), ''), 200) where weapon_id = w.id and back_at is null and p_status = 'perdue';
  update public.weapons set status = p_status, holder_id = null, notes = coalesce(left(nullif(trim(coalesce(p_note, '')), ''), 300), notes) where id = w.id;
  insert into public.journal (org_id, who, what) values (w.org_id, me.name, 'Armurerie : ' || w.serial || ' → ' || p_status);
end $$;

create or replace function public.save_equipment(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'logistique', 'logistique'); eid uuid;
begin
  if p_id is null then
    insert into public.equipment (org_id, name, kind) values (p_org, trim(coalesce(p ->> 'name', '')), coalesce(nullif(p ->> 'kind', ''), 'autre')) returning id into eid;
  else
    select id into eid from public.equipment where id = p_id and org_id = p_org;
    if eid is null then raise exception 'Article inconnu.'; end if;
  end if;
  update public.equipment set name = trim(coalesce(p ->> 'name', name)), kind = coalesce(nullif(p ->> 'kind', ''), kind), unit = coalesce(nullif(trim(coalesce(p ->> 'unit', '')), ''), unit),
    alert_below = nullif(p ->> 'alert_below', '')::integer where id = eid;
  return eid;
exception
  when unique_violation then raise exception 'Cet article existe déjà.';
  when check_violation then raise exception 'Nom de 2 à 60 caractères, type connu, unité de 20 caractères au plus, seuil positif.';
end $$;

-- une sortie (quantité négative) ou une entrée ; le stock ne descend jamais sous zéro
create or replace function public.move_equipment(p_item uuid, p_qty integer, p_member uuid, p_note text) returns integer
language plpgsql security definer set search_path = '' as $$
declare e public.equipment; me public.members; left_qty integer;
begin
  select * into e from public.equipment where id = p_item for update;
  if e.id is null then raise exception 'Article inconnu.'; end if;
  me := public.need(e.org_id, 'logistique', 'logistique');
  if coalesce(p_qty, 0) = 0 or abs(p_qty) > 100000 then raise exception 'Indique une quantité.'; end if;
  if p_member is not null and not exists (select 1 from public.members where id = p_member and org_id = e.org_id) then raise exception 'Agent inconnu.'; end if;
  if e.qty + p_qty < 0 then raise exception 'Stock insuffisant : il reste % %.', e.qty, e.unit; end if;
  update public.equipment set qty = qty + p_qty where id = e.id returning qty into left_qty;
  insert into public.equipment_moves (org_id, item_id, member_id, qty, note, by_name) values (e.org_id, e.id, p_member, p_qty, left(nullif(trim(coalesce(p_note, '')), ''), 200), me.name);
  return left_qty;
end $$;

create or replace function public.fleet_save(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'logistique', 'logistique'); vid uuid; v_min uuid := nullif(p ->> 'min_grade_id', '')::uuid; v_q uuid := nullif(p ->> 'qualification_id', '')::uuid;
begin
  if v_min is not null and not exists (select 1 from public.grades where id = v_min and org_id = p_org) then raise exception 'Grade inconnu.'; end if;
  if v_q is not null and not exists (select 1 from public.qualifications where id = v_q and org_id = p_org) then raise exception 'Qualification inconnue.'; end if;
  if p_id is null then
    insert into public.fleet (org_id, model, plate, kind) values (p_org, trim(coalesce(p ->> 'model', '')), upper(trim(coalesce(p ->> 'plate', ''))), coalesce(nullif(p ->> 'kind', ''), 'marque'))
      returning id into vid;
  else
    select id into vid from public.fleet where id = p_id and org_id = p_org;
    if vid is null then raise exception 'Véhicule inconnu.'; end if;
  end if;
  update public.fleet set model = trim(coalesce(p ->> 'model', model)), plate = upper(trim(coalesce(p ->> 'plate', plate))), kind = coalesce(nullif(p ->> 'kind', ''), kind),
    min_grade_id = v_min, qualification_id = v_q, notes = nullif(trim(coalesce(p ->> 'notes', '')), '') where id = vid;
  return vid;
exception
  when unique_violation then raise exception 'Cette plaque existe déjà dans le parc.';
  when check_violation then raise exception 'Modèle de 2 à 60 caractères, plaque de 2 à 10 lettres ou chiffres, type connu.';
end $$;

-- prendre un véhicule : le sien s'il est réservé, sinon libre et autorisé (blindé réservé au SOG)
create or replace function public.fleet_take(p_vehicle uuid, p_purpose text, p_until timestamptz) returns bigint
language plpgsql security definer set search_path = '' as $$
declare v public.fleet; me public.members; why text; other public.fleet_trips; mine public.fleet_trips; tid bigint;
begin
  select * into v from public.fleet where id = p_vehicle for update;
  if v.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(v.org_id, 'logistique', null);
  if v.state in ('reparation', 'hors_service') then raise exception 'Ce véhicule est %.', case v.state when 'reparation' then 'en réparation' else 'hors service' end; end if;
  if exists (select 1 from public.fleet_trips where vehicle_id = v.id and status = 'sortie') then raise exception 'Ce véhicule est déjà sorti.'; end if;
  why := public.gear_check(me.id, v.min_grade_id, v.qualification_id, v.kind = 'blinde');
  if why is not null then raise exception 'Tu ne peux pas prendre ce véhicule : %.', why; end if;
  select * into other from public.fleet_trips where vehicle_id = v.id and status = 'reservee' and member_id <> me.id and starts_at <= now() + interval '15 minutes' and coalesce(ends_at, starts_at + interval '4 hours') > now() limit 1;
  if other.id is not null then raise exception 'Réservé par % jusqu’à %.', (select name from public.members where id = other.member_id), to_char(coalesce(other.ends_at, other.starts_at + interval '4 hours') at time zone 'Europe/Paris', 'HH24:MI'); end if;
  select * into mine from public.fleet_trips where vehicle_id = v.id and status = 'reservee' and member_id = me.id and starts_at <= now() + interval '1 hour' and coalesce(ends_at, starts_at + interval '4 hours') > now()
    order by starts_at limit 1;
  if mine.id is not null then
    update public.fleet_trips set status = 'sortie', out_at = now(), purpose = coalesce(left(nullif(trim(coalesce(p_purpose, '')), ''), 120), purpose) where id = mine.id returning id into tid;
  else
    insert into public.fleet_trips (org_id, vehicle_id, member_id, status, starts_at, ends_at, out_at, purpose, by_name)
      values (v.org_id, v.id, me.id, 'sortie', now(), p_until, now(), left(nullif(trim(coalesce(p_purpose, '')), ''), 120), me.name) returning id into tid;
  end if;
  return tid;
end $$;

create or replace function public.fleet_book(p_vehicle uuid, p_from timestamptz, p_to timestamptz, p_purpose text) returns bigint
language plpgsql security definer set search_path = '' as $$
declare v public.fleet; me public.members; why text; tid bigint;
begin
  select * into v from public.fleet where id = p_vehicle for update;
  if v.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(v.org_id, 'logistique', null);
  if v.state = 'hors_service' then raise exception 'Ce véhicule est hors service.'; end if;
  if p_from is null or p_to is null or p_to <= p_from then raise exception 'Vérifie le début et la fin de la réservation.'; end if;
  if p_from < now() - interval '10 minutes' or p_to > now() + interval '14 days' or p_to - p_from > interval '24 hours' then raise exception 'Une réservation commence maintenant ou plus tard, dure 24 heures au plus, dans les 14 jours.'; end if;
  why := public.gear_check(me.id, v.min_grade_id, v.qualification_id, v.kind = 'blinde');
  if why is not null then raise exception 'Tu ne peux pas réserver ce véhicule : %.', why; end if;
  if exists (select 1 from public.fleet_trips where vehicle_id = v.id and status = 'reservee' and tstzrange(starts_at, coalesce(ends_at, starts_at + interval '4 hours')) && tstzrange(p_from, p_to)) then
    raise exception 'Ce véhicule est déjà réservé sur ce créneau.';
  end if;
  insert into public.fleet_trips (org_id, vehicle_id, member_id, status, starts_at, ends_at, purpose, by_name)
    values (v.org_id, v.id, me.id, 'reservee', p_from, p_to, left(nullif(trim(coalesce(p_purpose, '')), ''), 120), me.name) returning id into tid;
  return tid;
end $$;

create or replace function public.fleet_return(p_trip bigint, p_damage text) returns void
language plpgsql security definer set search_path = '' as $$
declare t public.fleet_trips; me public.members;
begin
  select * into t from public.fleet_trips where id = p_trip for update;
  if t.id is null then raise exception 'Sortie inconnue.'; end if;
  me := public.need(t.org_id, 'logistique', null);
  if t.member_id <> me.id and not public.has_cap(t.org_id, 'logistique') then raise exception 'Seul le conducteur ou la logistique rend ce véhicule.'; end if;
  if t.status <> 'sortie' then raise exception 'Ce véhicule n’est pas sorti.'; end if;
  update public.fleet_trips set status = 'rendue', back_at = now() where id = t.id;
  if length(trim(coalesce(p_damage, ''))) >= 3 then
    insert into public.fleet_damage (org_id, vehicle_id, member_id, description, by_name) values (t.org_id, t.vehicle_id, me.id, left(trim(p_damage), 500), me.name);
    update public.fleet set state = 'abime' where id = t.vehicle_id and state = 'ok';
  end if;
end $$;

create or replace function public.fleet_cancel(p_trip bigint) returns void
language plpgsql security definer set search_path = '' as $$
declare t public.fleet_trips; me public.members;
begin
  select * into t from public.fleet_trips where id = p_trip for update;
  if t.id is null then raise exception 'Réservation inconnue.'; end if;
  me := public.need(t.org_id, 'logistique', null);
  if t.member_id <> me.id and not public.has_cap(t.org_id, 'logistique') then raise exception 'Seul l’auteur ou la logistique annule cette réservation.'; end if;
  if t.status <> 'reservee' then raise exception 'Cette réservation n’est plus active.'; end if;
  update public.fleet_trips set status = 'annulee' where id = t.id;
end $$;

create or replace function public.fleet_report_damage(p_vehicle uuid, p_text text) returns void
language plpgsql security definer set search_path = '' as $$
declare v public.fleet; me public.members;
begin
  select * into v from public.fleet where id = p_vehicle;
  if v.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(v.org_id, 'logistique', null);
  if length(trim(coalesce(p_text, ''))) < 3 then raise exception 'Décris les dégâts.'; end if;
  insert into public.fleet_damage (org_id, vehicle_id, member_id, description, by_name) values (v.org_id, v.id, me.id, left(trim(p_text), 500), me.name);
  update public.fleet set state = 'abime' where id = v.id and state = 'ok';
  perform public.alert_members(v.org_id, array(select x from unnest(public.cap_members(v.org_id, 'logistique')) x where x <> me.id), 'logistique',
    'Dégâts signalés sur ' || v.model || ' (' || v.plate || ')', '/logistique');
end $$;

-- état du véhicule ; remis en état, les dégâts en cours sont marqués réparés
create or replace function public.fleet_state(p_vehicle uuid, p_state text) returns void
language plpgsql security definer set search_path = '' as $$
declare v public.fleet; me public.members;
begin
  select * into v from public.fleet where id = p_vehicle for update;
  if v.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(v.org_id, 'logistique', 'logistique');
  if p_state not in ('ok', 'abime', 'reparation', 'hors_service') then raise exception 'État inconnu.'; end if;
  update public.fleet set state = p_state where id = v.id;
  if p_state = 'ok' then update public.fleet_damage set repaired_at = now(), repaired_by = me.name where vehicle_id = v.id and repaired_at is null; end if;
end $$;

-- ---------------------------------------------------------------- statistiques
-- tableau des agents sur une période : arrestations, heures, rapports, dossiers résolus, sorties SOG, score
create or replace function public.score_board(o uuid, t0 timestamptz) returns json
language sql stable security definer set search_path = '' as $$
  select coalesce(json_agg(a order by a.score desc, a.name), '[]') from (
    select m.id, m.name, m.callsign,
      (select u.code from public.member_units mu join public.units u on u.id = mu.unit_id where mu.member_id = m.id and mu.main limit 1) as unit,
      x.arrests, x.hours, x.reports, x.refused, x.cases_closed, x.sog, x.arrests * 3 + x.reports + x.cases_closed * 2 + floor(x.hours)::integer as score
    from public.members m, lateral (select
      (select count(*) from public.reports r where r.author_id = m.id and r.kind = 'arrestation' and r.status in ('soumis', 'valide') and r.submitted_at >= t0)::integer as arrests,
      round(coalesce((select sum(extract(epoch from coalesce(s.ended_at, now()) - greatest(s.started_at, t0))) from public.shifts s
        where s.member_id = m.id and coalesce(s.ended_at, now()) > t0), 0) / 3600, 1) as hours,
      (select count(*) from public.reports r where r.author_id = m.id and r.status in ('soumis', 'valide') and r.submitted_at >= t0)::integer as reports,
      (select count(*) from public.reports r where r.author_id = m.id and r.status = 'a_corriger')::integer as refused,
      (select count(*) from public.cases c where c.lead_id = m.id and c.status in ('arrete', 'rendu', 'decede') and c.closed_at >= t0)::integer as cases_closed,
      (select count(*) from public.sog_missions x where x.org_id = o and x.status <> 'annulee' and x.activated_at >= t0
        and x.roles @> jsonb_build_array(jsonb_build_object('member_id', m.id)))::integer as sog) x
    where m.org_id = o and m.active) a
$$;

create or replace function public.mdt_stats(p_org uuid, p_weeks integer) returns json
language plpgsql stable security definer set search_path = '' as $$
declare n integer := least(greatest(coalesce(p_weeks, 4), 1), 26); w0 date; t0 timestamptz;
begin
  perform public.need(p_org, 'statistiques', null);
  w0 := public.week_of(now()) - 7 * (n - 1);
  t0 := (w0::timestamp at time zone 'Europe/Paris');
  return json_build_object('from', t0, 'weeks', n,
    'series', (select json_agg(json_build_object('week', w.d,
        'arrests', (select count(*) from public.reports r where r.org_id = p_org and r.kind = 'arrestation' and r.status in ('soumis', 'valide') and r.submitted_at >= b.t0 and r.submitted_at < b.t1),
        'hours', round(coalesce((select sum(extract(epoch from least(coalesce(s.ended_at, now()), b.t1) - greatest(s.started_at, b.t0))) from public.shifts s
          where s.org_id = p_org and s.started_at < b.t1 and coalesce(s.ended_at, now()) > b.t0), 0) / 3600, 1),
        'reports', (select count(*) from public.reports r where r.org_id = p_org and r.status in ('soumis', 'valide') and r.submitted_at >= b.t0 and r.submitted_at < b.t1),
        'sog', (select count(*) from public.sog_missions x where x.org_id = p_org and x.status <> 'annulee' and x.activated_at >= b.t0 and x.activated_at < b.t1),
        'transfers', (select count(*) from public.transfers x where x.org_id = p_org and x.status <> 'annule' and x.depart_at >= b.t0 and x.depart_at < b.t1),
        'incidents', (select count(*) from public.reports r where r.org_id = p_org and r.kind in ('incident', 'force') and r.status in ('soumis', 'valide') and r.submitted_at >= b.t0 and r.submitted_at < b.t1)
          + (select count(*) from public.transfers x where x.org_id = p_org and x.status = 'incident' and x.depart_at >= b.t0 and x.depart_at < b.t1)) order by w.d)
      from (select w0 + 7 * i as d from generate_series(0, n - 1) i) w, lateral public.week_bounds(w.d) b),
    'agents', public.score_board(p_org, t0),
    'week_best', public.score_board(p_org, now() - interval '7 days') -> 0,
    'month_best', public.score_board(p_org, now() - interval '30 days') -> 0,
    'cases', (select json_build_object('total', count(*), 'open', count(*) filter (where status in ('ouvert', 'en_cours', 'localise')),
        'resolved', count(*) filter (where status in ('arrete', 'rendu', 'decede')), 'abandoned', count(*) filter (where status = 'abandonne'),
        'opened_period', count(*) filter (where opened_at >= t0), 'resolved_period', count(*) filter (where status in ('arrete', 'rendu', 'decede') and closed_at >= t0))
      from public.cases where org_id = p_org),
    'late', (select coalesce(json_agg(json_build_object('id', r.id, 'num', r.num, 'title', case when r.status = 'brouillon' then null else r.title end, 'author', r.author_name,
        'status', r.status, 'since', coalesce(r.reviewed_at, r.submitted_at, r.created_at)) order by coalesce(r.reviewed_at, r.submitted_at, r.created_at)), '[]')
      from public.reports r where r.org_id = p_org and ((r.status = 'brouillon' and r.created_at < now() - interval '48 hours')
        or (r.status = 'soumis' and r.submitted_at < now() - interval '48 hours') or r.status = 'a_corriger')));
end $$;

-- ---------------------------------------------------------------- promotions : proposition avec les statistiques de l'agent, conditions vérifiées
create or replace function public.agent_stats(p_member uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'arrests', (select count(*) from public.reports r where r.author_id = m.id and r.kind = 'arrestation' and r.status in ('soumis', 'valide')),
    'arrests_30', (select count(*) from public.reports r where r.author_id = m.id and r.kind = 'arrestation' and r.status in ('soumis', 'valide') and r.submitted_at > now() - interval '30 days'),
    'reports', (select count(*) from public.reports r where r.author_id = m.id and r.status = 'valide'),
    'reports_refused', (select count(*) from public.reports r where r.author_id = m.id and r.status = 'a_corriger'),
    'cases_closed', (select count(*) from public.cases c where c.lead_id = m.id and c.status in ('arrete', 'rendu', 'decede')),
    'hours_30', round(coalesce((select sum(extract(epoch from coalesce(s.ended_at, now()) - greatest(s.started_at, now() - interval '30 days'))) from public.shifts s
      where s.member_id = m.id and coalesce(s.ended_at, now()) > now() - interval '30 days'), 0) / 3600, 1),
    'sog', (select count(*) from public.sog_missions x where x.org_id = m.org_id and x.roles @> jsonb_build_array(jsonb_build_object('member_id', m.id))),
    'transfers', (select count(*) from public.transfers t where t.leader_id = m.id or exists (select 1 from public.transfer_agents a where a.transfer_id = t.id and a.member_id = m.id)),
    'sanctions', (select count(*) from public.discipline d where d.member_id = m.id),
    'modules', (select count(*) from public.training_progress tp where tp.member_id = m.id),
    'days_in_grade', floor(extract(epoch from now() - m.rank_since) / 86400),
    'days_in_service', floor(extract(epoch from now() - m.created_at) / 86400))
  from public.members m where m.id = p_member
$$;

create table public.promotions (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  from_grade_id uuid references public.grades (id) on delete set null,
  to_grade_id uuid not null references public.grades (id) on delete cascade,
  reason text not null check (length(reason) between 10 and 2000),
  stats jsonb not null default '{}',
  status text not null default 'proposee' check (status in ('proposee', 'acceptee', 'refusee')),
  proposed_by_id uuid references public.members (id) on delete set null,
  proposed_by text not null,
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  decided_by text,
  decision_note text check (length(decision_note) <= 300)
);
create index promotions_org on public.promotions (org_id, created_at desc);
create index promotions_member on public.promotions (member_id);
create index promotions_from on public.promotions (from_grade_id);
create index promotions_to on public.promotions (to_grade_id);
create index promotions_by on public.promotions (proposed_by_id);
create unique index promotions_one_open on public.promotions (member_id) where status = 'proposee';

create or replace function public.propose_promotion(p_member uuid, p_grade uuid, p_reason text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members; g public.grades; cur public.grades; pid uuid;
begin
  select * into mem from public.members where id = p_member and active;
  if mem.id is null then raise exception 'Agent inconnu.'; end if;
  me := public.need(mem.org_id, 'academie', null);
  if not (public.has_cap(mem.org_id, 'effectifs') or public.has_cap(mem.org_id, 'valider') or public.has_cap(mem.org_id, 'academie')) then raise exception 'Ton grade ne permet pas cette action.'; end if;
  if mem.id = me.id then raise exception 'Tu ne peux pas te proposer toi-même.'; end if;
  select * into cur from public.grades where id = mem.grade_id;
  select * into g from public.grades where id = p_grade and org_id = mem.org_id;
  if g.id is null then raise exception 'Grade inconnu.'; end if;
  if g.rank <= cur.rank then raise exception 'Choisis un grade plus haut que %.', cur.name; end if;
  if length(trim(coalesce(p_reason, ''))) < 10 then raise exception 'Explique pourquoi (10 caractères au moins).'; end if;
  insert into public.promotions (org_id, member_id, from_grade_id, to_grade_id, reason, stats, proposed_by_id, proposed_by)
    values (mem.org_id, mem.id, cur.id, g.id, left(trim(p_reason), 2000), public.agent_stats(mem.id), me.id, me.name) returning id into pid;
  perform public.alert_members(mem.org_id, array(select x from unnest(public.cap_members(mem.org_id, 'effectifs')) x where x <> me.id and x <> mem.id), 'academie',
    'Proposition de promotion : ' || mem.name || ' → ' || g.name, '/academie');
  return pid;
exception when unique_violation then raise exception 'Une proposition est déjà en attente pour %.', mem.name;
end $$;

-- accepter : ancienneté dans le grade et place libre vérifiées, puis le grade change partout
create or replace function public.decide_promotion(p_id uuid, p_ok boolean, p_note text, p_force boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare p public.promotions; me public.members; mem public.members; cur public.grades; g public.grades; days integer;
begin
  select * into p from public.promotions where id = p_id for update;
  if p.id is null then raise exception 'Proposition inconnue.'; end if;
  me := public.need(p.org_id, 'academie', 'effectifs');
  if p.status <> 'proposee' then raise exception 'Cette proposition est déjà décidée.'; end if;
  if p.member_id = me.id then raise exception 'Un autre responsable décide de ta promotion.'; end if;
  if p_ok then
    select * into mem from public.members where id = p.member_id;
    if not mem.active then raise exception '% a quitté le service.', mem.name; end if;
    select * into cur from public.grades where id = mem.grade_id;
    select * into g from public.grades where id = p.to_grade_id;
    if g.rank <= cur.rank then raise exception '% est déjà % : la proposition n’a plus d’objet.', mem.name, cur.name; end if;
    days := floor(extract(epoch from now() - mem.rank_since) / 86400);
    if cur.min_days is not null and days < cur.min_days and not (coalesce(p_force, false) and public.is_top(p.org_id)) then
      raise exception 'Ancienneté insuffisante : % jour(s) en %, % requis.', days, cur.name, cur.min_days;
    end if;
    perform public.promote_agent(mem.id, g.id, 'Proposition de ' || p.proposed_by || ' acceptée');
    perform public.alert_members(p.org_id, array[mem.id], 'academie', 'Félicitations : tu passes ' || g.name || '.', '/effectifs/' || mem.id);
  end if;
  update public.promotions set status = case when p_ok then 'acceptee' else 'refusee' end, decided_at = now(), decided_by = me.name,
    decision_note = left(nullif(trim(coalesce(p_note, '')), ''), 300) where id = p.id;
end $$;

-- ---------------------------------------------------------------- carte
create table public.map_marks (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  kind text not null check (kind in ('position', 'interpellation', 'territoire', 'lieu')),
  label text not null check (length(label) between 2 and 80),
  x numeric(6, 5) not null check (x between 0 and 1),
  y numeric(6, 5) not null check (y between 0 and 1),
  radius numeric(5, 4) check (radius between 0.005 and 0.3),
  person_id uuid references public.people (id) on delete cascade,
  gang_id uuid references public.gangs (id) on delete cascade,
  case_id uuid references public.cases (id) on delete set null,
  note text check (length(note) <= 300),
  at timestamptz not null default now(),
  by_member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index map_marks_org on public.map_marks (org_id, kind);
create index map_marks_person on public.map_marks (person_id);
create index map_marks_gang on public.map_marks (gang_id);
create index map_marks_case on public.map_marks (case_id);
create index map_marks_by on public.map_marks (by_member_id);
alter table public.safehouses
  add column map_x numeric(6, 5) check (map_x between 0 and 1),
  add column map_y numeric(6, 5) check (map_y between 0 and 1);

create or replace function public.save_mark(p_org uuid, p_id uuid, p jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'carte', null); mk public.map_marks; mid uuid; v_person uuid := nullif(p ->> 'person_id', '')::uuid;
  v_gang uuid := nullif(p ->> 'gang_id', '')::uuid; v_case uuid := nullif(p ->> 'case_id', '')::uuid; v_kind text := coalesce(nullif(p ->> 'kind', ''), 'lieu');
begin
  if v_person is not null and not exists (select 1 from public.people where id = v_person and org_id = p_org) then raise exception 'Individu inconnu.'; end if;
  if v_gang is not null and not exists (select 1 from public.gangs where id = v_gang and org_id = p_org) then raise exception 'Organisation inconnue.'; end if;
  if v_case is not null and not exists (select 1 from public.cases where id = v_case and org_id = p_org) then raise exception 'Dossier inconnu.'; end if;
  if v_kind = 'territoire' and v_gang is null then raise exception 'Un territoire appartient à une organisation.'; end if;
  if length(trim(coalesce(p ->> 'label', ''))) < 2 then raise exception 'Donne un nom au repère.'; end if;
  if p_id is null then
    insert into public.map_marks (org_id, kind, label, x, y, by_member_id, by_name) values (p_org, v_kind, trim(p ->> 'label'), (p ->> 'x')::numeric, (p ->> 'y')::numeric, me.id, me.name) returning id into mid;
  else
    select * into mk from public.map_marks where id = p_id and org_id = p_org;
    if mk.id is null then raise exception 'Repère inconnu.'; end if;
    if mk.by_member_id is distinct from me.id and not public.has_cap(p_org, 'dossiers') then raise exception 'Seul son auteur ou un enquêteur modifie ce repère.'; end if;
    mid := mk.id;
  end if;
  update public.map_marks set kind = v_kind, label = trim(p ->> 'label'), x = coalesce(nullif(p ->> 'x', '')::numeric, x), y = coalesce(nullif(p ->> 'y', '')::numeric, y),
    radius = case when v_kind = 'territoire' then coalesce(nullif(p ->> 'radius', '')::numeric, radius, 0.04) end, person_id = v_person, gang_id = v_gang, case_id = v_case,
    note = nullif(trim(coalesce(p ->> 'note', '')), ''), at = now() where id = mid;
  return mid;
exception
  when check_violation then raise exception 'Repère hors de la carte, nom de 2 à 80 caractères ou note trop longue.';
  when invalid_text_representation then raise exception 'Position invalide.';
end $$;

create or replace function public.delete_mark(p_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare mk public.map_marks; me public.members;
begin
  select * into mk from public.map_marks where id = p_id;
  if mk.id is null then raise exception 'Repère inconnu.'; end if;
  me := public.need(mk.org_id, 'carte', null);
  if mk.by_member_id is distinct from me.id and not public.has_cap(mk.org_id, 'dossiers') then raise exception 'Seul son auteur ou un enquêteur retire ce repère.'; end if;
  delete from public.map_marks where id = mk.id;
end $$;

-- les planques WITSEC sont placées depuis la carte, mais restent dans la table WITSEC (accès restreint)
create or replace function public.place_safehouse(p_id uuid, p_x numeric, p_y numeric) returns void
language plpgsql security definer set search_path = '' as $$
declare sh public.safehouses;
begin
  select * into sh from public.safehouses where id = p_id;
  if sh.id is null then raise exception 'Planque inconnue.'; end if;
  perform public.witsec_me(sh.org_id);
  update public.safehouses set map_x = p_x, map_y = p_y where id = sh.id;
exception when check_violation then raise exception 'Position hors de la carte.';
end $$;

-- ---------------------------------------------------------------- page publique du service : avis, candidature, plainte
create or replace function public.service_info(p_code text) returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object('code', o.code, 'name', o.name, 'kind', o.kind, 'logo', o.logo_path,
    'wanted', 'wanted' = any (o.modules), 'apply', 'academie' = any (o.modules) and o.recruiting, 'complain', 'affaires' = any (o.modules),
    'questions', coalesce((select t.body -> 'questions' from public.doc_templates t where t.org_id = o.id and t.kind = 'candidature'), public.default_questions()))
  from public.orgs o where o.code = lower(trim(p_code)) and o.sector = 'service_public'
$$;

-- ---------------------------------------------------------------- sauvegarde : le Director exporte toutes les données du service (sauf les vrais noms WITSEC)
create or replace function public.export_service(p_org uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'effectifs', null); t text; part jsonb; res jsonb := '{}';
  tables text[] := array['grades', 'members', 'units', 'member_units', 'qualifications', 'member_qualifications', 'career_events', 'blacklist', 'absences', 'agenda', 'agenda_staff', 'shifts',
    'people', 'gangs', 'person_links', 'plates', 'plate_log', 'cases', 'case_agents', 'case_log', 'case_files', 'warrants', 'wanted', 'wanted_history', 'tips', 'announcements',
    'reports', 'penal_code', 'evidence', 'evidence_log', 'ia_cases', 'ia_log', 'complaints', 'discipline', 'transfers', 'transfer_agents', 'transfer_routes', 'detentions',
    'hearings', 'hearing_posts', 'protectees', 'threats', 'safehouses', 'witnesses', 'witsec_events', 'witsec_audit', 'sog_operators', 'sog_tests', 'sog_missions',
    'informants', 'informant_payments', 'intel', 'applications', 'application_notes', 'training_modules', 'training_progress', 'library_docs', 'exams', 'exam_keys', 'exam_attempts',
    'field_evals', 'promotions', 'weapons', 'weapon_loans', 'equipment', 'equipment_moves', 'fleet', 'fleet_trips', 'fleet_damage', 'map_marks', 'doc_templates', 'journal'];
begin
  if not public.is_top(p_org) then raise exception 'Seul le Director exporte les données du service.'; end if;
  foreach t in array tables loop
    execute format('select coalesce(jsonb_agg(to_jsonb(x)), ''[]'') from public.%I x where x.org_id = $1', t) into part using p_org;
    res := res || jsonb_build_object(t, part);
  end loop;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Export de sauvegarde des données du service');
  return jsonb_build_object('service', (select to_jsonb(o) from public.orgs o where o.id = p_org), 'exported_at', now(), 'by', me.name, 'tables', res);
end $$;

-- ---------------------------------------------------------------- un service créé : nouveaux accès, parcours de formation, codes radio et premier examen
create or replace function public.seed_academy(o uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare radio uuid; eid uuid;
begin
  insert into public.training_modules (org_id, name, description, sort) values
    (o, 'Accueil et règlement', 'Organisation du service, grades, unités, règlement intérieur.', 1),
    (o, 'Procédures d’interpellation', 'Contrôle, menottage, lecture des droits, fouille, transport.', 2),
    (o, 'Code pénal et rapports', 'Infractions, peines, rédaction d’un rapport d’arrestation.', 3),
    (o, 'Radio et indicatifs', 'Codes 10, indicatifs, discipline radio.', 4),
    (o, 'Conduite et convoi', 'Conduite d’urgence, escorte et transfert de détenus.', 5),
    (o, 'Armes et sécurité', 'Maniement, règles d’engagement, usage de la force.', 6),
    (o, 'Recherche de fugitifs', 'Dossier IOD, enquête de terrain, mandat, interpellation.', 7)
  on conflict (org_id, name) do nothing;
  select id into radio from public.training_modules where org_id = o and name = 'Radio et indicatifs';
  insert into public.library_docs (org_id, category, title, body, sort, by_name) values (o, 'radio', 'Codes radio', concat_ws(E'\n',
    '10-1 · Mauvaise réception', '10-2 · Bonne réception', '10-3 · Silence radio', '10-4 · Reçu, compris', '10-5 · Pause', '10-6 · Occupé', '10-7 · Hors service',
    '10-8 · En service, disponible', '10-9 · Répétez', '10-10 · Négatif', '10-11 · Contrôle routier', '10-15 · Suspect interpellé', '10-20 · Position',
    '10-22 · Annulez', '10-23 · Arrivé sur les lieux', '10-28 · Vérification de plaque', '10-29 · Vérification d’identité', '10-31 · Crime en cours',
    '10-32 · Demande de renfort', '10-38 · Contrôle d’un véhicule', '10-50 · Accident de la route', '10-52 · Ambulance demandée', '10-70 · Poursuite à pied',
    '10-80 · Poursuite en véhicule', '10-99 · Agent en détresse, toutes les unités', '', 'Code 2 · Intervention urgente, sans sirène', 'Code 3 · Gyrophares et sirène',
    'Code 4 · Situation sous contrôle'), 1, 'Windy Desk');
  insert into public.exams (org_id, title, pass_pct, module_id, questions, by_name) values (o, 'Codes radio', 80, radio, 5, 'Windy Desk') returning id into eid;
  insert into public.exam_keys (exam_id, org_id, questions) values (eid, o, '[
    {"q": "Que signifie 10-4 ?", "choices": ["Reçu, compris", "Agent en détresse", "Demande de renfort", "Hors service"], "answer": 0},
    {"q": "Quel code pour demander des renforts ?", "choices": ["10-20", "10-32", "10-8", "10-99"], "answer": 1},
    {"q": "Que signifie 10-99 ?", "choices": ["Fin de service", "Vérification de plaque", "Agent en détresse, toutes les unités", "Poursuite à pied"], "answer": 2},
    {"q": "Quel code pour donner ta position ?", "choices": ["10-20", "10-6", "10-50", "10-28"], "answer": 0},
    {"q": "Que veut dire « Code 4 » ?", "choices": ["Situation sous contrôle", "Gyrophares et sirène", "Agent blessé", "Retour au poste"], "answer": 0}]'::jsonb);
end $$;

do $$ declare o uuid; begin for o in select id from public.orgs where sector = 'service_public' loop perform public.seed_academy(o); end loop; end $$;

create or replace function public.create_service(p_code text, p_name text, p_owner_name text, p_owner_login text, p_prefix text, p_modules text[], p_weeks integer) returns json
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_editeur(); oid uuid; top uuid; mid uuid; tok text;
begin
  insert into public.orgs (code, name, kind, owner_name, sector, id_prefix, modules, licence_until)
    values (lower(trim(p_code)), trim(p_name), 'US Marshals Service', trim(p_owner_name), 'service_public', upper(trim(coalesce(p_prefix, 'USMS'))),
      public.check_modules(p_modules), now() + make_interval(days => 7 * greatest(1, p_weeks)))
    returning id into oid;
  insert into public.grades (org_id, name, rank, caps, badge, max_seats) values (oid, 'Director', 100, public.all_caps(), '★★★★', 1) returning id into top;
  insert into public.grades (org_id, name, rank, caps, badge, max_seats, min_days) values
    (oid, 'Chief Deputy', 90, array['effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces', 'equipe', 'transferts', 'tribunal', 'sog', 'renseignement', 'academie', 'logistique'], '★★★', 1, 21),
    (oid, 'Supervisory', 80, array['effectifs', 'valider', 'dossiers', 'mandats', 'wanted', 'annonces', 'transferts', 'tribunal', 'renseignement', 'academie'], '★★', 2, 21),
    (oid, 'Inspector', 70, array['dossiers', 'mandats', 'wanted', 'renseignement'], '★', 4, 14),
    (oid, 'Deputy', 60, array['dossiers'], 'DUSM', 12, 14),
    (oid, 'Recrue', 40, '{}', 'R', 6, 7);
  insert into public.units (org_id, code, name, restricted) values
    (oid, 'IOD', 'Investigative Operations Division · fugitifs', false),
    (oid, 'POD', 'Prisoner Operations Division · transferts', false),
    (oid, 'JSD', 'Judicial Security Division · tribunal', false),
    (oid, 'SOG', 'Special Operations Group', false),
    (oid, 'WITSEC', 'Witness Security · protection des témoins', true);
  insert into public.qualifications (org_id, name) values (oid, 'SOG'), (oid, 'FTO'), (oid, 'Sniper'), (oid, 'Négociateur'), (oid, 'Conducteur de convoi');
  perform public.seed_penal(oid);
  perform public.seed_academy(oid);
  insert into public.members (org_id, name, login, grade_id, callsign) values (oid, trim(p_owner_name), lower(trim(p_owner_login)), top, 'Marshal-1') returning id into mid;
  insert into public.career_events (org_id, member_id, kind, text, by_name) values (oid, mid, 'recrutement', 'Prise de fonction · Director', who);
  insert into public.invitations (member_id) values (mid) returning token into tok;
  insert into public.journal (org_id, who, what) values (oid, who, 'Service créé par l’éditeur');
  return json_build_object('org_id', oid, 'token', tok);
exception when unique_violation then
  raise exception 'L’adresse « % » est déjà prise.', lower(trim(p_code));
end $$;

-- l'espace de chacun : suspendu ou non, accès aux Affaires internes, logo du service
create or replace function public.my_spaces() returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object(
    'role', (select r.role from public.platform_roles r where r.user_id = auth.uid()),
    'display_name', (select r.display_name from public.platform_roles r where r.user_id = auth.uid()),
    'orgs', coalesce((
      select json_agg(json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'is_association', o.is_association, 'sector', o.sector,
        'id_prefix', o.id_prefix, 'modules', o.modules, 'licence_until', o.licence_until, 'member_id', m.id, 'member_name', m.name, 'grade', g.name,
        'signature_name', m.signature_name, 'signature_style', m.signature_style, 'witsec', o.sector = 'service_public' and public.witsec_access(o.id),
        'ia', o.sector = 'service_public' and public.ia_access(o.id), 'logo', o.logo_path,
        'suspended_until', case when m.suspended_until > now() then m.suspended_until end,
        'caps', case when g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o.id) then public.all_caps() else g.caps end) order by o.name)
      from public.members m join public.orgs o on o.id = m.org_id join public.grades g on g.id = m.grade_id
      where m.user_id = auth.uid() and m.active), '[]'::json))
$$;

-- ---------------------------------------------------------------- stockage : le logo du service, public comme les affiches
create policy windy_avis_logo on storage.objects for insert to authenticated
  with check (bucket_id = 'avis' and split_part(name, '/', 2) = 'logo' and public.cap_of_path(name, 'equipe'));

-- ---------------------------------------------------------------- règles de lecture
alter table public.doc_templates enable row level security;
alter table public.applications enable row level security;
alter table public.application_notes enable row level security;
alter table public.training_modules enable row level security;
alter table public.training_progress enable row level security;
alter table public.library_docs enable row level security;
alter table public.exams enable row level security;
alter table public.exam_keys enable row level security;
alter table public.exam_attempts enable row level security;
alter table public.field_evals enable row level security;
alter table public.promotions enable row level security;
alter table public.complaints enable row level security;
alter table public.ia_log enable row level security;
alter table public.discipline enable row level security;
alter table public.weapons enable row level security;
alter table public.weapon_loans enable row level security;
alter table public.equipment enable row level security;
alter table public.equipment_moves enable row level security;
alter table public.fleet enable row level security;
alter table public.fleet_trips enable row level security;
alter table public.fleet_damage enable row level security;
alter table public.map_marks enable row level security;

create policy doc_templates_read on public.doc_templates for select to authenticated using (public.is_member(org_id));
create policy applications_read on public.applications for select to authenticated using (public.has_cap(org_id, 'academie') or public.has_cap(org_id, 'effectifs'));
create policy application_notes_read on public.application_notes for select to authenticated using (public.has_cap(org_id, 'academie') or public.has_cap(org_id, 'effectifs'));
create policy training_modules_read on public.training_modules for select to authenticated using (public.is_member(org_id));
create policy training_progress_read on public.training_progress for select to authenticated using (public.is_member(org_id));
create policy library_docs_read on public.library_docs for select to authenticated using (public.is_member(org_id));
create policy exams_read on public.exams for select to authenticated using (public.is_member(org_id));
create policy exam_keys_read on public.exam_keys for select to authenticated using (public.has_cap(org_id, 'academie'));
create policy exam_attempts_read on public.exam_attempts for select to authenticated using (public.has_cap(org_id, 'academie') or member_id = (public.caller_member(org_id)).id);
create policy field_evals_read on public.field_evals for select to authenticated
  using (public.has_cap(org_id, 'academie') or member_id = (public.caller_member(org_id)).id or fto_id = (public.caller_member(org_id)).id);
create policy promotions_read on public.promotions for select to authenticated using (public.has_cap(org_id, 'effectifs') or proposed_by_id = (public.caller_member(org_id)).id);
create policy complaints_read on public.complaints for select to authenticated using (by_member_id = (public.caller_member(org_id)).id
  or (public.ia_access(org_id) and (against_member_id is null or against_member_id is distinct from (public.caller_member(org_id)).id or public.is_top(org_id))));
create policy ia_log_read on public.ia_log for select to authenticated using (public.ia_access(org_id) and exists (select 1 from public.ia_cases c where c.id = case_id));
create policy discipline_read on public.discipline for select to authenticated
  using (public.ia_access(org_id) or public.has_cap(org_id, 'effectifs') or member_id = (public.caller_member(org_id)).id);
create policy weapons_read on public.weapons for select to authenticated using (public.is_member(org_id));
create policy weapon_loans_read on public.weapon_loans for select to authenticated using (public.has_cap(org_id, 'logistique') or member_id = (public.caller_member(org_id)).id);
create policy equipment_read on public.equipment for select to authenticated using (public.is_member(org_id));
create policy equipment_moves_read on public.equipment_moves for select to authenticated using (public.has_cap(org_id, 'logistique') or member_id = (public.caller_member(org_id)).id);
create policy fleet_read on public.fleet for select to authenticated using (public.is_member(org_id));
create policy fleet_trips_read on public.fleet_trips for select to authenticated using (public.is_member(org_id));
create policy fleet_damage_read on public.fleet_damage for select to authenticated using (public.is_member(org_id));
create policy map_marks_read on public.map_marks for select to authenticated using (public.is_member(org_id));
-- les dossiers des Affaires internes : la personne désignée y entre aussi ; un dossier qui vise un agent lui reste fermé
drop policy ia_cases_read on public.ia_cases;
create policy ia_cases_read on public.ia_cases for select to authenticated
  using (public.ia_access(org_id) and (member_id is null or member_id is distinct from (public.caller_member(org_id)).id or public.is_top(org_id)));
create policy journal_ia_read on public.journal for select to authenticated using (public.ia_access(org_id));

-- ---------------------------------------------------------------- droits
do $$
declare
  t text;
  f regprocedure;
  tables text[] := array['doc_templates', 'applications', 'application_notes', 'training_modules', 'training_progress', 'library_docs', 'exams', 'exam_keys', 'exam_attempts',
    'field_evals', 'promotions', 'complaints', 'ia_log', 'discipline', 'weapons', 'weapon_loans', 'equipment', 'equipment_moves', 'fleet', 'fleet_trips', 'fleet_damage', 'map_marks'];
  internal text[] := array['next_num', 'cap_members', 'is_fto', 'ia_members', 'ia_case_alert', 'ia_witness_lost', 'ia_case_of', 'gear_check', 'score_board', 'agent_stats',
    'default_questions', 'seed_academy'];
  api text[] := array['is_top', 'ia_access', 'witsec_access', 'in_convoy', 'set_grade_meta', 'save_service_settings', 'save_template', 'export_service', 'set_ia_officer',
    'application_note', 'schedule_interview', 'refuse_application', 'accept_application', 'save_training_module', 'set_fto', 'validate_module', 'save_doc', 'delete_doc',
    'save_exam', 'exam_paper', 'submit_exam', 'add_field_eval', 'plan_training', 'training_join', 'mark_presence', 'propose_promotion', 'decide_promotion',
    'file_complaint', 'complaint_status', 'open_ia_case', 'ia_update', 'ia_note', 'ia_close', 'apply_sanction', 'lift_suspension',
    'save_weapon', 'issue_weapon', 'return_weapon', 'weapon_status', 'save_equipment', 'move_equipment', 'fleet_save', 'fleet_take', 'fleet_book', 'fleet_return',
    'fleet_cancel', 'fleet_report_damage', 'fleet_state', 'mdt_stats', 'save_mark', 'delete_mark', 'place_safehouse', 'create_service', 'my_spaces'];
  public_api text[] := array['send_application', 'send_complaint', 'service_info'];
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
  for f in select p.oid::regprocedure from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = any (public_api) loop
    execute format('grant execute on function %s to anon, authenticated', f);
  end loop;
end $$;
