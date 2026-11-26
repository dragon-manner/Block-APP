// A tree grown from a session's seed: the same session always draws the same tree, on every
// device. Longer sessions grow taller, fuller trees; a session you gave up on stays as a bare,
// grey, leaning one in the grove. Flat colours only, to match the rest of the app.

function rng(seed: number) {
  let a = seed >>> 0 || 1;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const LEAF = [
  ['#C6F432', '#9BC523', '#E2FF8A'], // lime oak
  ['#5CE6B0', '#36B88A', '#9EF5D4'], // mint pine
  ['#FF8DB4', '#F0C2D2', '#FFE3EC'], // blossom
  ['#FFD15C', '#E8A93A', '#FFE7A6'], // autumn
  ['#7CC4FF', '#4E9BDB', '#BFE2FF'], // frost
];
const BARK = '#7A6A55';
const DEAD = '#55594F';

export const SPECIES = ['Lime oak', 'Mint pine', 'Blossom', 'Amber maple', 'Frost willow'];
export function speciesOf(seed: number) {
  return Math.abs(seed) % LEAF.length;
}

type Seg = { x1: number; y1: number; x2: number; y2: number; w: number; d: number };

export function Tree({ seed, growth = 1, minutes = 25, withered = false, size = 64, title }: {
  seed: number; growth?: number; minutes?: number; withered?: boolean; size?: number; title?: string;
}) {
  const r = rng(seed);
  const sp = speciesOf(seed);
  const pal = LEAF[sp];
  const scale = 0.55 + 0.45 * Math.min(1, minutes / 90);
  const g = Math.max(0.04, Math.min(1, growth));
  const maxDepth = sp === 1 ? 0 : 4;
  const depthLimit = g * (maxDepth + 1);
  const segs: Seg[] = [];
  const tips: { x: number; y: number; d: number }[] = [];
  const lean = withered ? (r() > 0.5 ? 9 : -9) : (r() - 0.5) * 6;

  const branch = (x: number, y: number, ang: number, len: number, d: number, w: number) => {
    if (d > depthLimit) return;
    const frac = Math.min(1, depthLimit - d);
    const rad = (ang * Math.PI) / 180;
    const x2 = x + Math.sin(rad) * len * frac;
    const y2 = y - Math.cos(rad) * len * frac;
    segs.push({ x1: x, y1: y, x2, y2, w, d });
    // Leaves on the outer two levels, not only the tips, so crowns read as full and round
    // rather than as a fringe on top of bare sticks.
    if (d >= maxDepth - 1) tips.push({ x: x2, y: y2, d });
    if (d >= maxDepth || frac < 1) return;
    const n = sp === 4 ? 3 : 2 + (r() > 0.6 ? 1 : 0);
    for (let i = 0; i < n; i++) {
      const spread = sp === 4 ? 38 : 22 + r() * 14;
      const a = ang + (i - (n - 1) / 2) * spread + (r() - 0.5) * 14;
      branch(x2, y2, a, len * (0.68 + r() * 0.12), d + 1, w * 0.66);
    }
  };

  const trunk = 25 * scale;
  if (sp === 1) branch(50, 92, lean, trunk * 1.5, 0, 4.2 * scale);
  else branch(50, 92, lean, trunk, 0, 5 * scale);

  const leafGrowth = Math.max(0, Math.min(1, (g - 0.35) / 0.65));
  const leaves: any[] = [];
  if (!withered && leafGrowth > 0) {
    if (sp === 1) {
      // Pine: stacked tiers along the trunk.
      const top = segs[0];
      const tiers = 4;
      for (let i = 0; i < tiers; i++) {
        const t = 0.25 + (i / tiers) * 0.75;
        const cx = top.x1 + (top.x2 - top.x1) * t;
        const cy = top.y1 + (top.y2 - top.y1) * t;
        const wdt = (26 - i * 5) * scale * leafGrowth;
        const h = 18 * scale * leafGrowth;
        leaves.push(<path key={'p' + i} d={`M${cx - wdt} ${cy + h * 0.35} L${cx} ${cy - h} L${cx + wdt} ${cy + h * 0.35} Z`} fill={pal[i % 2]} />);
      }
    } else {
      tips.forEach((t, i) => {
        const rr = (sp === 4 ? 5 : t.d < maxDepth ? 10 + (i % 3) : 7 + (i % 3)) * scale * leafGrowth;
        leaves.push(<circle key={'l' + i} cx={t.x} cy={sp === 4 ? t.y + rr : t.y} r={rr} fill={pal[i % 2]} />);
        if (sp === 2 && i % 2 === 0) leaves.push(<circle key={'b' + i} cx={t.x + rr * 0.4} cy={t.y - rr * 0.3} r={rr * 0.32} fill={pal[2]} />);
      });
    }
  }

  return (
    <svg viewBox="0 0 100 100" width={size} height={size} role="img" aria-label={title ?? (withered ? 'Withered tree' : SPECIES[sp])}>
      {title && <title>{title}</title>}
      <ellipse cx="50" cy="93" rx={22 * scale} ry="3.2" fill="#23261F" />
      {segs.map((s, i) => (
        <line key={i} x1={s.x1} y1={s.y1} x2={s.x2} y2={s.y2} stroke={withered ? DEAD : BARK} stroke-width={Math.max(0.8, s.w)} stroke-linecap="round" />
      ))}
      {leaves}
      {withered && tips.slice(0, 3).map((t, i) => <circle key={'d' + i} cx={t.x + 2} cy={t.y + 8 + i * 3} r="1.6" fill={DEAD} />)}
    </svg>
  );
}
