import { TestBed } from '@angular/core/testing';
import { BarChartComponent } from './bar-chart.component';

describe('BarChartComponent', () => {
  function render(data: { label: string; value: number }[]) {
    const f = TestBed.createComponent(BarChartComponent);
    f.componentRef.setInput('data', data);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  it('draws one bar per point, scaled to the largest value', () => {
    const el = render([{ label: '2026-10-01', value: 100 }, { label: '2026-10-02', value: 50 }, { label: '2026-10-03', value: 0 }]);
    const bars = Array.from(el.querySelectorAll('rect'));
    expect(bars.length).toBe(3);
    const h = bars.map(b => Number(b.getAttribute('height')));
    expect(h[0]).toBeGreaterThan(h[1]);
    expect(h[1]).toBeCloseTo(h[0] / 2, 5);
    expect(h[2]).toBe(0);
  });

  it('copes with all-zero and empty data', () => {
    expect(render([{ label: 'a', value: 0 }]).querySelectorAll('rect').length).toBe(1);
    expect(render([]).querySelectorAll('rect').length).toBe(0);
  });

  it('has an accessible name and per-bar tooltips', () => {
    const el = render([{ label: '2026-10-01', value: 7 }]);
    expect(el.querySelector('svg')?.getAttribute('role')).toBe('img');
    expect(el.querySelector('rect title')?.textContent).toContain('2026-10-01: 7');
  });
});
