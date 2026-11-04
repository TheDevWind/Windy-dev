-- Windy Desk · stockage des services publics.
--   mdt  : privé. Photos des agents et des individus, pièces jointes des dossiers. Rangé par service : <id du service>/...
--          Lu et déposé par les membres du service ; jamais modifié ni supprimé (les pièces d'un dossier restent).
--   avis : public. Affiches Most Wanted de la page publique ; déposées par ceux qui ont l'accès « wanted ».
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('mdt', 'mdt', false, 52428800, array['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'video/mp4', 'video/webm', 'application/pdf', 'text/plain']),
  ('avis', 'avis', true, 5242880, array['image/png', 'image/jpeg', 'image/webp'])
on conflict (id) do nothing;

create policy windy_mdt_read on storage.objects for select to authenticated using (bucket_id = 'mdt' and public.member_of_path(name));
create policy windy_mdt_insert on storage.objects for insert to authenticated with check (bucket_id = 'mdt' and public.member_of_path(name));
create policy windy_avis_read on storage.objects for select to authenticated using (bucket_id = 'avis' and public.member_of_path(name));
create policy windy_avis_insert on storage.objects for insert to authenticated with check (bucket_id = 'avis' and public.cap_of_path(name, 'wanted'));
