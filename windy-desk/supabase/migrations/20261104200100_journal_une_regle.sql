-- Windy Desk · une seule règle de lecture du journal (l'accès « équipe » ou les Affaires internes), plutôt que deux évaluées à chaque lecture.
drop policy journal_ia_read on public.journal;
drop policy journal_read on public.journal;
create policy journal_read on public.journal for select to authenticated using (public.has_cap(org_id, 'equipe') or public.ia_access(org_id));
