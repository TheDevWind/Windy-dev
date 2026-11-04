// Catalogue vendu par l'éditeur : les mêmes modules et les mêmes prix que la table public.modules.
export const MODULES = [
  { id: 'stock', name: 'Stock', icon: 'boite', price: 250, text: 'Articles, quantités, seuils d’alerte, emplacements et historique des mouvements.' },
  { id: 'fabrication', name: 'Fabrication', icon: 'fiole', price: 300, text: 'Recettes, ordres de fabrication et coût de revient. Les ingrédients sont pris dans le stock.' },
  { id: 'recolte', name: 'Récolte', icon: 'epi', price: 200, text: 'Saisie des récoltes par employé, classement de la semaine et primes au rendement.' },
  { id: 'caisse', name: 'Caisse', icon: 'caisse', price: 350, text: 'Carte, encaissement rapide et tickets. Chaque vente décompte ses ingrédients du stock.' },
  { id: 'factures', name: 'Factures', icon: 'facture', price: 200, text: 'Factures entre entreprises, échéances et relances. Une facture payée entre en comptabilité.' },
  { id: 'service', name: 'Service et paie', icon: 'horloge', price: 250, text: 'Prise et fin de service, heures de la semaine, salaires et paie en un clic.' },
  { id: 'dons', name: 'Dons et distributions', icon: 'coeur', price: 100, text: 'Dons en argent ou en nature, distributions aux bénéficiaires, bénévoles. Tarif association.' },
  { id: 'employes', name: 'Employés', icon: 'badge', price: 200, text: 'Fiches du personnel : contrat, téléphone, permis, embauche et licenciement, avertissements internes.' },
  { id: 'vehicules', name: 'Véhicules', icon: 'camion', price: 200, text: 'La flotte : qui a quel véhicule, carburant, dégâts, réparations et assurance.' },
  { id: 'evenements', name: 'Événements', icon: 'calendrier', price: 150, text: 'Des événements temporaires avec leur propre vue : dates, équipe, stock réservé, objectif et bilan en direct.' }
];
// Modules des services publics (US Marshals) : mêmes identifiants et prix que public.modules (sector = 'service_public').
export const MDT_MODULES = [
  { id: 'effectifs', name: 'Effectifs', icon: 'badge', price: 300, text: 'Fiches agents (matricule, indicatif, unités, qualifications), organigramme, carrière, départs et liste noire.' },
  { id: 'presence', name: 'Présence', icon: 'horloge', price: 150, text: 'Prise de service et statut en direct, absences validées, planning des opérations, classement et inactivité.' },
  { id: 'dossiers', name: 'Dossiers', icon: 'dossier', price: 350, text: 'Individus recherchés et dossiers d’enquête : statut, priorité, agents, journal et pièces jointes.' },
  { id: 'mandats', name: 'Mandats', icon: 'marteau', price: 200, text: 'Demandes de mandat envoyées à la Justice, signature, expiration et exécution.' },
  { id: 'wanted', name: 'Most Wanted', icon: 'cible', price: 200, text: 'Classement des plus recherchés, historique de la semaine, affiche, page publique et signalements anonymes.' },
  { id: 'annonces', name: 'Annonces', icon: 'megaphone', price: 100, text: 'Annonces du commandement avec accusé de lecture.' },
  { id: 'rapports', name: 'Rapports', icon: 'rapport', price: 250, text: 'Rapports guidés (arrestation, SOG, transfert, incident, usage de la force, audience, libre), validation, export PDF ou PNG, code pénal et calcul des peines.' },
  { id: 'preuves', name: 'Preuves et saisies', icon: 'scelle', price: 150, text: 'Registre des scellés, chaîne de possession, statut et inventaire des saisies : armes, drogue, argent, véhicules.' },
  { id: 'transferts', name: 'Transferts de détenus', icon: 'camion', price: 200, text: 'Ordres de transfert, convoi, itinéraires réservés au convoi, suivi en direct et registre des détenus.' },
  { id: 'tribunal', name: 'Tribunal et protection', icon: 'balance', price: 200, text: 'Calendrier des audiences, plan de sécurité, magistrats protégés et registre des menaces.' },
  { id: 'witsec', name: 'WITSEC', icon: 'cadenas', price: 250, text: 'Témoins protégés sous code, nom révélé seulement par action volontaire et journalisée, planques, escortes, alertes au Director.' },
  { id: 'sog', name: 'SOG', icon: 'bouclier', price: 200, text: 'Opérateurs certifiés, activation et alerte, limite de sorties, briefing, retour d’expérience, tests et recertifications.' },
  { id: 'renseignement', name: 'Renseignement', icon: 'reseau', price: 200, text: 'Informateurs sous nom de code, fiabilité, paiements, notes classifiées et graphe des relations.' }
];
export const modulesFor = sector => sector === 'service_public' ? MDT_MODULES : MODULES;
export const moduleOf = id => MODULES.concat(MDT_MODULES).find(m => m.id === id) || { id, name: id, icon: 'grille', price: 0, text: '' };
export const PACKS = {
  Commerce: ['stock', 'fabrication', 'caisse', 'service', 'employes', 'evenements'],
  Ferme: ['stock', 'fabrication', 'recolte', 'factures', 'service', 'employes', 'vehicules'],
  Association: ['stock', 'dons', 'evenements'],
  Service: ['factures', 'service', 'employes', 'vehicules']
};
/** Les accès d'un grade, tels que la base les connaît (public.all_caps), avec leur libellé. */
export const CAPS = [['encaisser', 'Encaisser'], ['stock', 'Gérer le stock'], ['fabriquer', 'Fabriquer'], ['compta', 'Voir et tenir la comptabilité'], ['declarer', 'Déclarer au fisc'],
  ['paie', 'Payer les salaires'], ['equipe', 'Gérer l’équipe et les réglages'], ['vehicules', 'Véhicules'], ['evenements', 'Événements']];
/** Les accès d'un grade dans un service public. */
export const MDT_CAPS = [['effectifs', 'Gérer les effectifs (recrutement, grades, carrière, départs)'], ['valider', 'Valider les absences et organiser le planning'], ['dossiers', 'Créer et suivre les dossiers'],
  ['mandats', 'Demander des mandats'], ['wanted', 'Gérer le Most Wanted et les signalements'], ['annonces', 'Publier des annonces'], ['affaires', 'Affaires internes'], ['transferts', 'Planifier les transferts et tenir le registre des détenus'], ['tribunal', 'Audiences, magistrats protégés et menaces'],
  ['witsec', 'WITSEC et son journal d’audit (en plus de l’unité WITSEC)'], ['sog', 'Activer et diriger le SOG'], ['renseignement', 'Informateurs et notes secrètes'], ['equipe', 'Réglages du service, code pénal et comptes']];
export const capsFor = sector => sector === 'service_public' ? MDT_CAPS : CAPS;
export const CATEGORIES = { in: ['Ventes', 'Factures', 'Dons', 'Subventions', 'Divers'], out: ['Achats', 'Salaires', 'Loyer', 'Licence', 'Entretien', 'Divers'] };
export const DAYS = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'];
export const quoteTotal = q => {
  const week1 = q.modules.reduce((a, id) => a + moduleOf(id).price, 0), gross = week1 * q.weeks;
  return { week1, gross, cut: Math.round(gross * q.discount / 100), total: Math.round(gross * (1 - q.discount / 100)) };
};
