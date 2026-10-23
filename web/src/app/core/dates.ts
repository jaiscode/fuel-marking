/** yyyy-MM-dd in the browser's local time zone (toISOString would shift the day for UTC+ users). */
export function isoDate(d: Date): string {
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

export function daysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return isoDate(d);
}

/** "5 min ago" from an age in seconds supplied by the server (so no clock or time-zone assumptions). */
export function formatAge(seconds: number | null): string {
  if (seconds === null || seconds === undefined) return 'never';
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86400)} d ago`;
}

/** Site-local timestamps are shown as recorded: "2026-10-14 03:59". */
export function formatTime(iso: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : '';
}
