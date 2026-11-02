-- Recommandation des conseillers Supabase : figer la recherche de schéma de toutes les fonctions.
alter function public.all_caps() set search_path = '';
alter function public.week_of(timestamptz) set search_path = '';
alter function public.week_bounds(date) set search_path = '';
comment on table public.invitations is 'Aucune règle d’accès voulue : lecture et écriture seulement par les fonctions invite_member, renew_invitation, invitation_info, accept_invitation.';
comment on table public.platform_claims is 'Aucune règle d’accès voulue : liens à usage unique lus seulement par claim_platform_role.';
