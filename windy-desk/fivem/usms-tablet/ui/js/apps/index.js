import consoleApp from './console.js';
import notes from './notes.js';
import camera from './camera.js';
import photos from './photos.js';
import calculator from './calculator.js';
import clock from './clock.js';
import location from './location.js';
import codes from './codes.js';
import reminders from './reminders.js';
import contacts from './contacts.js';
import settings from './settings.js';

export const APPS = Object.fromEntries([consoleApp, notes, camera, photos, calculator, clock, location, codes, reminders, contacts, settings].map(a => [a.id, a]));
export const DOCK = ['console', 'notes', 'camera', 'photos', 'calculator'];
export const GRID = ['clock', 'location', 'codes', 'reminders', 'contacts', 'settings'];
