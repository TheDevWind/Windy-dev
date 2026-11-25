-- Vocabulaire des services : les contenus créés avec un service (formulaire de candidature, bibliothèque, examen) ne parlent plus du jeu
-- ni du nom du logiciel. Seules deux fonctions de modèles changent ; les services déjà créés ne sont pas modifiés.

create or replace function public.default_questions() returns jsonb
language sql immutable set search_path = '' as $$
  select '[{"q": "Ton âge et ton parcours", "long": false}, {"q": "Ton expérience (police, armée, sécurité, autre service)", "long": true},
    {"q": "Pourquoi veux-tu rejoindre les US Marshals ?", "long": true}, {"q": "Tes disponibilités (jours et heures)", "long": false},
    {"q": "As-tu lu le règlement du service ?", "long": false}, {"q": "Ta disponibilité hebdomadaire (en heures)", "long": false}]'::jsonb
$$;

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
    'Code 4 · Situation sous contrôle'), 1, 'Direction du service');
  insert into public.exams (org_id, title, pass_pct, module_id, questions, by_name) values (o, 'Codes radio', 80, radio, 5, 'Direction du service') returning id into eid;
  insert into public.exam_keys (exam_id, org_id, questions) values (eid, o, '[
    {"q": "Que signifie 10-4 ?", "choices": ["Reçu, compris", "Agent en détresse", "Demande de renfort", "Hors service"], "answer": 0},
    {"q": "Quel code pour demander des renforts ?", "choices": ["10-20", "10-32", "10-8", "10-99"], "answer": 1},
    {"q": "Que signifie 10-99 ?", "choices": ["Fin de service", "Vérification de plaque", "Agent en détresse, toutes les unités", "Poursuite à pied"], "answer": 2},
    {"q": "Quel code pour donner ta position ?", "choices": ["10-20", "10-6", "10-50", "10-28"], "answer": 0},
    {"q": "Que veut dire « Code 4 » ?", "choices": ["Situation sous contrôle", "Gyrophares et sirène", "Agent blessé", "Retour au poste"], "answer": 0}]'::jsonb);
end $$;
