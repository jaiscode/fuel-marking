const nf0 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const nf1 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });

export const fmt0 = (v: number | null | undefined): string => (v === null || v === undefined ? '–' : nf0.format(v));
export const fmt1 = (v: number | null | undefined): string => (v === null || v === undefined ? '–' : nf1.format(v));

const nf3 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
/** Container quantities arrive in grams but are read in kilograms (a jerrican holds 18-22 kg, an IBC 900-1000 kg). */
export const fmtKg = (grams: number | null | undefined): string => (grams === null || grams === undefined ? '–' : nf3.format(grams / 1000));
