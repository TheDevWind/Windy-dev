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
export const moduleOf = id => MODULES.find(m => m.id === id) || { id, name: id, icon: 'grille', price: 0, text: '' };
export const PACKS = {
  Commerce: ['stock', 'fabrication', 'caisse', 'service', 'employes', 'evenements'],
  Ferme: ['stock', 'fabrication', 'recolte', 'factures', 'service', 'employes', 'vehicules'],
  Association: ['stock', 'dons', 'evenements'],
  Service: ['factures', 'service', 'employes', 'vehicules']
};
/** Les accès d'un grade, tels que la base les connaît (public.all_caps), avec leur libellé. */
export const CAPS = [['encaisser', 'Encaisser'], ['stock', 'Gérer le stock'], ['fabriquer', 'Fabriquer'], ['compta', 'Voir et tenir la comptabilité'], ['declarer', 'Déclarer au fisc'],
  ['paie', 'Payer les salaires'], ['equipe', 'Gérer l’équipe et les réglages'], ['vehicules', 'Véhicules'], ['evenements', 'Événements']];
export const CATEGORIES = { in: ['Ventes', 'Factures', 'Dons', 'Subventions', 'Divers'], out: ['Achats', 'Salaires', 'Loyer', 'Licence', 'Entretien', 'Divers'] };
export const DAYS = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'];
export const quoteTotal = q => {
  const week1 = q.modules.reduce((a, id) => a + moduleOf(id).price, 0), gross = week1 * q.weeks;
  return { week1, gross, cut: Math.round(gross * q.discount / 100), total: Math.round(gross * (1 - q.discount / 100)) };
};
