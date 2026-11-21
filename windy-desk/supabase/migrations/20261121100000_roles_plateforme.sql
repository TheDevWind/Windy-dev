-- Rôles de plateforme multiples : un compte peut détenir éditeur, Justice et fisc en même temps.
-- Le reste du site continue de lire UN rôle « actif » (platform_roles, une ligne par compte) : on n'y touche pas.
-- Nouveau : platform_grants garde tous les rôles détenus, et switch_platform_role choisit celui qui est actif.
-- Avant, utiliser un lien Justice avec un compte éditeur remplaçait son rôle éditeur : c'est ce que cette migration répare.

create table public.platform_grants (
  user_id uuid not null references auth.users (id) on delete cascade,
  role text not null check (role in ('editeur', 'fisc', 'justice')),
  display_name text not null,
  granted_at timestamptz not null default now(),
  primary key (user_id, role)
);
alter table public.platform_grants enable row level security;
create policy platform_grants_self on public.platform_grants for select to authenticated using (user_id = (select auth.uid()));
revoke all on public.platform_grants from anon, authenticated;
grant select on public.platform_grants to authenticated;

-- 1. les rôles actuels
insert into public.platform_grants (user_id, role, display_name) select user_id, role, display_name from public.platform_roles on conflict do nothing;
-- 2. les rôles écrasés : chaque lien utilisé par un compte encore actif redonne ce rôle (un compte retiré par l'éditeur n'est pas rétabli)
insert into public.platform_grants (user_id, role, display_name, granted_at)
  select c.used_by, c.role, c.display_name, c.used_at from public.platform_claims c join public.platform_roles r on r.user_id = c.used_by
  where c.used_at is not null on conflict do nothing;
-- 3. le rôle actif devient le plus étendu que le compte détient (éditeur, puis fisc, puis Justice)
update public.platform_roles r set role = g.role, display_name = g.display_name
  from (select distinct on (user_id) user_id, role, display_name from public.platform_grants order by user_id, case role when 'editeur' then 1 when 'fisc' then 2 else 3 end) g
  where g.user_id = r.user_id and g.role <> r.role;

-- utiliser un lien ajoute le rôle sans retirer les autres ; il devient le rôle actif
create or replace function public.claim_platform_role(p_token text) returns text
language plpgsql security definer set search_path = '' as $$
declare c public.platform_claims;
begin
  if auth.uid() is null then raise exception 'Connexion requise.'; end if;
  select * into c from public.platform_claims where token = p_token for update;
  if c.token is null or c.used_at is not null or c.expires_at < now() then raise exception 'Ce lien n’est plus valable.'; end if;
  insert into public.platform_grants (user_id, role, display_name) values (auth.uid(), c.role, c.display_name)
    on conflict (user_id, role) do update set display_name = excluded.display_name;
  insert into public.platform_roles (user_id, role, display_name) values (auth.uid(), c.role, c.display_name)
    on conflict (user_id) do update set role = excluded.role, display_name = excluded.display_name;
  update public.platform_claims set used_at = now(), used_by = auth.uid() where token = p_token;
  return c.role;
end $$;

-- change le rôle actif parmi ceux que le compte détient
create or replace function public.switch_platform_role(p_role text) returns text
language plpgsql security definer set search_path = '' as $$
declare g public.platform_grants;
begin
  if auth.uid() is null then raise exception 'Connexion requise.'; end if;
  select * into g from public.platform_grants where user_id = auth.uid() and role = p_role;
  if g.user_id is null then raise exception 'Ce compte n’a pas accès à cet espace.'; end if;
  update public.platform_roles set role = g.role, display_name = g.display_name where user_id = auth.uid();
  if not found then insert into public.platform_roles (user_id, role, display_name) values (g.user_id, g.role, g.display_name); end if;
  return g.role;
end $$;

-- retirer l'accès d'un compte retire tous ses rôles
create or replace function public.revoke_platform(p_user uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_editeur();
  if p_user = auth.uid() then raise exception 'Tu ne peux pas retirer ton propre accès.'; end if;
  delete from public.platform_grants where user_id = p_user;
  delete from public.platform_roles where user_id = p_user;
end $$;

-- my_spaces : rôle actif (« role ») + tous les rôles détenus (« roles »)
create or replace function public.my_spaces() returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object(
    'role', (select r.role from public.platform_roles r where r.user_id = auth.uid()),
    'roles', coalesce((select json_agg(g.role order by case g.role when 'editeur' then 1 when 'fisc' then 2 else 3 end) from public.platform_grants g where g.user_id = auth.uid()), '[]'::json),
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

revoke execute on function public.switch_platform_role(text) from public, anon;
grant execute on function public.switch_platform_role(text) to authenticated;
