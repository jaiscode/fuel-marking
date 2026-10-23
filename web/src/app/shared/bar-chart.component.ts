import { Component, computed, input } from '@angular/core';

export interface Bar { label: string; value: number; }

/** Minimal accessible SVG bar chart; no charting library needed for one series. */
@Component({
  selector: 'app-bar-chart',
  standalone: true,
  template: `
    <svg [attr.viewBox]="'0 0 ' + width + ' ' + height" role="img" [attr.aria-label]="ariaLabel()" class="chart">
      @for (g of grid(); track g.y) {
        <line [attr.x1]="left" [attr.x2]="width" [attr.y1]="g.y" [attr.y2]="g.y" class="grid" />
        <text [attr.x]="left - 6" [attr.y]="g.y + 4" text-anchor="end" class="tick">{{ g.text }}</text>
      }
      @for (b of bars(); track b.i) {
        <rect [attr.x]="b.x" [attr.y]="b.y" [attr.width]="b.w" [attr.height]="b.h" rx="3" class="bar">
          <title>{{ b.label }}: {{ b.value }}</title>
        </rect>
        @if (b.showLabel) {
          <text [attr.x]="b.x + b.w / 2" [attr.y]="height - 6" text-anchor="middle" class="tick">{{ b.short }}</text>
        }
      }
    </svg>
  `,
  styles: [`
    .chart { width: 100%; height: auto; display: block; }
    .grid { stroke: var(--border); stroke-width: 1; }
    .tick { fill: var(--muted); font-size: 11px; }
    .bar { fill: var(--accent); }
    .bar:hover { filter: brightness(1.15); }
  `]
})
export class BarChartComponent {
  data = input.required<Bar[]>();
  ariaLabel = input('Bar chart');

  readonly width = 640;
  readonly height = 200;
  readonly left = 44;
  private readonly top = 10;
  private readonly bottom = 24;

  private max = computed(() => {
    const m = Math.max(0, ...this.data().map(d => d.value));
    if (m === 0) return 1;
    const pow = Math.pow(10, Math.floor(Math.log10(m)));
    return Math.ceil(m / pow) * pow; // round the axis up to a "nice" number
  });

  grid = computed(() => {
    const plotH = this.height - this.top - this.bottom;
    return [0, 0.5, 1].map(f => ({ y: this.top + plotH * (1 - f), text: this.fmt(this.max() * f) }));
  });

  bars = computed(() => {
    const d = this.data();
    const plotW = this.width - this.left;
    const plotH = this.height - this.top - this.bottom;
    const slot = plotW / Math.max(1, d.length);
    const w = Math.max(2, Math.min(40, slot * 0.7));
    const step = Math.ceil(d.length / 10); // thin out x labels on long ranges
    return d.map((p, i) => {
      const h = (p.value / this.max()) * plotH;
      return {
        i, label: p.label, value: p.value, w, h,
        x: this.left + slot * i + (slot - w) / 2, y: this.top + plotH - h,
        showLabel: i % step === 0, short: p.label.length > 5 ? p.label.slice(5) : p.label
      };
    });
  });

  private fmt(v: number): string {
    return v >= 1000 ? `${(v / 1000).toFixed(v % 1000 === 0 ? 0 : 1)}k` : String(Math.round(v * 10) / 10);
  }
}
