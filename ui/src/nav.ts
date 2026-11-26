import { signal } from '@preact/signals';

export type Route = 'today' | 'focus' | 'tasks' | 'insights' | 'grove' | 'rewards' | 'limits' | 'devices';
export const ROUTES: Route[] = ['today', 'focus', 'tasks', 'insights', 'grove', 'rewards', 'limits', 'devices'];

const initial = location.hash.slice(1) as Route;
export const route = signal<Route>(ROUTES.includes(initial) ? initial : 'today');

export function go(r: Route) {
  route.value = r;
  history.replaceState(null, '', '#' + r);
  document.querySelector('main')?.scrollTo({ top: 0 });
  window.scrollTo({ top: 0 });
}
