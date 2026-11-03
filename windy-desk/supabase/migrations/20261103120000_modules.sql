-- Windy Desk · les dix modules : stock, fabrication, récolte, caisse, factures, service et paie, dons,
-- employés, véhicules, événements. Même principe que le socle : lecture par les règles RLS,
-- écriture uniquement par des fonctions qui vérifient le module (offre de l'entreprise) et le grade.

-- ---------------------------------------------------------------- fiche du personnel (module Employés, Service et paie)
alter table public.members
  add column phone text check (length(phone) <= 20),
  add column contract text not null default 'CDI' check (length(contract) between 2 and 20),
  add column licences text[] not null default '{}',
  add column hired_at date not null default current_date,
  add column hourly_rate numeric(10, 2) not null default 0 check (hourly_rate between 0 and 100000),
  add column left_at timestamptz,
  add column left_reason text;

-- ---------------------------------------------------------------- tables
create table public.items (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 2 and 40),
  unit text not null default 'unité' check (length(unit) between 1 and 16),
  category text not null default 'Divers' check (length(category) between 2 and 30),
  place text not null default 'Réserve' check (length(place) between 2 and 30),
  qty numeric(14, 3) not null default 0 check (qty >= 0),
  threshold numeric(14, 3) not null default 0 check (threshold >= 0),
  cost numeric(12, 2) not null default 0 check (cost >= 0),          -- coût unitaire (valeur du stock, coût de revient)
  price numeric(12, 2) check (price > 0),                             -- prix de vente en caisse ; vide = pas à la carte
  harvest_bonus numeric(10, 2) check (harvest_bonus >= 0),            -- prime par unité récoltée ; vide = pas récoltable
  archived boolean not null default false,
  created_at timestamptz not null default now(),
  unique (org_id, name)
);
create index items_org on public.items (org_id);

create table public.stock_moves (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  item_id uuid not null references public.items (id) on delete cascade,
  at timestamptz not null default now(),
  delta numeric(14, 3) not null,
  reason text not null,
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index stock_moves_org_at on public.stock_moves (org_id, at desc);
create index stock_moves_item on public.stock_moves (item_id);

create table public.recipes (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  item_id uuid not null references public.items (id) on delete cascade,   -- le produit fabriqué
  yield numeric(14, 3) not null check (yield > 0),                          -- quantité produite par fournée
  minutes integer not null default 0 check (minutes between 0 and 10000),
  archived boolean not null default false,
  unique (org_id, item_id)
);
create table public.recipe_inputs (
  recipe_id uuid not null references public.recipes (id) on delete cascade,
  item_id uuid not null references public.items (id) on delete cascade,
  qty numeric(14, 3) not null check (qty > 0),
  primary key (recipe_id, item_id)
);
create index recipe_inputs_item on public.recipe_inputs (item_id);

create table public.productions (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  recipe_id uuid not null references public.recipes (id) on delete cascade,
  at timestamptz not null default now(),
  runs integer not null check (runs > 0),
  produced numeric(14, 3) not null,
  cost numeric(12, 2) not null default 0,
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index productions_org_at on public.productions (org_id, at desc);

create table public.harvests (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  member_id uuid references public.members (id) on delete set null,
  by_name text not null,
  item_id uuid not null references public.items (id) on delete cascade,
  qty numeric(14, 3) not null check (qty > 0),
  bonus numeric(12, 2) not null default 0,
  paid_at timestamptz
);
create index harvests_org_at on public.harvests (org_id, at desc);

create table public.events (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  name text not null check (length(name) between 3 and 60),
  place text not null default '' check (length(place) <= 80),
  kind text not null default 'ventes' check (kind in ('ventes', 'dons')),
  starts_at timestamptz not null,
  ends_at timestamptz not null check (ends_at > starts_at),
  goal numeric(12, 2) not null default 0 check (goal >= 0),
  budget numeric(12, 2) not null default 0 check (budget >= 0),
  note text check (length(note) <= 300),
  closed_at timestamptz,
  created_at timestamptz not null default now(),
  by_name text not null
);
create index events_org on public.events (org_id, starts_at);
create table public.event_staff (
  event_id uuid not null references public.events (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  primary key (event_id, member_id)
);
create index event_staff_member on public.event_staff (member_id);
create table public.event_stock (
  event_id uuid not null references public.events (id) on delete cascade,
  item_id uuid not null references public.items (id) on delete cascade,
  qty numeric(14, 3) not null check (qty > 0),
  primary key (event_id, item_id)
);
create index event_stock_item on public.event_stock (item_id);
create table public.event_tasks (
  id bigint generated always as identity primary key,
  event_id uuid not null references public.events (id) on delete cascade,
  label text not null check (length(label) between 2 and 80),
  done boolean not null default false,
  done_by text
);
create index event_tasks_event on public.event_tasks (event_id);

alter table public.ledger add column event_id uuid references public.events (id) on delete set null;
create index ledger_event on public.ledger (event_id);

create table public.sales (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  num integer not null,
  at timestamptz not null default now(),
  member_id uuid references public.members (id) on delete set null,
  by_name text not null,
  payment text not null check (payment in ('carte', 'especes')),
  total numeric(12, 2) not null check (total > 0),
  lines jsonb not null,
  event_id uuid references public.events (id) on delete set null,
  unique (org_id, num)
);
create index sales_org_at on public.sales (org_id, at desc);
create index sales_event on public.sales (event_id);

create table public.invoices (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  num text not null,
  client text not null check (length(client) between 2 and 60),
  client_org_id uuid references public.orgs (id) on delete set null,     -- client qui utilise aussi Windy Desk
  lines jsonb not null,
  total numeric(12, 2) not null check (total > 0),
  created_at timestamptz not null default now(),
  due_at timestamptz not null,
  status text not null default 'attente' check (status in ('attente', 'payee', 'annulee')),
  paid_at timestamptz,
  by_name text not null,
  unique (org_id, num)
);
create index invoices_org on public.invoices (org_id, created_at desc);
create index invoices_client on public.invoices (client_org_id);

create table public.shifts (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  paid_at timestamptz,
  check (ended_at is null or ended_at >= started_at)
);
create index shifts_org on public.shifts (org_id, started_at desc);
create index shifts_member on public.shifts (member_id);
create unique index shifts_one_open on public.shifts (member_id) where ended_at is null;

create table public.payroll_runs (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  total numeric(12, 2) not null,
  lines jsonb not null,
  by_name text not null
);
create index payroll_runs_org on public.payroll_runs (org_id, at desc);

create table public.donations (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  donor text not null check (length(donor) between 2 and 60),
  amount numeric(12, 2) check (amount > 0),
  item_id uuid references public.items (id) on delete set null,
  qty numeric(14, 3) check (qty > 0),
  note text check (length(note) <= 160),
  event_id uuid references public.events (id) on delete set null,
  by_name text not null,
  check ((amount is not null) <> (qty is not null))
);
create index donations_org on public.donations (org_id, at desc);
create index donations_item on public.donations (item_id);
create index donations_event on public.donations (event_id);

create table public.distributions (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  at timestamptz not null default now(),
  beneficiary text not null check (length(beneficiary) between 2 and 60),
  item_id uuid references public.items (id) on delete set null,
  qty numeric(14, 3) not null check (qty > 0),
  note text check (length(note) <= 160),
  by_name text not null
);
create index distributions_org on public.distributions (org_id, at desc);
create index distributions_item on public.distributions (item_id);

create table public.warnings (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  at timestamptz not null default now(),
  reason text not null check (length(reason) between 5 and 200),
  by_name text not null
);
create index warnings_member on public.warnings (member_id);
create index warnings_org on public.warnings (org_id);

create table public.staff_notes (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  member_id uuid not null references public.members (id) on delete cascade,
  at timestamptz not null default now(),
  text text not null check (length(text) between 2 and 300),
  by_name text not null
);
create index staff_notes_member on public.staff_notes (member_id);
create index staff_notes_org on public.staff_notes (org_id);

create table public.vehicles (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references public.orgs (id) on delete cascade,
  model text not null check (length(model) between 2 and 40),
  plate text not null check (length(plate) between 2 and 10),
  usage text not null default 'Utilitaire' check (length(usage) between 2 and 30),
  fuel integer not null default 100 check (fuel between 0 and 100),
  condition integer not null default 100 check (condition between 0 and 100),
  km integer not null default 0 check (km >= 0),
  status text not null default 'garage' check (status in ('garage', 'sortie', 'reparation')),
  driver_id uuid references public.members (id) on delete set null,
  out_since timestamptz,
  insured_until date,
  archived boolean not null default false,
  created_at timestamptz not null default now(),
  unique (org_id, plate)
);
create index vehicles_driver on public.vehicles (driver_id);

create table public.vehicle_logs (
  id bigint generated always as identity primary key,
  org_id uuid not null references public.orgs (id) on delete cascade,
  vehicle_id uuid not null references public.vehicles (id) on delete cascade,
  at timestamptz not null default now(),
  action text not null,
  note text,
  member_id uuid references public.members (id) on delete set null,
  by_name text not null
);
create index vehicle_logs_org on public.vehicle_logs (org_id, at desc);
create index vehicle_logs_vehicle on public.vehicle_logs (vehicle_id);
create index vehicle_logs_member on public.vehicle_logs (member_id);

-- ---------------------------------------------------------------- outils internes (jamais appelés directement par le site)
create or replace function public.fmt_qty(x numeric) returns text
language sql immutable set search_path = '' as $$ select replace(trim_scale(round(x, 3))::text, '.', ',') $$;

-- la personne connectée, si le module est dans l'offre et si son grade a l'accès demandé
create or replace function public.need(o uuid, m text, cap text) returns public.members
language plpgsql security definer set search_path = '' as $$
declare me public.members;
begin
  if not exists (select 1 from public.orgs x where x.id = o and m = any (x.modules)) then
    raise exception 'Le module « % » n’est pas inclus dans l’offre de cette entreprise.', coalesce((select name from public.modules where id = m), m);
  end if;
  me := public.caller_member(o);
  if me.id is null then raise exception 'Accès refusé.'; end if;
  if cap is not null and not public.has_cap(o, cap) then raise exception 'Ton grade ne permet pas cette action.'; end if;
  return me;
end $$;

-- une ligne de comptabilité
create or replace function public.book(o uuid, k text, cat text, lbl text, amt numeric, me public.members, ev uuid) returns void
language sql security definer set search_path = '' as $$
  insert into public.ledger (org_id, kind, category, label, amount, member_id, by_name, event_id)
  values (o, k, cat, left(lbl, 140), round(amt, 2), me.id, coalesce(me.name, 'Windy Desk'), ev)
$$;

-- un mouvement de stock (refusé s'il mettrait le stock en négatif)
create or replace function public.stock_add(o uuid, it uuid, d numeric, why text, me public.members) returns numeric
language plpgsql security definer set search_path = '' as $$
declare q numeric; n text; u text;
begin
  select i.qty, i.name, i.unit into q, n, u from public.items i where i.id = it and i.org_id = o for update;
  if q is null then raise exception 'Article inconnu.'; end if;
  if q + d < 0 then raise exception 'Stock insuffisant : il manque % % de « % ».', public.fmt_qty(-(q + d)), u, n; end if;
  update public.items set qty = q + d where id = it;
  insert into public.stock_moves (org_id, item_id, delta, reason, member_id, by_name) values (o, it, d, left(why, 140), me.id, coalesce(me.name, 'Windy Desk'));
  return q + d;
end $$;

-- l'événement en cours, s'il y en a un (les ventes et les dons s'y rattachent tout seuls)
create or replace function public.live_event(o uuid, k text) returns uuid
language sql stable security definer set search_path = '' as $$
  select e.id from public.events e where e.org_id = o and e.kind = k and e.closed_at is null and now() between e.starts_at and e.ends_at order by e.starts_at limit 1
$$;

-- ---------------------------------------------------------------- stock
create or replace function public.save_item(p_org uuid, p_id uuid, p_name text, p_unit text, p_category text, p_place text,
  p_threshold numeric, p_cost numeric, p_price numeric, p_bonus numeric) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members; iid uuid;
begin
  if not exists (select 1 from public.orgs x where x.id = p_org and x.modules && array['stock', 'caisse', 'fabrication', 'recolte', 'dons', 'factures', 'evenements']) then
    raise exception 'Aucun module de cette offre n’utilise d’articles.';
  end if;
  me := public.caller_member(p_org);
  if me.id is null or not public.has_cap(p_org, 'stock') then raise exception 'Ton grade ne permet pas de gérer les articles.'; end if;
  if p_id is null then
    insert into public.items (org_id, name, unit, category, place, threshold, cost, price, harvest_bonus)
      values (p_org, trim(p_name), coalesce(nullif(trim(p_unit), ''), 'unité'), coalesce(nullif(trim(p_category), ''), 'Divers'), coalesce(nullif(trim(p_place), ''), 'Réserve'),
        greatest(coalesce(p_threshold, 0), 0), greatest(coalesce(p_cost, 0), 0), nullif(p_price, 0), p_bonus) returning id into iid;
    insert into public.journal (org_id, who, what) values (p_org, me.name, 'Nouvel article : ' || trim(p_name));
  else
    update public.items set name = trim(p_name), unit = coalesce(nullif(trim(p_unit), ''), 'unité'), category = coalesce(nullif(trim(p_category), ''), 'Divers'),
      place = coalesce(nullif(trim(p_place), ''), 'Réserve'), threshold = greatest(coalesce(p_threshold, 0), 0), cost = greatest(coalesce(p_cost, 0), 0),
      price = nullif(p_price, 0), harvest_bonus = p_bonus
      where id = p_id and org_id = p_org returning id into iid;
    if iid is null then raise exception 'Article inconnu.'; end if;
  end if;
  return iid;
exception when unique_violation then
  raise exception 'Un article « % » existe déjà.', trim(p_name);
end $$;

create or replace function public.archive_item(p_item uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare it public.items; me public.members;
begin
  select * into it from public.items where id = p_item;
  if it.id is null then raise exception 'Article inconnu.'; end if;
  me := public.caller_member(it.org_id);
  if me.id is null or not public.has_cap(it.org_id, 'stock') then raise exception 'Ton grade ne permet pas de gérer les articles.'; end if;
  update public.items set archived = true where id = p_item;
  insert into public.journal (org_id, who, what) values (it.org_id, me.name, 'Article retiré : ' || it.name);
end $$;

-- entrée, sortie ou perte ; un achat payé est enregistré en dépense
create or replace function public.move_stock(p_org uuid, p_item uuid, p_delta numeric, p_reason text, p_paid numeric) returns numeric
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'stock', 'stock'); q numeric; it public.items;
begin
  if p_delta = 0 then raise exception 'Indique une quantité.'; end if;
  if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'Indique le motif du mouvement.'; end if;
  q := public.stock_add(p_org, p_item, p_delta, trim(p_reason), me);
  if coalesce(p_paid, 0) > 0 then
    if p_delta < 0 then raise exception 'Une sortie ne se paie pas.'; end if;
    select * into it from public.items where id = p_item;
    perform public.book(p_org, 'out', 'Achats', 'Achat · ' || public.fmt_qty(p_delta) || ' ' || it.unit || ' de ' || it.name, p_paid, me, null);
  end if;
  return q;
end $$;

-- ---------------------------------------------------------------- fabrication
create or replace function public.save_recipe(p_org uuid, p_item uuid, p_yield numeric, p_minutes integer, p_inputs jsonb) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'fabrication', 'fabriquer'); rid uuid; l jsonb; n int := 0;
begin
  if not exists (select 1 from public.items where id = p_item and org_id = p_org and not archived) then raise exception 'Produit inconnu.'; end if;
  if coalesce(p_yield, 0) <= 0 then raise exception 'Indique la quantité produite par fournée.'; end if;
  insert into public.recipes (org_id, item_id, yield, minutes, archived) values (p_org, p_item, p_yield, greatest(coalesce(p_minutes, 0), 0), false)
    on conflict (org_id, item_id) do update set yield = excluded.yield, minutes = excluded.minutes, archived = false
    returning id into rid;
  delete from public.recipe_inputs where recipe_id = rid;
  for l in select * from jsonb_array_elements(coalesce(p_inputs, '[]')) loop
    if (l->>'item')::uuid = p_item then raise exception 'Un produit ne peut pas être son propre ingrédient.'; end if;
    if not exists (select 1 from public.items where id = (l->>'item')::uuid and org_id = p_org and not archived) then raise exception 'Ingrédient inconnu.'; end if;
    if coalesce((l->>'qty')::numeric, 0) <= 0 then raise exception 'Chaque ingrédient doit avoir une quantité.'; end if;
    insert into public.recipe_inputs (recipe_id, item_id, qty) values (rid, (l->>'item')::uuid, (l->>'qty')::numeric)
      on conflict (recipe_id, item_id) do update set qty = public.recipe_inputs.qty + excluded.qty;
    n := n + 1;
  end loop;
  if n = 0 then raise exception 'Une recette a au moins un ingrédient.'; end if;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Recette enregistrée : ' || (select name from public.items where id = p_item));
  return rid;
end $$;

create or replace function public.archive_recipe(p_recipe uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare r public.recipes; me public.members;
begin
  select * into r from public.recipes where id = p_recipe;
  if r.id is null then raise exception 'Recette inconnue.'; end if;
  me := public.need(r.org_id, 'fabrication', 'fabriquer');
  update public.recipes set archived = true where id = p_recipe;
end $$;

-- fabrique « runs » fournées : les ingrédients sortent du stock, le produit y entre
create or replace function public.produce(p_recipe uuid, p_runs integer) returns numeric
language plpgsql security definer set search_path = '' as $$
declare r public.recipes; me public.members; inp record; total_cost numeric := 0; pname text; label text; made numeric;
begin
  select * into r from public.recipes where id = p_recipe and not archived;
  if r.id is null then raise exception 'Recette inconnue.'; end if;
  me := public.need(r.org_id, 'fabrication', 'fabriquer');
  if coalesce(p_runs, 0) < 1 or p_runs > 1000 then raise exception 'Entre 1 et 1000 fournées.'; end if;
  select name into pname from public.items where id = r.item_id;
  label := 'Fabrication · ' || pname || ' ×' || p_runs;
  for inp in select ri.item_id, ri.qty, i.cost from public.recipe_inputs ri join public.items i on i.id = ri.item_id where ri.recipe_id = r.id loop
    perform public.stock_add(r.org_id, inp.item_id, -inp.qty * p_runs, label, me);
    total_cost := total_cost + inp.qty * p_runs * inp.cost;
  end loop;
  made := r.yield * p_runs;
  perform public.stock_add(r.org_id, r.item_id, made, label, me);
  if total_cost > 0 then update public.items set cost = round(total_cost / made, 2) where id = r.item_id; end if;
  insert into public.productions (org_id, recipe_id, runs, produced, cost, member_id, by_name) values (r.org_id, r.id, p_runs, made, round(total_cost, 2), me.id, me.name);
  return made;
end $$;

-- ---------------------------------------------------------------- récolte
create or replace function public.add_harvest(p_org uuid, p_item uuid, p_qty numeric, p_member uuid) returns numeric
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'recolte', null); who public.members; it public.items; b numeric;
begin
  who := me;
  if p_member is not null and p_member <> me.id then
    if not public.has_cap(p_org, 'stock') then raise exception 'Seul un responsable du stock saisit la récolte d’un autre.'; end if;
    select * into who from public.members where id = p_member and org_id = p_org and active;
    if who.id is null then raise exception 'Employé inconnu.'; end if;
  end if;
  if coalesce(p_qty, 0) <= 0 then raise exception 'Indique une quantité.'; end if;
  select * into it from public.items where id = p_item and org_id = p_org and not archived;
  if it.id is null or it.harvest_bonus is null then raise exception 'Ce produit ne se récolte pas.'; end if;
  perform public.stock_add(p_org, it.id, p_qty, 'Récolte · ' || who.name, me);
  b := round(p_qty * it.harvest_bonus, 2);
  insert into public.harvests (org_id, member_id, by_name, item_id, qty, bonus) values (p_org, who.id, who.name, it.id, p_qty, b);
  return b;
end $$;

create or replace function public.pay_harvest_bonuses(p_org uuid) returns numeric
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'recolte', 'paie'); due numeric; n int;
begin
  select coalesce(sum(bonus), 0), count(*) into due, n from public.harvests where org_id = p_org and paid_at is null and bonus > 0;
  if due <= 0 then raise exception 'Aucune prime à verser.'; end if;
  perform public.book(p_org, 'out', 'Salaires', 'Primes de récolte · ' || n || ' saisie(s)', due, me, null);
  update public.harvests set paid_at = now() where org_id = p_org and paid_at is null;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Primes de récolte versées : ' || public.fmt_money(due));
  return due;
end $$;

-- ---------------------------------------------------------------- caisse
-- un ticket : chaque article est pris dans le stock ; s'il n'y en a plus et qu'il a une recette,
-- il est préparé à la commande et ses ingrédients sortent du stock
create or replace function public.sell(p_org uuid, p_lines jsonb, p_payment text) returns json
language plpgsql security definer set search_path = '' as $$
declare
  me public.members := public.need(p_org, 'caisse', 'encaisser');
  l jsonb; it public.items; r public.recipes; inp record; n numeric; from_stock numeric; to_make numeric;
  total numeric := 0; count_n numeric := 0; num integer; ev uuid; lines jsonb := '[]'; label text;
begin
  if p_payment not in ('carte', 'especes') then raise exception 'Moyen de paiement inconnu.'; end if;
  if jsonb_array_length(coalesce(p_lines, '[]')) = 0 then raise exception 'Le ticket est vide.'; end if;
  perform 1 from public.orgs where id = p_org for update;   -- numéros de ticket sans trou ni doublon
  select coalesce(max(s.num), 0) + 1 into num from public.sales s where s.org_id = p_org;
  label := 'Vente · ticket n°' || num;
  for l in select * from jsonb_array_elements(p_lines) loop
    n := (l->>'qty')::numeric;
    if n is null or n <= 0 or n <> trunc(n) or n > 1000 then raise exception 'Quantité invalide.'; end if;
    select * into it from public.items where id = (l->>'item')::uuid and org_id = p_org and not archived;
    if it.id is null then raise exception 'Article inconnu.'; end if;
    if it.price is null then raise exception '« % » n’est pas à la carte.', it.name; end if;
    from_stock := least(it.qty, n);
    to_make := n - from_stock;
    if from_stock > 0 then perform public.stock_add(p_org, it.id, -from_stock, label, me); end if;
    if to_make > 0 then
      select * into r from public.recipes where org_id = p_org and item_id = it.id and not archived;
      if r.id is null then raise exception 'Rupture : plus de « % » en stock.', it.name; end if;
      for inp in select ri.item_id, ri.qty from public.recipe_inputs ri where ri.recipe_id = r.id loop
        perform public.stock_add(p_org, inp.item_id, -(inp.qty * to_make / r.yield), label, me);
      end loop;
    end if;
    total := total + n * it.price;
    count_n := count_n + n;
    lines := lines || jsonb_build_array(jsonb_build_object('item', it.id, 'name', it.name, 'qty', n, 'price', it.price));
  end loop;
  ev := public.live_event(p_org, 'ventes');
  insert into public.sales (org_id, num, member_id, by_name, payment, total, lines, event_id) values (p_org, num, me.id, me.name, p_payment, total, lines, ev);
  perform public.book(p_org, 'in', 'Ventes', 'Ticket n°' || num || ' · ' || count_n::int || ' article(s)', total, me, ev);
  return json_build_object('num', num, 'total', total, 'event', ev);
end $$;

-- ---------------------------------------------------------------- factures
create or replace function public.create_invoice(p_org uuid, p_client text, p_client_code text, p_lines jsonb, p_due_days integer, p_take_stock boolean) returns text
language plpgsql security definer set search_path = '' as $$
declare
  me public.members := public.need(p_org, 'factures', 'compta');
  co public.orgs; client text := trim(coalesce(p_client, '')); l jsonb; total numeric := 0; num text; lines jsonb := '[]';
  q numeric; pr numeric; lbl text; it public.items;
begin
  if nullif(trim(coalesce(p_client_code, '')), '') is not null then
    select * into co from public.orgs where code = lower(trim(p_client_code));
    if co.id is null then raise exception 'Aucune entreprise à l’adresse /%.', lower(trim(p_client_code)); end if;
    if co.id = p_org then raise exception 'Une entreprise ne se facture pas à elle-même.'; end if;
    client := co.name;
  end if;
  if length(client) < 2 then raise exception 'Indique le client.'; end if;
  perform 1 from public.orgs where id = p_org for update;
  select 'F-' || lpad((count(*) + 1)::text, 4, '0') into num from public.invoices where org_id = p_org;
  for l in select * from jsonb_array_elements(coalesce(p_lines, '[]')) loop
    q := (l->>'qty')::numeric; pr := (l->>'price')::numeric; lbl := trim(coalesce(l->>'label', ''));
    if nullif(l->>'item', '') is not null then
      select * into it from public.items where id = (l->>'item')::uuid and org_id = p_org and not archived;
      if it.id is null then raise exception 'Article inconnu.'; end if;
      if lbl = '' then lbl := it.name; end if;
      if p_take_stock then perform public.stock_add(p_org, it.id, -q, 'Facture ' || num || ' · ' || client, me); end if;
    end if;
    if coalesce(q, 0) <= 0 or coalesce(pr, -1) < 0 or length(lbl) < 2 then raise exception 'Chaque ligne a un libellé, une quantité et un prix.'; end if;
    total := total + q * pr;
    lines := lines || jsonb_build_array(jsonb_build_object('label', lbl, 'qty', q, 'price', pr));
  end loop;
  if total <= 0 then raise exception 'La facture est vide.'; end if;
  insert into public.invoices (org_id, num, client, client_org_id, lines, total, due_at, by_name)
    values (p_org, num, client, co.id, lines, round(total, 2), now() + make_interval(days => greatest(0, least(coalesce(p_due_days, 7), 90))), me.name);
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Facture ' || num || ' émise pour ' || client || ' · ' || public.fmt_money(total));
  return num;
end $$;

-- une facture payée entre dans la comptabilité de l'émetteur, et dans celle du client s'il utilise Windy Desk
create or replace function public.pay_invoice(p_invoice uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare inv public.invoices; me public.members; issuer text;
begin
  select * into inv from public.invoices where id = p_invoice for update;
  if inv.id is null then raise exception 'Facture inconnue.'; end if;
  me := public.need(inv.org_id, 'factures', 'compta');
  if inv.status <> 'attente' then raise exception 'Cette facture n’est plus en attente.'; end if;
  update public.invoices set status = 'payee', paid_at = now() where id = inv.id;
  perform public.book(inv.org_id, 'in', 'Factures', 'Règlement ' || inv.num || ' · ' || inv.client, inv.total, me, null);
  if inv.client_org_id is not null then
    select name into issuer from public.orgs where id = inv.org_id;
    insert into public.ledger (org_id, kind, category, label, amount, by_name)
      values (inv.client_org_id, 'out', 'Achats', left('Facture ' || inv.num || ' · ' || issuer, 140), inv.total, issuer);
    insert into public.journal (org_id, who, what) values (inv.client_org_id, issuer, 'Facture ' || inv.num || ' réglée · ' || public.fmt_money(inv.total));
  end if;
end $$;

create or replace function public.cancel_invoice(p_invoice uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare inv public.invoices; me public.members;
begin
  select * into inv from public.invoices where id = p_invoice for update;
  if inv.id is null then raise exception 'Facture inconnue.'; end if;
  me := public.need(inv.org_id, 'factures', 'compta');
  if inv.status <> 'attente' then raise exception 'Seule une facture en attente s’annule.'; end if;
  update public.invoices set status = 'annulee' where id = inv.id;
  insert into public.journal (org_id, who, what) values (inv.org_id, me.name, 'Facture ' || inv.num || ' annulée');
end $$;

-- ---------------------------------------------------------------- service et paie
create or replace function public.toggle_duty(p_org uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'service', null); sid bigint;
begin
  update public.shifts set ended_at = now() where member_id = me.id and ended_at is null returning id into sid;
  if sid is not null then return false; end if;
  insert into public.shifts (org_id, member_id) values (p_org, me.id);
  return true;
end $$;

create or replace function public.set_rate(p_member uuid, p_rate numeric) returns void
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null then raise exception 'Compte inconnu.'; end if;
  me := public.need(mem.org_id, 'service', 'paie');
  if coalesce(p_rate, -1) < 0 or p_rate > 100000 then raise exception 'Taux horaire invalide.'; end if;
  update public.members set hourly_rate = round(p_rate, 2) where id = mem.id;
  insert into public.journal (org_id, who, what) values (mem.org_id, me.name, 'Taux horaire de ' || mem.name || ' : ' || public.fmt_money(p_rate));
end $$;

-- paie les heures des services terminés (12 h au plus par service, contre les oublis de fin de service)
create or replace function public.pay_salaries(p_org uuid) returns numeric
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'service', 'paie'); total numeric; lines jsonb;
begin
  select coalesce(sum(amount), 0), coalesce(jsonb_agg(jsonb_build_object('member', member_id, 'name', name, 'hours', hours, 'rate', rate, 'amount', amount)), '[]')
    into total, lines
    from (
      select s.member_id, m.name, m.hourly_rate as rate,
        round(sum(least(extract(epoch from s.ended_at - s.started_at), 43200)) / 3600, 2) as hours,
        round(sum(least(extract(epoch from s.ended_at - s.started_at), 43200)) / 3600 * m.hourly_rate, 2) as amount
      from public.shifts s join public.members m on m.id = s.member_id
      where s.org_id = p_org and s.ended_at is not null and s.paid_at is null
      group by s.member_id, m.name, m.hourly_rate
    ) x where amount > 0;
  if total <= 0 then raise exception 'Rien à payer : aucune heure terminée et non payée avec un taux horaire.'; end if;
  perform public.book(p_org, 'out', 'Salaires', 'Salaires · ' || jsonb_array_length(lines) || ' employé(s)', total, me, null);
  update public.shifts set paid_at = now() where org_id = p_org and ended_at is not null and paid_at is null;
  insert into public.payroll_runs (org_id, total, lines, by_name) values (p_org, total, lines, me.name);
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Salaires payés : ' || public.fmt_money(total));
  return total;
end $$;

-- ---------------------------------------------------------------- dons et distributions
create or replace function public.add_donation(p_org uuid, p_donor text, p_amount numeric, p_item uuid, p_qty numeric, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'dons', 'stock'); donor text := coalesce(nullif(trim(coalesce(p_donor, '')), ''), 'Anonyme'); ev uuid := public.live_event(p_org, 'dons');
begin
  if p_item is null then
    if coalesce(p_amount, 0) <= 0 then raise exception 'Indique le montant du don.'; end if;
    perform public.book(p_org, 'in', 'Dons', 'Don · ' || donor, p_amount, me, ev);
    insert into public.donations (org_id, donor, amount, note, event_id, by_name) values (p_org, donor, round(p_amount, 2), nullif(trim(coalesce(p_note, '')), ''), ev, me.name);
  else
    if coalesce(p_qty, 0) <= 0 then raise exception 'Indique la quantité donnée.'; end if;
    perform public.stock_add(p_org, p_item, p_qty, 'Don · ' || donor, me);
    insert into public.donations (org_id, donor, item_id, qty, note, event_id, by_name) values (p_org, donor, p_item, p_qty, nullif(trim(coalesce(p_note, '')), ''), ev, me.name);
  end if;
end $$;

create or replace function public.distribute(p_org uuid, p_beneficiary text, p_item uuid, p_qty numeric, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'dons', 'stock');
begin
  if length(trim(coalesce(p_beneficiary, ''))) < 2 then raise exception 'Indique le bénéficiaire.'; end if;
  if coalesce(p_qty, 0) <= 0 then raise exception 'Indique la quantité.'; end if;
  perform public.stock_add(p_org, p_item, -p_qty, 'Distribution · ' || trim(p_beneficiary), me);
  insert into public.distributions (org_id, beneficiary, item_id, qty, note, by_name) values (p_org, trim(p_beneficiary), p_item, p_qty, nullif(trim(coalesce(p_note, '')), ''), me.name);
end $$;

-- ---------------------------------------------------------------- employés
-- outil : la personne connectée peut-elle gérer ce compte (même entreprise, grade pas plus haut que le sien, pas soi-même)
create or replace function public.manage_member(p_member uuid, m text, out member_id uuid, out org_id uuid, out name text, out by_name text)
language plpgsql security definer set search_path = '' as $$
declare mem public.members; me public.members; my_rank integer; their_rank integer;
begin
  select * into mem from public.members where id = p_member;
  if mem.id is null then raise exception 'Compte inconnu.'; end if;
  me := public.need(mem.org_id, m, 'equipe');
  if me.id = mem.id then raise exception 'Tu ne peux pas faire ça sur ton propre compte.'; end if;
  select rank into my_rank from public.grades where id = me.grade_id;
  select rank into their_rank from public.grades where id = mem.grade_id;
  if their_rank > my_rank then raise exception 'Tu ne peux pas gérer un grade plus haut que le tien.'; end if;
  member_id := mem.id; org_id := mem.org_id; name := mem.name; by_name := me.name;
end $$;

create or replace function public.save_profile(p_member uuid, p_phone text, p_contract text, p_licences text[], p_hired_at date) returns void
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  select * into r from public.manage_member(p_member, 'employes');
  update public.members set phone = nullif(trim(coalesce(p_phone, '')), ''), contract = coalesce(nullif(trim(coalesce(p_contract, '')), ''), 'CDI'),
    licences = coalesce(p_licences, '{}'), hired_at = coalesce(p_hired_at, hired_at) where id = r.member_id;
  insert into public.journal (org_id, who, what) values (r.org_id, r.by_name, 'Fiche de ' || r.name || ' mise à jour');
end $$;

create or replace function public.add_warning(p_member uuid, p_reason text) returns void
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  select * into r from public.manage_member(p_member, 'employes');
  insert into public.warnings (org_id, member_id, reason, by_name) values (r.org_id, r.member_id, trim(coalesce(p_reason, '')), r.by_name);
  insert into public.journal (org_id, who, what) values (r.org_id, r.by_name, 'Avertissement à ' || r.name);
end $$;

create or replace function public.add_staff_note(p_member uuid, p_text text) returns void
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  select * into r from public.manage_member(p_member, 'employes');
  insert into public.staff_notes (org_id, member_id, text, by_name) values (r.org_id, r.member_id, trim(coalesce(p_text, '')), r.by_name);
end $$;

create or replace function public.dismiss_member(p_member uuid, p_reason text) returns void
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  select * into r from public.manage_member(p_member, 'employes');
  if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'Indique le motif du départ.'; end if;
  update public.members set active = false, left_at = now(), left_reason = trim(p_reason) where id = r.member_id;
  update public.shifts set ended_at = now() where member_id = r.member_id and ended_at is null;
  update public.vehicles set status = 'garage', driver_id = null, out_since = null where driver_id = r.member_id and status = 'sortie';
  insert into public.journal (org_id, who, what) values (r.org_id, r.by_name, 'Départ de ' || r.name || ' : ' || trim(p_reason));
end $$;

-- ---------------------------------------------------------------- véhicules
create or replace function public.save_vehicle(p_org uuid, p_id uuid, p_model text, p_plate text, p_usage text, p_insured_until date) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'vehicules', 'vehicules'); vid uuid;
begin
  if p_id is null then
    insert into public.vehicles (org_id, model, plate, usage, insured_until) values (p_org, trim(p_model), upper(trim(p_plate)), coalesce(nullif(trim(coalesce(p_usage, '')), ''), 'Utilitaire'), p_insured_until)
      returning id into vid;
    insert into public.vehicle_logs (org_id, vehicle_id, action, member_id, by_name) values (p_org, vid, 'Ajouté à la flotte', me.id, me.name);
  else
    update public.vehicles set model = trim(p_model), plate = upper(trim(p_plate)), usage = coalesce(nullif(trim(coalesce(p_usage, '')), ''), 'Utilitaire'), insured_until = p_insured_until
      where id = p_id and org_id = p_org returning id into vid;
    if vid is null then raise exception 'Véhicule inconnu.'; end if;
  end if;
  return vid;
exception when unique_violation then
  raise exception 'La plaque % est déjà dans la flotte.', upper(trim(p_plate));
end $$;

create or replace function public.take_vehicle(p_vehicle uuid, p_driver uuid, p_note text) returns void
language plpgsql security definer set search_path = '' as $$
declare v public.vehicles; me public.members; drv public.members;
begin
  select * into v from public.vehicles where id = p_vehicle and not archived for update;
  if v.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(v.org_id, 'vehicules', 'vehicules');
  if v.status <> 'garage' then raise exception 'Ce véhicule n’est pas au garage.'; end if;
  drv := me;
  if p_driver is not null and p_driver <> me.id then
    select * into drv from public.members where id = p_driver and org_id = v.org_id and active;
    if drv.id is null then raise exception 'Conducteur inconnu.'; end if;
  end if;
  update public.vehicles set status = 'sortie', driver_id = drv.id, out_since = now() where id = v.id;
  insert into public.vehicle_logs (org_id, vehicle_id, action, note, member_id, by_name) values (v.org_id, v.id, 'Sortie', nullif(trim(coalesce(p_note, '')), ''), drv.id, drv.name);
end $$;

create or replace function public.return_vehicle(p_vehicle uuid, p_fuel integer, p_condition integer, p_km integer, p_note text) returns text
language plpgsql security definer set search_path = '' as $$
declare v public.vehicles; me public.members; drv text; st text; dmg integer;
begin
  select * into v from public.vehicles where id = p_vehicle for update;
  if v.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(v.org_id, 'vehicules', 'vehicules');
  if v.status <> 'sortie' then raise exception 'Ce véhicule n’est pas en sortie.'; end if;
  if p_fuel not between 0 and 100 or p_condition not between 0 and 100 or coalesce(p_km, 0) < 0 then raise exception 'Valeurs invalides.'; end if;
  dmg := v.condition - p_condition;
  st := case when p_condition < 30 then 'reparation' else 'garage' end;
  select name into drv from public.members where id = v.driver_id;
  update public.vehicles set status = st, driver_id = null, out_since = null, fuel = p_fuel, condition = p_condition, km = km + coalesce(p_km, 0) where id = v.id;
  insert into public.vehicle_logs (org_id, vehicle_id, action, note, member_id, by_name)
    values (v.org_id, v.id, 'Retour', concat_ws(' · ', nullif(trim(coalesce(p_note, '')), ''), case when dmg > 0 then '−' || dmg || ' % d’état' end, coalesce(p_km, 0) || ' km'), v.driver_id, coalesce(drv, me.name));
  return st;
end $$;

-- plein, réparation ou assurance ; le coût entre en comptabilité (Entretien)
create or replace function public.service_vehicle(p_vehicle uuid, p_kind text, p_amount numeric, p_days integer) returns void
language plpgsql security definer set search_path = '' as $$
declare v public.vehicles; me public.members; what text;
begin
  select * into v from public.vehicles where id = p_vehicle for update;
  if v.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(v.org_id, 'vehicules', 'vehicules');
  if p_kind = 'plein' then
    update public.vehicles set fuel = 100 where id = v.id; what := 'Plein';
  elsif p_kind = 'reparation' then
    if v.status = 'sortie' then raise exception 'Le véhicule doit être rentré pour être réparé.'; end if;
    update public.vehicles set condition = 100, status = 'garage' where id = v.id; what := 'Réparation';
  elsif p_kind = 'assurance' then
    if coalesce(p_days, 0) < 1 then raise exception 'Indique la durée de l’assurance.'; end if;
    update public.vehicles set insured_until = greatest(coalesce(insured_until, current_date), current_date) + p_days where id = v.id; what := 'Assurance · ' || p_days || ' j';
  else raise exception 'Opération inconnue.';
  end if;
  if coalesce(p_amount, 0) > 0 then perform public.book(v.org_id, 'out', 'Entretien', what || ' · ' || v.model || ' ' || v.plate, p_amount, me, null); end if;
  insert into public.vehicle_logs (org_id, vehicle_id, action, note, member_id, by_name) values (v.org_id, v.id, what, case when coalesce(p_amount, 0) > 0 then public.fmt_money(p_amount) end, me.id, me.name);
end $$;

create or replace function public.archive_vehicle(p_vehicle uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare v public.vehicles; me public.members;
begin
  select * into v from public.vehicles where id = p_vehicle;
  if v.id is null then raise exception 'Véhicule inconnu.'; end if;
  me := public.need(v.org_id, 'vehicules', 'vehicules');
  if v.status = 'sortie' then raise exception 'Le véhicule est en sortie.'; end if;
  update public.vehicles set archived = true where id = v.id;
  insert into public.vehicle_logs (org_id, vehicle_id, action, member_id, by_name) values (v.org_id, v.id, 'Retiré de la flotte', me.id, me.name);
end $$;

-- ---------------------------------------------------------------- événements
create or replace function public.save_event(p_org uuid, p_id uuid, p_name text, p_place text, p_kind text, p_starts timestamptz, p_ends timestamptz,
  p_goal numeric, p_budget numeric, p_note text, p_staff uuid[], p_stock jsonb, p_tasks text[]) returns uuid
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.need(p_org, 'evenements', 'evenements'); eid uuid; l jsonb; t text;
begin
  if p_kind not in ('ventes', 'dons') then raise exception 'Type d’événement inconnu.'; end if;
  if length(trim(coalesce(p_name, ''))) < 3 then raise exception 'Donne un nom à l’événement.'; end if;
  if p_ends <= p_starts then raise exception 'La fin doit être après le début.'; end if;
  if p_id is null then
    insert into public.events (org_id, name, place, kind, starts_at, ends_at, goal, budget, note, by_name)
      values (p_org, trim(p_name), trim(coalesce(p_place, '')), p_kind, p_starts, p_ends, greatest(coalesce(p_goal, 0), 0), greatest(coalesce(p_budget, 0), 0), nullif(trim(coalesce(p_note, '')), ''), me.name)
      returning id into eid;
    insert into public.journal (org_id, who, what) values (p_org, me.name, 'Événement créé : ' || trim(p_name));
    foreach t in array coalesce(p_tasks, '{}') loop
      if length(trim(t)) >= 2 then insert into public.event_tasks (event_id, label) values (eid, left(trim(t), 80)); end if;
    end loop;
  else
    update public.events set name = trim(p_name), place = trim(coalesce(p_place, '')), kind = p_kind, starts_at = p_starts, ends_at = p_ends,
      goal = greatest(coalesce(p_goal, 0), 0), budget = greatest(coalesce(p_budget, 0), 0), note = nullif(trim(coalesce(p_note, '')), '')
      where id = p_id and org_id = p_org returning id into eid;
    if eid is null then raise exception 'Événement inconnu.'; end if;
  end if;
  delete from public.event_staff where event_id = eid;
  insert into public.event_staff (event_id, member_id) select eid, m.id from public.members m where m.org_id = p_org and m.id = any (coalesce(p_staff, '{}'));
  delete from public.event_stock where event_id = eid;
  for l in select * from jsonb_array_elements(coalesce(p_stock, '[]')) loop
    if coalesce((l->>'qty')::numeric, 0) > 0 and exists (select 1 from public.items where id = (l->>'item')::uuid and org_id = p_org) then
      insert into public.event_stock (event_id, item_id, qty) values (eid, (l->>'item')::uuid, (l->>'qty')::numeric) on conflict (event_id, item_id) do update set qty = excluded.qty;
    end if;
  end loop;
  return eid;
end $$;

create or replace function public.event_add_task(p_event uuid, p_label text) returns void
language plpgsql security definer set search_path = '' as $$
declare e public.events; me public.members;
begin
  select * into e from public.events where id = p_event;
  if e.id is null then raise exception 'Événement inconnu.'; end if;
  me := public.need(e.org_id, 'evenements', null);
  if length(trim(coalesce(p_label, ''))) < 2 then raise exception 'Écris la tâche.'; end if;
  insert into public.event_tasks (event_id, label) values (e.id, left(trim(p_label), 80));
end $$;

create or replace function public.event_toggle_task(p_task bigint, p_done boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare t public.event_tasks; e public.events; me public.members;
begin
  select * into t from public.event_tasks where id = p_task;
  if t.id is null then raise exception 'Tâche inconnue.'; end if;
  select * into e from public.events where id = t.event_id;
  me := public.need(e.org_id, 'evenements', null);
  update public.event_tasks set done = p_done, done_by = case when p_done then me.name end where id = t.id;
end $$;

create or replace function public.event_spend(p_event uuid, p_label text, p_amount numeric) returns void
language plpgsql security definer set search_path = '' as $$
declare e public.events; me public.members;
begin
  select * into e from public.events where id = p_event;
  if e.id is null then raise exception 'Événement inconnu.'; end if;
  me := public.need(e.org_id, 'evenements', 'evenements');
  if length(trim(coalesce(p_label, ''))) < 3 then raise exception 'Écris un libellé.'; end if;
  if coalesce(p_amount, 0) <= 0 then raise exception 'Indique le montant.'; end if;
  perform public.book(e.org_id, 'out', 'Achats', e.name || ' · ' || trim(p_label), p_amount, me, e.id);
end $$;

create or replace function public.close_event(p_event uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare e public.events; me public.members;
begin
  select * into e from public.events where id = p_event;
  if e.id is null then raise exception 'Événement inconnu.'; end if;
  me := public.need(e.org_id, 'evenements', 'evenements');
  update public.events set closed_at = now() where id = e.id and closed_at is null;
  insert into public.journal (org_id, who, what) values (e.org_id, me.name, 'Événement clôturé : ' || e.name);
end $$;

-- le bilan d'un événement, lisible par toute l'équipe (sans ouvrir la comptabilité) : recettes, dépenses, recettes par heure
create or replace function public.event_book(p_event uuid) returns json
language plpgsql stable security definer set search_path = '' as $$
declare e public.events;
begin
  select * into e from public.events where id = p_event;
  if e.id is null or not public.is_member(e.org_id) then raise exception 'Événement inconnu.'; end if;
  return json_build_object(
    'income', coalesce((select sum(total) from public.sales where event_id = e.id), 0) + coalesce((select sum(amount) from public.donations where event_id = e.id and amount is not null), 0),
    'tickets', (select count(*) from public.sales where event_id = e.id),
    'donors', (select count(*) from public.donations where event_id = e.id),
    'spent', coalesce((select sum(amount) from public.ledger where event_id = e.id and kind = 'out'), 0),
    'spends', coalesce((select json_agg(json_build_object('at', at, 'label', label, 'amount', amount, 'by', by_name) order by at desc) from public.ledger where event_id = e.id and kind = 'out'), '[]'),
    'series', coalesce((select json_agg(json_build_object('t', h, 'v', v) order by h) from (
        select date_trunc('hour', at) as h, sum(total) as v from public.sales where event_id = e.id group by 1
        union all
        select date_trunc('hour', at), sum(amount) from public.donations where event_id = e.id and amount is not null group by 1) s), '[]'));
end $$;

-- ---------------------------------------------------------------- règles de lecture
alter table public.items enable row level security;
alter table public.stock_moves enable row level security;
alter table public.recipes enable row level security;
alter table public.recipe_inputs enable row level security;
alter table public.productions enable row level security;
alter table public.harvests enable row level security;
alter table public.events enable row level security;
alter table public.event_staff enable row level security;
alter table public.event_stock enable row level security;
alter table public.event_tasks enable row level security;
alter table public.sales enable row level security;
alter table public.invoices enable row level security;
alter table public.shifts enable row level security;
alter table public.payroll_runs enable row level security;
alter table public.donations enable row level security;
alter table public.distributions enable row level security;
alter table public.warnings enable row level security;
alter table public.staff_notes enable row level security;
alter table public.vehicles enable row level security;
alter table public.vehicle_logs enable row level security;

create policy items_read on public.items for select to authenticated using (public.is_member(org_id) or (select public.platform_role()) = 'fisc');
create policy stock_moves_read on public.stock_moves for select to authenticated
  using (public.has_cap(org_id, 'stock') or public.has_cap(org_id, 'fabriquer') or (select public.platform_role()) = 'fisc');
create policy recipes_read on public.recipes for select to authenticated using (public.is_member(org_id));
create policy recipe_inputs_read on public.recipe_inputs for select to authenticated
  using (exists (select 1 from public.recipes r where r.id = recipe_id and public.is_member(r.org_id)));
create policy productions_read on public.productions for select to authenticated using (public.is_member(org_id));
create policy harvests_read on public.harvests for select to authenticated using (public.is_member(org_id));
create policy events_read on public.events for select to authenticated using (public.is_member(org_id));
create policy event_staff_read on public.event_staff for select to authenticated using (exists (select 1 from public.events e where e.id = event_id and public.is_member(e.org_id)));
create policy event_stock_read on public.event_stock for select to authenticated using (exists (select 1 from public.events e where e.id = event_id and public.is_member(e.org_id)));
create policy event_tasks_read on public.event_tasks for select to authenticated using (exists (select 1 from public.events e where e.id = event_id and public.is_member(e.org_id)));
create policy sales_read on public.sales for select to authenticated
  using (public.has_cap(org_id, 'encaisser') or public.has_cap(org_id, 'compta') or (select public.platform_role()) = 'fisc');
create policy invoices_read on public.invoices for select to authenticated
  using (public.has_cap(org_id, 'compta') or (client_org_id is not null and public.has_cap(client_org_id, 'compta')) or (select public.platform_role()) = 'fisc');
create policy shifts_read on public.shifts for select to authenticated using (public.is_member(org_id));
create policy payroll_runs_read on public.payroll_runs for select to authenticated using (public.has_cap(org_id, 'paie') or public.has_cap(org_id, 'compta'));
create policy donations_read on public.donations for select to authenticated using (public.is_member(org_id) or (select public.platform_role()) = 'fisc');
create policy distributions_read on public.distributions for select to authenticated using (public.is_member(org_id));
create policy warnings_read on public.warnings for select to authenticated
  using (public.has_cap(org_id, 'equipe') or member_id = (public.caller_member(org_id)).id);
create policy staff_notes_read on public.staff_notes for select to authenticated using (public.has_cap(org_id, 'equipe'));
create policy vehicles_read on public.vehicles for select to authenticated using (public.is_member(org_id));
create policy vehicle_logs_read on public.vehicle_logs for select to authenticated using (public.is_member(org_id));

-- ---------------------------------------------------------------- droits
revoke all on public.items, public.stock_moves, public.recipes, public.recipe_inputs, public.productions, public.harvests, public.events, public.event_staff,
  public.event_stock, public.event_tasks, public.sales, public.invoices, public.shifts, public.payroll_runs, public.donations, public.distributions,
  public.warnings, public.staff_notes, public.vehicles, public.vehicle_logs from anon, authenticated;
grant select on public.items, public.stock_moves, public.recipes, public.recipe_inputs, public.productions, public.harvests, public.events, public.event_staff,
  public.event_stock, public.event_tasks, public.sales, public.invoices, public.shifts, public.payroll_runs, public.donations, public.distributions,
  public.warnings, public.staff_notes, public.vehicles, public.vehicle_logs to authenticated;

revoke execute on function public.fmt_qty(numeric), public.need(uuid, text, text), public.book(uuid, text, text, text, numeric, public.members, uuid),
  public.stock_add(uuid, uuid, numeric, text, public.members), public.live_event(uuid, text), public.manage_member(uuid, text),
  public.save_item(uuid, uuid, text, text, text, text, numeric, numeric, numeric, numeric), public.archive_item(uuid), public.move_stock(uuid, uuid, numeric, text, numeric),
  public.save_recipe(uuid, uuid, numeric, integer, jsonb), public.archive_recipe(uuid), public.produce(uuid, integer),
  public.add_harvest(uuid, uuid, numeric, uuid), public.pay_harvest_bonuses(uuid), public.sell(uuid, jsonb, text),
  public.create_invoice(uuid, text, text, jsonb, integer, boolean), public.pay_invoice(uuid), public.cancel_invoice(uuid),
  public.toggle_duty(uuid), public.set_rate(uuid, numeric), public.pay_salaries(uuid),
  public.add_donation(uuid, text, numeric, uuid, numeric, text), public.distribute(uuid, text, uuid, numeric, text),
  public.save_profile(uuid, text, text, text[], date), public.add_warning(uuid, text), public.add_staff_note(uuid, text), public.dismiss_member(uuid, text),
  public.save_vehicle(uuid, uuid, text, text, text, date), public.take_vehicle(uuid, uuid, text), public.return_vehicle(uuid, integer, integer, integer, text),
  public.service_vehicle(uuid, text, numeric, integer), public.archive_vehicle(uuid),
  public.save_event(uuid, uuid, text, text, text, timestamptz, timestamptz, numeric, numeric, text, uuid[], jsonb, text[]),
  public.event_add_task(uuid, text), public.event_toggle_task(bigint, boolean), public.event_spend(uuid, text, numeric), public.close_event(uuid), public.event_book(uuid)
  from public, anon, authenticated;

grant execute on function
  public.save_item(uuid, uuid, text, text, text, text, numeric, numeric, numeric, numeric), public.archive_item(uuid), public.move_stock(uuid, uuid, numeric, text, numeric),
  public.save_recipe(uuid, uuid, numeric, integer, jsonb), public.archive_recipe(uuid), public.produce(uuid, integer),
  public.add_harvest(uuid, uuid, numeric, uuid), public.pay_harvest_bonuses(uuid), public.sell(uuid, jsonb, text),
  public.create_invoice(uuid, text, text, jsonb, integer, boolean), public.pay_invoice(uuid), public.cancel_invoice(uuid),
  public.toggle_duty(uuid), public.set_rate(uuid, numeric), public.pay_salaries(uuid),
  public.add_donation(uuid, text, numeric, uuid, numeric, text), public.distribute(uuid, text, uuid, numeric, text),
  public.save_profile(uuid, text, text, text[], date), public.add_warning(uuid, text), public.add_staff_note(uuid, text), public.dismiss_member(uuid, text),
  public.save_vehicle(uuid, uuid, text, text, text, date), public.take_vehicle(uuid, uuid, text), public.return_vehicle(uuid, integer, integer, integer, text),
  public.service_vehicle(uuid, text, numeric, integer), public.archive_vehicle(uuid),
  public.save_event(uuid, uuid, text, text, text, timestamptz, timestamptz, numeric, numeric, text, uuid[], jsonb, text[]),
  public.event_add_task(uuid, text), public.event_toggle_task(bigint, boolean), public.event_spend(uuid, text, numeric), public.close_event(uuid), public.event_book(uuid)
  to authenticated;
-- la règle de lecture des avertissements appelle caller_member : il faut pouvoir l'exécuter
grant execute on function public.caller_member(uuid) to authenticated;
