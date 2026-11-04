-- Windy Desk · signatures des agents (services publics).
-- À la première connexion, l'agent écrit son nom tel qu'il signe et choisit un modèle de signature.
-- Un rapport garde la signature de son auteur (à la soumission) et celle du responsable qui le valide :
-- le document reste le même si l'agent change de signature plus tard.

alter table public.members
  add column signature_name text check (length(signature_name) between 2 and 40),
  add column signature_style text check (signature_style in ('classique', 'elegante', 'rapide', 'paraphe', 'manuscrite', 'moderne'));

alter table public.reports
  add column author_signature jsonb,
  add column reviewer_id uuid references public.members (id) on delete set null,
  add column reviewer_signature jsonb;
create index reports_reviewer on public.reports (reviewer_id);

-- la signature de l'agent, telle qu'elle s'imprime sur un document
create or replace function public.signature_of(m public.members) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('name', coalesce(m.signature_name, m.name), 'style', coalesce(m.signature_style, 'classique'), 'printed', m.name,
    'grade', (select g.name from public.grades g where g.id = m.grade_id), 'matricule', m.matricule)
$$;

create or replace function public.save_signature(p_org uuid, p_name text, p_style text) returns void
language plpgsql security definer set search_path = '' as $$
declare me public.members := public.caller_member(p_org);
begin
  if me.id is null then raise exception 'Accès refusé.'; end if;
  if length(trim(coalesce(p_name, ''))) < 2 or length(trim(p_name)) > 40 then raise exception 'Écris ton nom de signature (2 à 40 caractères).'; end if;
  if p_style not in ('classique', 'elegante', 'rapide', 'paraphe', 'manuscrite', 'moderne') then raise exception 'Choisis un modèle de signature.'; end if;
  update public.members set signature_name = trim(p_name), signature_style = p_style where id = me.id;
end $$;

-- l'espace de chacun indique s'il a déjà choisi sa signature
create or replace function public.my_spaces() returns json
language sql stable security definer set search_path = '' as $$
  select json_build_object(
    'role', (select r.role from public.platform_roles r where r.user_id = auth.uid()),
    'display_name', (select r.display_name from public.platform_roles r where r.user_id = auth.uid()),
    'orgs', coalesce((
      select json_agg(json_build_object('id', o.id, 'code', o.code, 'name', o.name, 'kind', o.kind, 'is_association', o.is_association, 'sector', o.sector,
        'id_prefix', o.id_prefix, 'modules', o.modules, 'licence_until', o.licence_until, 'member_id', m.id, 'member_name', m.name, 'grade', g.name,
        'signature_name', m.signature_name, 'signature_style', m.signature_style,
        'caps', case when g.rank = (select max(g2.rank) from public.grades g2 where g2.org_id = o.id) then public.all_caps() else g.caps end) order by o.name)
      from public.members m join public.orgs o on o.id = m.org_id join public.grades g on g.id = m.grade_id
      where m.user_id = auth.uid() and m.active), '[]'::json))
$$;

-- soumettre : la signature de l'auteur est apposée sur le rapport
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
  update public.reports set status = 'soumis', submitted_at = now(), author_signature = public.signature_of(me), reviewer_id = null, reviewer_signature = null where id = r.id;
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

-- valider, c'est signer : la signature du responsable est apposée au bas du rapport
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
  update public.reports set status = case when p_ok then 'valide' else 'a_corriger' end, reviewer = me.name, review_note = left(note, 1000), reviewed_at = now(),
    reviewer_id = case when p_ok then me.id end, reviewer_signature = case when p_ok then public.signature_of(me) end where id = r.id;
  insert into public.journal (org_id, who, what) values (r.org_id, me.name, 'Rapport ' || r.num || case when p_ok then ' validé et signé' else ' renvoyé pour correction' end);
  if r.case_id is not null and p_ok then
    insert into public.case_log (case_id, org_id, kind, text, member_id, by_name) values (r.case_id, r.org_id, 'note', 'Rapport ' || r.num || ' validé par ' || me.name, me.id, me.name);
  end if;
end $$;

revoke execute on function public.signature_of(public.members) from public, anon, authenticated;
revoke execute on function public.save_signature(uuid, text, text) from public, anon;
grant execute on function public.save_signature(uuid, text, text) to authenticated;
