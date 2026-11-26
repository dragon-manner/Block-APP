// Mirrors of the server's JSON (server/*.go). Keep field names in lockstep with the Go tags.

export type AppLimit = { target: string; minutes: number };
export type Quiet = { enabled: boolean; from: string; to: string; blockReels: boolean; categories: string[] };
export type Penalties = { overdueReels: number; overdueCoins: number; lockdown: boolean; giveUpCoins: number; giveUpReels: number };
export type Taper = { enabled: boolean; goal: number; weeklyDrop: number; started: string };
export type Rules = {
  reelLimit: number | null;
  appLimits: AppLimit[];
  quiet: Quiet;
  focusCategories: string[];
  penalties: Penalties;
  taper: Taper;
  categoryOverrides: Record<string, string>;
  dailyFocusGoal: number;
};

export type Phase = { kind: 'work' | 'break'; start: number; end: number };
export type Focus = {
  id: number; mode: 'focus' | 'pomodoro'; label: string; taskId: number;
  started: number; ends: number; workMin: number; breakMin: number; cycles: number;
  strict: boolean; categories: string[]; phases: Phase[]; seed: number; deviceId: number;
};

export type Enforce = {
  now: number; date: string; tz: string;
  reels: { limit: number | null; base: number | null; bonus: number; penalty: number; others: number; mine: number };
  limits: { target: string; minutes: number; others: number; mine: number }[];
  focus: Focus | null;
  lockdown: { active: boolean; tasks: string[]; categories: string[] };
  quiet: Quiet;
  passUntil: number;
  categoryOverrides: Record<string, string>;
  armed: boolean;
};

export type UsageRow = { target: string; name: string; seconds: number; opens: number; reels: number; byDevice: Record<string, number> };
export type DaySummary = {
  date: string; seconds: number; byDevice: Record<string, number>; reels: number; pickups: number;
  focusMin: number; apps: UsageRow[]; hours: Record<string, number[]>; reelLimit: number | null; result: string;
};

export type Task = {
  id: number; title: string; notes: string; due: number; est: number; focusMin: number;
  status: 'open' | 'done'; created: number; doneAt: number; penalized: boolean; debtCleared: boolean;
};

export type Wallet = { coins: number; xp: number; level: number; levelFloor: number; levelNext: number; streak: number; bestStreak: number; freezes: number };
export type ShopItem = { id: string; name: string; desc: string; price: number; left: number; disabled: string };
export type Device = { id: number; name: string; platform: string; lastSeen: number; status: any; online: boolean };

export type State = {
  user: { id: number; email: string; tz: string };
  deviceId: number;
  rules: Rules;
  pending: Rules | null;
  pendingFrom: string;
  enforce: Enforce;
  today: DaySummary;
  tasks: Task[];
  wallet: Wallet;
  shop: ShopItem[];
  devices: Device[];
  stats: { sessions: number; focusMinutes: number; tasksDone: number; withered: number; focusGoal: number };
};

export type SessionRow = { id: number; mode: string; label: string; taskId: number; started: number; ended: number; minutes: number; status: 'completed' | 'abandoned'; seed: number; reason: string };

export type Category = { id: string; name: string; distracting: boolean };
export type Target = { id: string; name: string; category: string; reels?: boolean; android: string[]; domains: string[]; desktop: string[] };
export type Catalog = { version: number; categories: Category[]; targets: Target[]; browsers: string[] };
