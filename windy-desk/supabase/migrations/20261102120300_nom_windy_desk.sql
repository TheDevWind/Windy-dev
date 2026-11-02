-- Le produit s'appelle désormais Windy Desk (Windy Project) : seul ce message d'erreur portait l'ancien nom.
create or replace function public.require_editeur() returns text
language plpgsql stable security definer set search_path = '' as $$
begin
  if public.platform_role() is distinct from 'editeur' then raise exception 'Réservé à l’éditeur de Windy Desk.'; end if;
  return public.caller_name();
end $$;
