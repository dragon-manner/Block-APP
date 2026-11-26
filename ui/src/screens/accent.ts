import { bridge } from '../bridge';

// Accents are earned by level. Cosmetic only: no reward in Keel may loosen a limit.
export const ACCENTS = [
  { id: 'lime', name: 'Lime', main: '#C6F432', ink: '#141A00', wash: '#263015', level: 1 },
  { id: 'mint', name: 'Mint', main: '#5CE6B0', ink: '#00281A', wash: '#16302A', level: 3 },
  { id: 'sky', name: 'Sky', main: '#7CC4FF', ink: '#00213A', wash: '#17283A', level: 5 },
  { id: 'lilac', name: 'Lilac', main: '#BFA3FF', ink: '#1E0E45', wash: '#28223D', level: 8 },
  { id: 'rose', name: 'Rose', main: '#FF8DB4', ink: '#3D0019', wash: '#3A1D28', level: 12 },
  { id: 'gold', name: 'Gold', main: '#FFD15C', ink: '#2E2000', wash: '#362D14', level: 20 },
];

export function currentAccent() {
  return bridge.pref('accent') || 'lime';
}

export function applyAccent(id = currentAccent()) {
  const a = ACCENTS.find((x) => x.id === id) ?? ACCENTS[0];
  const r = document.documentElement.style;
  r.setProperty('--lime', a.main);
  r.setProperty('--lime-ink', a.ink);
  r.setProperty('--lime-wash', a.wash);
  bridge.pref('accent', a.id);
}
