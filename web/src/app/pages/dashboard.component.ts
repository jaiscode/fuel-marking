import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { LookupsService } from '../core/lookups.service';
import { Overview, SiteStatus, SummaryRow } from '../core/models';
import { daysAgo, formatAge } from '../core/dates';
import { BarChartComponent } from '../shared/bar-chart.component';
import { fmt0, fmt1 } from '../shared/format';

type Preset = 'today' | '7d' | '30d';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [FormsModule, BarChartComponent],
  template: `
    <div class="page-head">
      <div><h1>Dashboard</h1><p>Marking activity across the sites that have reported in.</p></div>
      <div class="filters" style="margin:0">
        <div class="field"><label for="site">Site</label>
          <select id="site" [(ngModel)]="siteId" (ngModelChange)="load()">
            <option value="">All sites</option>
            @for (s of lookups.sites(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
          </select></div>
        <div class="field"><label for="range">Period</label>
          <select id="range" [ngModel]="preset()" (ngModelChange)="setPreset($event)">
            <option value="today">Today</option><option value="7d">Last 7 days</option><option value="30d">Last 30 days</option>
          </select></div>
      </div>
    </div>

    @if (o(); as ov) {
      <section class="grid kpis" aria-label="Key figures">
        <div class="card kpi"><div class="label">Trucks marked</div><div class="value">{{ fmt0(ov.trucks) }}</div>
          <div class="sub">{{ fmt0(ov.completed) }} verified</div></div>
        <div class="card kpi"><div class="label">Fuel marked</div><div class="value">{{ fmt0(ov.fuelLitres) }} <small>L</small></div>
          <div class="sub">declared volume</div></div>
        <div class="card kpi"><div class="label">Rum dosed</div><div class="value">{{ fmt1(ov.rumMl) }} <small>g</small></div>
          <div class="sub">{{ ov.fuelLitres > 0 ? fmt1(ov.rumMl / (ov.fuelLitres / 1000)) : '–' }} g per 1000 L</div></div>
        <div class="card kpi"><div class="label">Incidents</div><div class="value">{{ fmt0(ov.incidents) }}</div>
          <div class="sub">{{ fmt1(ov.incidentMl) }} g lost</div></div>
        <div class="card kpi"><div class="label">Open shifts</div><div class="value">{{ fmt0(ov.openShifts) }}</div>
          <div class="sub">{{ fmt0(ov.sitesReporting) }} site(s) marked in period</div></div>
      </section>

      <section class="card">
        <h2>Rum dosed per day (g)</h2>
        @if (chart().length) { <app-bar-chart [data]="chart()" ariaLabel="Rum dosed per day in millilitres" /> }
        @else { <div class="empty">No marking in this period.</div> }
      </section>

      <div class="grid two">
        <section class="card"><h2>By fuel type</h2>@if (fuel().length) { <div class="table-wrap"><table>
          <thead><tr><th>Fuel</th><th class="num">Trucks</th><th class="num">Fuel (L)</th><th class="num">Rum (g)</th></tr></thead>
          <tbody>@for (r of fuel(); track r.key) { <tr><td>{{ r.label }}</td><td class="num">{{ fmt0(r.trucks) }}</td>
            <td class="num">{{ fmt0(r.fuelLitres) }}</td><td class="num">{{ fmt1(r.rumMl) }}</td></tr> }</tbody></table></div> }
          @else { <div class="empty">Nothing yet.</div> }</section>
        <section class="card"><h2>By oil marketing company</h2>@if (omc().length) { <div class="table-wrap"><table>
          <thead><tr><th>Company</th><th class="num">Trucks</th><th class="num">Fuel (L)</th><th class="num">Rum (g)</th></tr></thead>
          <tbody>@for (r of omc(); track r.key) { <tr><td>{{ r.label }}</td><td class="num">{{ fmt0(r.trucks) }}</td>
            <td class="num">{{ fmt0(r.fuelLitres) }}</td><td class="num">{{ fmt1(r.rumMl) }}</td></tr> }</tbody></table></div> }
          @else { <div class="empty">Nothing yet.</div> }</section>
      </div>
    } @else {
      <div class="card empty">Loading…</div>
    }

    <section class="card" style="margin-top:16px">
      <h2>Site synchronisation</h2>
      <p class="muted" style="margin-top:-6px">A site that has not synced for over an hour may be offline.</p>
      <div class="table-wrap"><table>
        <thead><tr><th>Site</th><th>Type</th><th>Last worked at</th><th>Last sync</th><th>Shift</th><th class="num">Trucks today</th></tr></thead>
        <tbody>
          @for (s of sites(); track s.id) {
            <tr>
              <td>{{ s.name }} <span class="muted mono">{{ s.code }}</span>@if (!s.active) { <span class="badge">inactive</span> }</td>
              <td>{{ s.siteType.toLowerCase() }}</td>
              <td>{{ s.location || '–' }}</td>
              <td><span class="badge" [class]="syncTone(s)">{{ age(s) }}</span></td>
              <td>@if (s.shiftOpen) { <span class="badge info">open</span> } @else { <span class="muted">–</span> }</td>
              <td class="num">{{ fmt0(s.trucksToday) }}</td>
            </tr>
          } @empty { <tr><td colspan="5" class="empty">No sites configured. Create one under Administration → Sites.</td></tr> }
        </tbody></table></div>
    </section>
  `,
  styles: [`.kpi small { font-size: 14px; font-weight: 400; color: var(--muted); }`]
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly lookups = inject(LookupsService);
  readonly fmt0 = fmt0;
  readonly fmt1 = fmt1;

  siteId = '';
  preset = signal<Preset>('7d');
  o = signal<Overview | null>(null);
  fuel = signal<SummaryRow[]>([]);
  omc = signal<SummaryRow[]>([]);
  sites = signal<SiteStatus[]>([]);
  chart = computed(() => (this.o()?.byDay ?? []).map(d => ({ label: d.day, value: d.rumMl })).filter((_, __, a) => a.some(x => x.value > 0)));

  ngOnInit(): void { this.lookups.ensureLoaded(); this.load(); }

  setPreset(p: Preset): void { this.preset.set(p); this.load(); }

  load(): void {
    const from = this.preset() === 'today' ? daysAgo(0) : daysAgo(this.preset() === '7d' ? 6 : 29);
    const q = { siteId: this.siteId, from, to: daysAgo(0) };
    this.api.overview(q).subscribe(o => this.o.set(o));
    this.api.markingSummary('fuel', q).subscribe(r => this.fuel.set(r));
    this.api.markingSummary('omc', q).subscribe(r => this.omc.set(r));
    this.api.siteStatus().subscribe(s => this.sites.set(s));
  }

  age(s: SiteStatus): string { return formatAge(s.lastSyncAgeSeconds); }

  syncTone(s: SiteStatus): string {
    if (s.lastSyncAgeSeconds === null) return s.active ? 'warn' : '';
    return s.lastSyncAgeSeconds > 3600 ? 'warn' : 'ok';
  }
}
