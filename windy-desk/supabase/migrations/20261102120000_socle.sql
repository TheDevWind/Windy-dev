-- =====================================================================
-- Comptoir · socle
-- Entreprises, grades et accès, membres et invitations, comptabilité,
-- déclarations, journal, contrôles du fisc, barème, rôles de la plateforme.
--
-- Principes :
--   · chaque table a la sécurité par ligne (RLS) activée ;
--   · aucune écriture directe : tout passe par des fonctions qui vérifient le grade ;
--   · la comptabilité est immuable (ni modification ni suppression) : le fisc peut s'y fier ;
--   · les fonctions « security definer » fixent search_path = '' et nomment tout en entier.
-- =====================================================================

-- ---------------------------------------------------------------- catalogue et barème
create table public.modules (
  id text primary key,
  name text not null,
  price integer not null check (price >= 0),
  description text not null,
  sort integer not null
);

insert into public.modules (id, name, price, description, sort) values
  ('stock', 'Stock', 250, 'Articles, quantités, seuils d’alerte, emplacements et historique des mouvements.', 10),
  ('fabrication', 'Fabrication', 300, 'Recettes, ordres de fabrication et coût de revient. Les ingrédients sont pris dans le stock.', 20),
  ('recolte', 'Récolte', 200, 'Saisie des récoltes par employé, classement de la semaine et primes au rendement.', 30),
  ('caisse', 'Caisse', 350, 'Carte, encaissement rapide et tickets. Chaque vente décompte ses ingrédients du stock.', 40),
  ('factures', 'Factures', 200, 'Factures entre entreprises, échéances et relances. Une facture payée entre en comptabilité.', 50),
  ('service', 'Service et paie', 250, 'Prise et fin de service, heures de la semaine, salaires et paie en un clic.', 60),
  ('dons', 'Dons et distributions', 100, 'Dons en argent ou en nature, distributions aux bénéficiaires, bénévoles. Tarif association.', 70),
  ('employes', 'Employés', 200, 'Fiches du personnel : contrat, téléphone, permis, embauche et licenciement, avertissements internes.', 80),
  ('vehicules', 'Véhicules', 200, 'La flotte : qui a quel véhicule, carburant, dégâts, réparations et assurance.', 90),
  ('evenements', 'Événements', 150, 'Des événements temporaires avec leur propre vue : dates, équipe, stock réservé, objectif et bilan.', 100);

-- une seule ligne : le barème du serveur, modifiable par le fisc
create table public.tax_rules (
  id smallint primary key default 1 check (id = 1),
  rate numeric(5, 4) not null default 0.10 check (rate between 0 and 1),
  late_fee numeric(5, 4) not null default 0.05 check (late_fee between 0 and 1),
  deadline_dow smallint not null default 1 check (deadline_dow between 1 and 7),   -- 1 = lundi
  deadline_hour smallint not null default 20 check (deadline_hour between 0 and 23),
  associations_exempt boolean not null default true,
  updated_at timestamptz not null default now(),
  updated_by text
);
insert into public.tax_rules default values;

-- ---------------------------------------------------------------- plateforme
create table public.platform_roles (
  user_id uuid primary key references auth.users (id) on delete cascade,
  role text not null check (role in ('editeur', 'fisc')),
  display_name text not null
);

-- liens à usage unique qui donnent un rôle de plateforme (premier éditeur, contrôleurs du fisc)
create table public.platform_claims (
  token text primary key default (replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '')),
  role text not null check (role in ('editeur', 'fisc')),
  display_name text not null,
  expires_at timestamptz not null default now() + interval '7 days',
  used_at timestamptz,
  used_by uuid
);

-- ---------------------------------------------------------------- entreprises
create table public.orgs (
  id uuid primary key default gen_random_uuid(),
  code text not null unique check (code ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length(code) between 3 and 40),
  name text not null check (length(name) between 2 and 60),
  kind text not null default 'Entreprise' check (length(kind) between 2 and 40),
  owner_name text not null,
  is_association boolean not null default false,
  modules text[] not null default '{}',
  licence_until timestamptz not null default now() + interval '7 days',
  discord_login boolean not null default true,
  password_login boolean not null default true,
  created_at timestamptz not null default now()
);

create table public.grades (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 2 and 30),
  rank integer not null,
  caps text[] not null default '{}',
  unique (org_id, name)
);
create index grades_org on public.grades (org_id);

create table public.members (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  user_id uuid references auth.users (id) on delete set null,
  name text not null check (length(name) between 2 and 60),
  login text not null check (login ~ '^[a-z0-9]+([._-][a-z0-9]+)*$' and length(login) between 3 and 40),
  grade_id uuid not null references public.grades (id),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  last_seen timestamptz,
  unique (org_id, login),
  unique (org_id, user_id)
);
create index members_user on public.members (user_id);
create index members_grade on public.members (grade_id);

create table public.invitations (
  token text primary key default (replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '')),
  member_id uuid not null references public.members (id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '48 hours',
  used_at timestamptz
);
create index invitations_member on public.invitations (member_id);

-- ---------------------------------------------------------------- comptabilité (immuable)
create table public.ledger (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  kind text not null check (kind in ('in', 'out')),
  category text not null check (category in ('Ventes', 'Factures', 'Dons', 'Subventions', 'Achats', 'Salaires', 'Loyer', 'Licence', 'Entretien', 'Impôts', 'Divers')),
  label text not null check (length(label) between 3 and 140),
  amount numeric(14, 2) not null check (amount > 0 and amount < 100000000),
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index ledger_org_at on public.ledger (org_id, at);
create index ledger_member on public.ledger (member_id);

create table public.declarations (
  org_id uuid not null references public.orgs (id) on delete cascade,
  week_start date not null check (extract(isodow from week_start) = 1),
  revenue numeric(14, 2) not null check (revenue >= 0),
  charges numeric(14, 2) not null check (charges >= 0),
  tax numeric(14, 2) not null check (tax >= 0),
  late_fee numeric(14, 2) not null default 0 check (late_fee >= 0),
  declared_at timestamptz not null default now(),
  declared_by text not null,
  primary key (org_id, week_start)
);

create table public.journal (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  who text not null,
  what text not null,
  by_fisc boolean not null default false
);
create index journal_org_at on public.journal (org_id, at desc);

create table public.controls (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  opened_at timestamptz not null default now(),
  closed_at timestamptz,
  reason text not null check (length(reason) between 5 and 300),
  fine numeric(14, 2) not null default 0 check (fine >= 0),
  note text,
  inspector text not null
);
create index controls_org on public.controls (org_id);

-- devis de l'éditeur
create table public.quotes (
  id uuid primary key default gen_random_uuid(),
  num text not null unique,
  client text not null,
  contact text not null default '',
  kind text not null default '',
  modules text[] not null default '{}',
  weeks integer not null default 4 check (weeks between 1 and 52),
  discount integer not null default 0 check (discount between 0 and 50),
  status text not null default 'brouillon' check (status in ('brouillon', 'envoye', 'accepte', 'refuse')),
  note text,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------- outils d'accès (utilisés par les règles de sécurité)
create or replace function public.platform_role() returns text
language sql stable security definer set search_path = '' as $$
  select r.role from public.platform_roles r where r.user_id = auth.uid()
$$;

create or replace function public.is_member(o uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.members m where m.org_id = o and m.user_id = auth.uid() and m.active)
$$;

-- vrai si la personne connectée a cet accès dans l'entreprise ; le grade le plus haut a toujours tout
create or replace function public.has_cap(o uuid, cap text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.members m join public.grades g on g.id = m.grade_id
    where m.org_id = o and m.user_id = auth.uid() and m.active
      and (cap = any (g.caps) or g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o)))
$$;

create or replace function public.caller_member(o uuid) returns public.members
language sql stable security definer set search_path = '' as $$
  select m.* from public.members m where m.org_id = o and m.user_id = auth.uid() and m.active
$$;

create or replace function public.caller_name() returns text
language sql stable security definer set search_path = '' as $$
  select coalesce((select r.display_name from public.platform_roles r where r.user_id = auth.uid()), 'Inconnu')
$$;

create or replace function public.all_caps() returns text[]
language sql immutable as $$
  select array['encaisser', 'stock', 'fabriquer', 'compta', 'declarer', 'paie', 'equipe', 'vehicules', 'evenements']
$$;

-- début de semaine (lundi), à l'heure de Paris
create or replace function public.week_of(t timestamptz) returns date
language sql stable as $$
  select (date_trunc('week', t at time zone 'Europe/Paris'))::date
$$;

create or replace function public.week_bounds(w date, out t0 timestamptz, out t1 timestamptz)
language sql stable as $$
  select (w::timestamp at time zone 'Europe/Paris'), ((w + 7)::timestamp at time zone 'Europe/Paris')
$$;

-- ---------------------------------------------------------------- règles de sécurité (lecture seulement)
alter table public.modules enable row level security;
alter table public.tax_rules enable row level security;
alter table public.platform_roles enable row level security;
alter table public.platform_claims enable row level security;
alter table public.orgs enable row level security;
alter table public.grades enable row level security;
alter table public.members enable row level security;
alter table public.invitations enable row level security;
alter table public.ledger enable row level security;
alter table public.declarations enable row level security;
alter table public.journal enable row level security;
alter table public.controls enable row level security;
alter table public.quotes enable row level security;

create policy modules_read on public.modules for select to anon, authenticated using (true);
create policy tax_rules_read on public.tax_rules for select to anon, authenticated using (true);
create policy platform_roles_self on public.platform_roles for select to authenticated using (user_id = (select auth.uid()));
create policy orgs_read on public.orgs for select to authenticated using (public.is_member(id) or (select public.platform_role()) is not null);
create policy grades_read on public.grades for select to authenticated using (public.is_member(org_id) or (select public.platform_role()) = 'editeur');
create policy members_read on public.members for select to authenticated using (public.is_member(org_id) or (select public.platform_role()) = 'editeur');
create policy ledger_read on public.ledger for select to authenticated using (public.has_cap(org_id, 'compta') or (select public.platform_role()) = 'fisc');
create policy declarations_read on public.declarations for select to authenticated using (public.has_cap(org_id, 'compta') or (select public.platform_role()) = 'fisc');
create policy journal_read on public.journal for select to authenticated using (public.has_cap(org_id, 'equipe'));
create policy controls_read on public.controls for select to authenticated using (public.is_member(org_id) or (select public.platform_role()) = 'fisc');
create policy quotes_editeur on public.quotes for all to authenticated
  using ((select public.platform_role()) = 'editeur') with check ((select public.platform_role()) = 'editeur');
-- platform_claims et invitations : aucune règle, donc aucun accès direct (seulement par les fonctions)

-- ---------------------------------------------------------------- fonctions publiques (sans connexion)
create or replace function public.org_public(p_code text) returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'is_association', o.is_association,
    'discord_login', o.discord_login, 'password_login', o.password_login, 'modules', o.modules)
  from public.orgs o where o.code = lower(trim(p_code))
$$;

create or replace function public.invitation_info(p_token text) returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object('org_code', o.code, 'org_name', o.name, 'member_name', m.name, 'login', m.login, 'grade', g.name,
    'expired', i.expires_at < now(), 'used', i.used_at is not null, 'discord_login', o.discord_login, 'password_login', o.password_login)
  from public.invitations i join public.members m on m.id = i.member_id join public.orgs o on o.id = m.org_id join public.grades g on g.id = m.grade_id
  where i.token = p_token
$$;

-- ---------------------------------------------------------------- connexion
create or replace function public.accept_invitation(p_token text) returns json
language plpgsql security definer set search_path = '' as $$
declare
  inv public.invitations;
  mem public.members;
  org_code text;
begin
  if auth.uid() is null then raise exception 'Connexion requise.'; end if;
  select * into inv from public.invitations where token = p_token for update;
  if inv.token is null then raise exception 'Ce lien d’activation n’existe pas.'; end if;
  if inv.used_at is not null then raise exception 'Ce lien a déjà servi. Demande un nouveau lien à ton patron.'; end if;
  if inv.expires_at < now() then raise exception 'Ce lien a expiré. Demande un nouveau lien à ton patron.'; end if;
  select * into mem from public.members where id = inv.member_id for update;
  if exists (select 1 from public.members where org_id = mem.org_id and user_id = auth.uid() and id <> mem.id) then
    raise exception 'Ce compte fait déjà partie de cette entreprise.';
  end if;
  update public.members set user_id = auth.uid(), active = true, last_seen = now() where id = mem.id;
  update public.invitations set used_at = now() where token = p_token;
  insert into public.journal (org_id, who, what) values (mem.org_id, mem.name, 'Compte activé');
  select code into org_code from public.orgs where id = mem.org_id;
  return json_build_object('org_code', org_code);
end $$;

create or replace function public.claim_platform_role(p_token text) returns text
language plpgsql security definer set search_path = '' as $$
declare c public.platform_claims;
begin
  if auth.uid() is null then raise exception 'Connexion requise.'; end if;
  select * into c from public.platform_claims where token = p_token for update;
  if c.token is null or c.used_at is not null or c.expires_at < now() then raise exception 'Ce lien n’est plus valable.'; end if;
  insert into public.platform_roles (user_id, role, display_name) values (auth.uid(), c.role, c.display_name)
    on conflict (user_id) do update set role = excluded.role, display_name = excluded.display_name;
  update public.platform_claims set used_at = now(), used_by = auth.uid() where token = p_token;
  return c.role;
end $$;

-- tout ce que la personne connectée peut ouvrir : son rôle de plateforme et ses entreprises
create or replace function public.my_spaces() returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object(
    'role', (select r.role from public.platform_roles r where r.user_id = auth.uid()),
    'display_name', (select r.display_name from public.platform_roles r where r.user_id = auth.uid()),
    'orgs', coalesce((
      select json_agg(json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'is_association', o.is_association,
        'modules', o.modules, 'licence_until', o.licence_until, 'member_id', m.id, 'member_name', m.name, 'grade', g.name,
        'caps', case when g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o.id) then public.all_caps() else g.caps end) order by o.name)
      from public.members m join public.orgs o on o.id = m.org_id join public.grades g on g.id = m.grade_id
      where m.user_id = auth.uid() and m.active), '[]'::json))
$$;

create or replace function public.touch_member(p_org uuid) returns void
language sql security definer set search_path = '' as $$
  update public.members set last_seen = now() where org_id = p_org and user_id = auth.uid()
$$;

-- ---------------------------------------------------------------- équipe, grades, connexion de l'entreprise
create or replace function public.invite_member(p_org uuid, p_name text, p_login text, p_grade uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare
  me public.members;
  my_rank integer;
  target_rank integer;
  new_id uuid;
  tok text;
begin
  if not public.has_cap(p_org, 'equipe') then raise exception 'Ton grade ne permet pas de gérer l’équipe.'; end if;
  me := public.caller_member(p_org);
  select rank into my_rank from public.grades where id = me.grade_id;
  select rank into target_rank from public.grades where id = p_grade and org_id = p_org;
  if target_rank is null then raise exception 'Grade inconnu.'; end if;
  if target_rank > my_rank then raise exception 'Tu ne peux pas donner un grade plus haut que le tien.'; end if;
  insert into public.members (org_id, name, login, grade_id) values (p_org, trim(p_name), lower(trim(p_login)), p_grade) returning id into new_id;
  insert into public.invitations (member_id) values (new_id) returning token into tok;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Nouveau compte : ' || trim(p_name));
  return tok;
exception when unique_violation then
  raise exception 'L’identifiant « % » est déjà pris dans cette entreprise.', lower(trim(p_login));
end $$;

-- nouveau lien d'activation ; l'ancien compte relié est détaché (perte de mot de passe, changement de Discord)
create or replace function public.renew_invitation(p_member uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare
  mem public.members;
  me public.members;
  tok text;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null then raise exception 'Compte inconnu.'; end if;
  if not (public.has_cap(mem.org_id, 'equipe') or public.platform_role() = 'editeur') then raise exception 'Accès refusé.'; end if;
  me := public.caller_member(mem.org_id);
  if me.id = mem.id then raise exception 'Tu ne peux pas réinitialiser ton propre compte.'; end if;
  update public.invitations set expires_at = now() where member_id = mem.id and used_at is null;
  update public.members set user_id = null where id = mem.id;
  insert into public.invitations (member_id) values (mem.id) returning token into tok;
  insert into public.journal (org_id, who, what) values (mem.org_id, coalesce(me.name, public.caller_name()), 'Nouveau lien d’activation pour ' || mem.name);
  return tok;
end $$;

create or replace function public.set_member(p_member uuid, p_grade uuid, p_active boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare
  mem public.members;
  me public.members;
  my_rank integer;
  cur_rank integer;
  new_rank integer;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null or not public.has_cap(mem.org_id, 'equipe') then raise exception 'Accès refusé.'; end if;
  me := public.caller_member(mem.org_id);
  if me.id = mem.id then raise exception 'Tu ne peux pas modifier ton propre compte.'; end if;
  select rank into my_rank from public.grades where id = me.grade_id;
  select rank into cur_rank from public.grades where id = mem.grade_id;
  select rank into new_rank from public.grades where id = p_grade and org_id = mem.org_id;
  if new_rank is null then raise exception 'Grade inconnu.'; end if;
  if cur_rank > my_rank or new_rank > my_rank then raise exception 'Tu ne peux pas gérer un grade plus haut que le tien.'; end if;
  update public.members set grade_id = p_grade, active = p_active where id = mem.id;
  insert into public.journal (org_id, who, what) values (mem.org_id, me.name,
    mem.name || ' : ' || (select name from public.grades where id = p_grade) || case when p_active then '' else ' (compte désactivé)' end);
end $$;

create or replace function public.save_grade(p_org uuid, p_id uuid, p_name text, p_caps text[]) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  gid uuid;
  bad text;
  me public.members;
begin
  if not public.has_cap(p_org, 'equipe') then raise exception 'Accès refusé.'; end if;
  select c into bad from unnest(p_caps) c where not c = any (public.all_caps()) limit 1;
  if bad is not null then raise exception 'Accès inconnu : %', bad; end if;
  me := public.caller_member(p_org);
  if p_id is null then
    insert into public.grades (org_id, name, rank, caps)
      values (p_org, trim(p_name), coalesce((select min(rank) from public.grades where org_id = p_org), 100) - 10, p_caps) returning id into gid;
  else
    update public.grades set name = trim(p_name), caps = p_caps where id = p_id and org_id = p_org returning id into gid;
    if gid is null then raise exception 'Grade inconnu.'; end if;
  end if;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Grade « ' || trim(p_name) || ' » enregistré');
  return gid;
end $$;

create or replace function public.set_org_login(p_org uuid, p_discord boolean, p_password boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not public.has_cap(p_org, 'equipe') then raise exception 'Accès refusé.'; end if;
  if not (p_discord or p_password) then raise exception 'Garde au moins une façon de se connecter.'; end if;
  update public.orgs set discord_login = p_discord, password_login = p_password where id = p_org;
  insert into public.journal (org_id, who, what) values (p_org, (public.caller_member(p_org)).name, 'Connexion : Discord ' || case when p_discord then 'oui' else 'non' end || ', mot de passe ' || case when p_password then 'oui' else 'non' end);
end $$;

-- ---------------------------------------------------------------- comptabilité et déclarations
create or replace function public.add_entry(p_org uuid, p_kind text, p_category text, p_label text, p_amount numeric) returns bigint
language plpgsql security definer set search_path = '' as $$
declare
  me public.members;
  new_id bigint;
begin
  if not public.has_cap(p_org, 'compta') then raise exception 'Ton grade ne permet pas de toucher à la comptabilité.'; end if;
  if p_category = 'Impôts' then raise exception 'Les impôts sont enregistrés par la déclaration ou par le fisc.'; end if;
  me := public.caller_member(p_org);
  insert into public.ledger (org_id, kind, category, label, amount, member_id, by_name)
    values (p_org, p_kind, p_category, trim(p_label), round(p_amount, 2), me.id, me.name) returning id into new_id;
  return new_id;
end $$;

-- chiffres d'une semaine, tels que la comptabilité les donne, et ce que la déclaration coûterait
create or replace function public.week_book(p_org uuid, p_week date) returns json
language plpgsql stable security definer set search_path = '' as $$
declare
  b record;
  r public.tax_rules;
  o public.orgs;
  w date := public.week_of((p_week::timestamp at time zone 'Europe/Paris'));
  revenue numeric; other_in numeric; charges numeric; profit numeric; tax numeric; deadline timestamptz; days_late integer; d public.declarations;
begin
  if not (public.has_cap(p_org, 'compta') or public.has_cap(p_org, 'declarer') or public.platform_role() = 'fisc') then raise exception 'Accès refusé.'; end if;
  select * into o from public.orgs where id = p_org;
  select * into r from public.tax_rules where id = 1;
  select * into b from public.week_bounds(w);
  select coalesce(sum(amount) filter (where kind = 'in' and category in ('Ventes', 'Factures')), 0),
         coalesce(sum(amount) filter (where kind = 'in' and category not in ('Ventes', 'Factures')), 0),
         coalesce(sum(amount) filter (where kind = 'out' and category not in ('Impôts', 'Divers')), 0)
    into revenue, other_in, charges
    from public.ledger where org_id = p_org and at >= b.t0 and at < b.t1;
  profit := revenue - charges;
  tax := case when o.is_association and r.associations_exempt then 0 else round(greatest(0, profit) * r.rate, 2) end;
  deadline := ((w + 7 + (r.deadline_dow - 1))::timestamp + make_interval(hours => r.deadline_hour)) at time zone 'Europe/Paris';
  days_late := greatest(0, ceil(extract(epoch from (now() - deadline)) / 86400))::integer;
  select * into d from public.declarations where org_id = p_org and week_start = w;
  return json_build_object('week_start', w, 'week', extract(week from w)::integer, 'revenue', revenue, 'other_in', other_in, 'charges', charges, 'profit', profit,
    'tax', tax, 'exempt', o.is_association and r.associations_exempt, 'rate', r.rate, 'deadline', deadline, 'days_late', days_late,
    'late_fee', round(tax * r.late_fee * days_late, 2), 'closed', now() >= b.t1,
    'declaration', case when d.org_id is null then null else row_to_json(d) end);
end $$;

-- l'entreprise déclare ce qu'elle veut ; le fisc compare ensuite à la comptabilité
create or replace function public.declare_week(p_org uuid, p_week date, p_revenue numeric, p_charges numeric) returns json
language plpgsql security definer set search_path = '' as $$
declare
  me public.members;
  bk json;
  r public.tax_rules;
  o public.orgs;
  w date := public.week_of((p_week::timestamp at time zone 'Europe/Paris'));
  tax numeric; fee numeric; days_late integer;
begin
  if not public.has_cap(p_org, 'declarer') then raise exception 'Ton grade ne permet pas de déclarer au fisc.'; end if;
  if p_revenue < 0 or p_charges < 0 then raise exception 'Les montants ne peuvent pas être négatifs.'; end if;
  if w >= public.week_of(now()) then raise exception 'On déclare une semaine une fois qu’elle est terminée.'; end if;
  if exists (select 1 from public.declarations where org_id = p_org and week_start = w) then raise exception 'Cette semaine est déjà déclarée.'; end if;
  me := public.caller_member(p_org);
  select * into o from public.orgs where id = p_org;
  select * into r from public.tax_rules where id = 1;
  bk := public.week_book(p_org, w);
  days_late := (bk ->> 'days_late')::integer;
  tax := case when o.is_association and r.associations_exempt then 0 else round(greatest(0, p_revenue - p_charges) * r.rate, 2) end;
  fee := round(tax * r.late_fee * days_late, 2);
  insert into public.declarations (org_id, week_start, revenue, charges, tax, late_fee, declared_by)
    values (p_org, w, round(p_revenue, 2), round(p_charges, 2), tax, fee, me.name);
  if tax + fee > 0 then
    insert into public.ledger (org_id, kind, category, label, amount, member_id, by_name)
      values (p_org, 'out', 'Impôts', 'Impôt de la semaine ' || extract(week from w)::integer || case when fee > 0 then ' (dont ' || fee || ' $ de pénalité de retard)' else '' end, tax + fee, me.id, me.name);
  end if;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Déclaration de la semaine ' || extract(week from w)::integer || ' · ' || (tax + fee) || ' $');
  return json_build_object('tax', tax, 'late_fee', fee);
end $$;

-- ---------------------------------------------------------------- fisc
create or replace function public.require_fisc() returns text
language plpgsql stable security definer set search_path = '' as $$
begin
  if public.platform_role() is distinct from 'fisc' then raise exception 'Réservé au contrôleur du fisc.'; end if;
  return public.caller_name();
end $$;

-- vue d'ensemble : pour chaque entreprise, la dernière semaine et les signaux à regarder
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
    from public.orgs o) x;
  return res;
end $$;

create or replace function public.fisc_open(p_org uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_fisc();
begin
  insert into public.journal (org_id, who, what, by_fisc) values (p_org, who, 'Consultation de la fiche par le fisc', true);
end $$;

create or replace function public.fisc_open_control(p_org uuid, p_reason text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_fisc(); cid uuid;
begin
  if exists (select 1 from public.controls where org_id = p_org and closed_at is null) then raise exception 'Un contrôle est déjà en cours.'; end if;
  insert into public.controls (org_id, reason, inspector) values (p_org, trim(p_reason), who) returning id into cid;
  insert into public.journal (org_id, who, what, by_fisc) values (p_org, who, 'Ouverture d’un contrôle fiscal : ' || trim(p_reason), true);
  return cid;
end $$;

create or replace function public.fisc_close_control(p_control uuid, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_fisc(); c public.controls;
begin
  update public.controls set closed_at = now(), note = coalesce(nullif(trim(p_note), ''), note) where id = p_control and closed_at is null returning * into c;
  if c.id is null then raise exception 'Ce contrôle est déjà clos.'; end if;
  insert into public.journal (org_id, who, what, by_fisc) values (c.org_id, who, 'Contrôle fiscal clos', true);
end $$;

create or replace function public.fisc_fine(p_org uuid, p_amount numeric, p_reason text, p_close boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_fisc(); cid uuid;
begin
  if p_amount <= 0 then raise exception 'Le montant doit être positif.'; end if;
  if length(trim(p_reason)) < 5 then raise exception 'Indique le motif du redressement.'; end if;
  select id into cid from public.controls where org_id = p_org and closed_at is null;
  if cid is null then
    insert into public.controls (org_id, reason, inspector) values (p_org, trim(p_reason), who) returning id into cid;
  end if;
  update public.controls set fine = fine + round(p_amount, 2), note = trim(p_reason), closed_at = case when p_close then now() else closed_at end where id = cid;
  insert into public.ledger (org_id, kind, category, label, amount, by_name) values (p_org, 'out', 'Impôts', 'Redressement fiscal · ' || trim(p_reason), round(p_amount, 2), 'Fisc · ' || who);
  insert into public.journal (org_id, who, what, by_fisc) values (p_org, who, 'Redressement de ' || round(p_amount, 2) || ' $ : ' || trim(p_reason), true);
end $$;

create or replace function public.set_tax_rules(p_rate numeric, p_late_fee numeric, p_deadline_dow integer, p_deadline_hour integer, p_assoc_exempt boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_fisc();
begin
  update public.tax_rules set rate = p_rate, late_fee = p_late_fee, deadline_dow = p_deadline_dow, deadline_hour = p_deadline_hour,
    associations_exempt = p_assoc_exempt, updated_at = now(), updated_by = who where id = 1;
end $$;

-- ---------------------------------------------------------------- éditeur (licences)
create or replace function public.require_editeur() returns text
language plpgsql stable security definer set search_path = '' as $$
begin
  if public.platform_role() is distinct from 'editeur' then raise exception 'Réservé à l’éditeur de Comptoir.'; end if;
  return public.caller_name();
end $$;

create or replace function public.check_modules(p_modules text[]) returns text[]
language plpgsql stable security definer set search_path = '' as $$
declare bad text;
begin
  select m into bad from unnest(p_modules) m where not exists (select 1 from public.modules x where x.id = m) limit 1;
  if bad is not null then raise exception 'Module inconnu : %', bad; end if;
  return (select coalesce(array_agg(distinct m), '{}') from unnest(p_modules) m);
end $$;

-- une entreprise cliente, ses grades de départ, le compte du patron et son lien d'activation
create or replace function public.create_org(p_code text, p_name text, p_kind text, p_owner_name text, p_owner_login text, p_is_association boolean, p_modules text[], p_weeks integer) returns json
language plpgsql security definer set search_path = '' as $$
declare
  who text := public.require_editeur();
  oid uuid; top uuid; mid uuid; tok text;
begin
  insert into public.orgs (code, name, kind, owner_name, is_association, modules, licence_until)
    values (lower(trim(p_code)), trim(p_name), trim(p_kind), trim(p_owner_name), p_is_association, public.check_modules(p_modules), now() + make_interval(days => 7 * greatest(1, p_weeks)))
    returning id into oid;
  if p_is_association then
    insert into public.grades (org_id, name, rank, caps) values (oid, 'Présidence', 100, public.all_caps()) returning id into top;
    insert into public.grades (org_id, name, rank, caps) values
      (oid, 'Bureau', 80, array['stock', 'compta', 'declarer', 'equipe', 'vehicules', 'evenements']),
      (oid, 'Bénévole', 50, array['stock', 'vehicules', 'evenements']);
  else
    insert into public.grades (org_id, name, rank, caps) values (oid, 'Patron', 100, public.all_caps()) returning id into top;
    insert into public.grades (org_id, name, rank, caps) values
      (oid, 'Gérant', 80, array['encaisser', 'stock', 'fabriquer', 'compta', 'paie', 'equipe', 'vehicules', 'evenements']),
      (oid, 'Employé', 50, array['encaisser', 'stock', 'fabriquer', 'vehicules', 'evenements']),
      (oid, 'Stagiaire', 20, array['encaisser', 'vehicules']);
  end if;
  insert into public.members (org_id, name, login, grade_id) values (oid, trim(p_owner_name), lower(trim(p_owner_login)), top) returning id into mid;
  insert into public.invitations (member_id) values (mid) returning token into tok;
  insert into public.journal (org_id, who, what) values (oid, who, 'Espace créé par l’éditeur');
  return json_build_object('org_id', oid, 'token', tok);
exception when unique_violation then
  raise exception 'L’adresse « % » est déjà prise.', lower(trim(p_code));
end $$;

create or replace function public.set_org_modules(p_org uuid, p_modules text[]) returns void
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_editeur();
begin
  update public.orgs set modules = public.check_modules(p_modules) where id = p_org;
  insert into public.journal (org_id, who, what) values (p_org, who, 'Modules : ' || coalesce(array_to_string(public.check_modules(p_modules), ', '), 'aucun'));
end $$;

create or replace function public.extend_licence(p_org uuid, p_days integer) returns timestamptz
language plpgsql security definer set search_path = '' as $$
declare who text := public.require_editeur(); until timestamptz;
begin
  if p_days not between 1 and 365 then raise exception 'Entre 1 et 365 jours.'; end if;
  update public.orgs set licence_until = greatest(licence_until, now()) + make_interval(days => p_days) where id = p_org returning licence_until into until;
  insert into public.journal (org_id, who, what) values (p_org, who, 'Licence prolongée de ' || p_days || ' jours');
  return until;
end $$;

create or replace function public.editor_overview() returns json
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_editeur();
  return (select coalesce(json_agg(json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'owner_name', o.owner_name, 'is_association', o.is_association,
    'modules', o.modules, 'licence_until', o.licence_until, 'created_at', o.created_at,
    'price', (select coalesce(sum(m.price), 0) from public.modules m where m.id = any (o.modules)),
    'accounts', (select count(*) from public.members x where x.org_id = o.id and x.user_id is not null and x.active),
    'pending', (select count(*) from public.members x where x.org_id = o.id and x.user_id is null and x.active)) order by o.name), '[]'::json)
  from public.orgs o);
end $$;

-- ---------------------------------------------------------------- droits : lecture par les règles, écriture par les fonctions
revoke all on all tables in schema public from anon, authenticated;
grant select on public.modules, public.tax_rules to anon, authenticated;
grant select on public.platform_roles, public.orgs, public.grades, public.members, public.ledger, public.declarations, public.journal, public.controls to authenticated;
grant select, insert, update, delete on public.quotes to authenticated;

revoke execute on all functions in schema public from public, anon, authenticated;
grant execute on function public.org_public(text), public.invitation_info(text) to anon, authenticated;
grant execute on function
  public.platform_role(), public.is_member(uuid), public.has_cap(uuid, text), public.all_caps(), public.week_of(timestamptz),
  public.accept_invitation(text), public.claim_platform_role(text), public.my_spaces(), public.touch_member(uuid),
  public.invite_member(uuid, text, text, uuid), public.renew_invitation(uuid), public.set_member(uuid, uuid, boolean),
  public.save_grade(uuid, uuid, text, text[]), public.set_org_login(uuid, boolean, boolean),
  public.add_entry(uuid, text, text, text, numeric), public.week_book(uuid, date), public.declare_week(uuid, date, numeric, numeric),
  public.fisc_overview(), public.fisc_open(uuid), public.fisc_open_control(uuid, text), public.fisc_close_control(uuid, text),
  public.fisc_fine(uuid, numeric, text, boolean), public.set_tax_rules(numeric, numeric, integer, integer, boolean),
  public.create_org(text, text, text, text, text, boolean, text[], integer), public.set_org_modules(uuid, text[]),
  public.extend_licence(uuid, integer), public.editor_overview()
  to authenticated;
