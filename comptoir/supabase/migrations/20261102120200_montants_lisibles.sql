-- Montants écrits « à la française » dans les libellés et le journal : 1 500 $ et 1 500,50 $ (au lieu de 1500.00 $).
-- Le motif de to_char utilise « , » et « . » littéraux : le résultat ne dépend pas de la langue du serveur.
create or replace function public.fmt_money(x numeric) returns text
language sql immutable set search_path = '' as $$
  select regexp_replace(replace(replace(to_char(round(x, 2), 'FM999,999,999,990.00'), ',', chr(160)), '.', ','), ',00$', '') || chr(160) || '$'
$$;
revoke execute on function public.fmt_money(numeric) from public, anon, authenticated;

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
      values (p_org, 'out', 'Impôts', 'Impôt de la semaine ' || extract(week from w)::integer || case when fee > 0 then ' (dont ' || public.fmt_money(fee) || ' de pénalité de retard)' else '' end, tax + fee, me.id, me.name);
  end if;
  insert into public.journal (org_id, who, what) values (p_org, me.name, 'Déclaration de la semaine ' || extract(week from w)::integer || ' · ' || public.fmt_money(tax + fee));
  return json_build_object('tax', tax, 'late_fee', fee);
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
  insert into public.journal (org_id, who, what, by_fisc) values (p_org, who, 'Redressement de ' || public.fmt_money(p_amount) || ' : ' || trim(p_reason), true);
end $$;
