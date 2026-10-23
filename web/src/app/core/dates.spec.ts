import { daysAgo, formatAge, formatTime, isoDate } from './dates';

describe('dates', () => {
  it('formats a local date without shifting the day', () => {
    expect(isoDate(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
    expect(isoDate(new Date(2026, 11, 31, 0, 0))).toBe('2026-12-31');
  });

  it('computes days ago in local time', () => {
    const d = new Date();
    d.setDate(d.getDate() - 3);
    expect(daysAgo(3)).toBe(isoDate(d));
  });

  it('describes a server-supplied age', () => {
    expect(formatAge(null)).toBe('never');
    expect(formatAge(5)).toBe('just now');
    expect(formatAge(120)).toBe('2 min ago');
    expect(formatAge(7300)).toBe('2 h ago');
    expect(formatAge(2 * 86400 + 5)).toBe('2 d ago');
  });

  it('shows site-local timestamps as recorded', () => {
    expect(formatTime('2026-10-14T03:59:39.518316')).toBe('2026-10-14 03:59');
    expect(formatTime(null)).toBe('');
  });
});
