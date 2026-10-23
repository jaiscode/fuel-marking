import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { LookupsService } from '../core/lookups.service';
import { IncidentRow, Page } from '../core/models';
import { daysAgo, formatTime } from '../core/dates';
import { PagerComponent } from '../shared/pager.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { fmt1 } from '../shared/format';

@Component({
  selector: 'app-incidents',
  standalone: true,
  imports: [FormsModule, PagerComponent, StatusBadgeComponent],
  template: `
    <div class="page-head"><div><h1>Incidents</h1><p>Spillage, leakage and other losses of rum reported at the sites, and which rum it was.</p></div></div>
    <div class="filters">
      <div class="field"><label for="s">Site</label><select id="s" [(ngModel)]="f.siteId" (ngModelChange)="apply()"><option value="">All</option>
        @for (s of lookups.sites(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }</select></div>
      <div class="field"><label for="t">Type</label><select id="t" [(ngModel)]="f.type" (ngModelChange)="apply()"><option value="">All</option>
        <option value="SPILLAGE">Spillage</option><option value="LEAKAGE">Leakage</option><option value="OTHER">Other</option></select></div>
      <div class="field"><label for="rt">Rum type</label><select id="rt" [(ngModel)]="f.rumType" (ngModelChange)="apply()"><option value="">All</option>
        @for (r of lookups.rumTypes(); track r.id) { <option [value]="r.code">{{ r.name }}</option> }</select></div>
      <div class="field"><label for="fr">From</label><input id="fr" type="date" [(ngModel)]="f.from" (ngModelChange)="apply()" /></div>
      <div class="field"><label for="to">To</label><input id="to" type="date" [(ngModel)]="f.to" (ngModelChange)="apply()" /></div>
    </div>
    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>When</th><th>Site</th><th>Type</th><th class="num">Lost (g)</th><th>Rum type</th><th>Container</th><th>Reported by</th><th>What happened</th></tr></thead>
      <tbody>
        @for (i of data()?.items ?? []; track i.id) {
          <tr><td>{{ time(i.occurredAt) }}</td><td>{{ i.siteName }}</td><td><app-status-badge [value]="i.type" /></td>
            <td class="num">{{ fmt1(i.quantityMl) }}</td><td>@if (i.rumType) { <span class="swatch" [style.background]="rumColor(i.rumType)"></span> {{ rumName(i.rumType) }} } @else { <span class="muted">–</span> }</td><td class="mono">{{ i.container || '–' }}</td><td>{{ i.reportedBy }}</td>
            <td class="wrap">{{ i.description || '–' }}</td></tr>
        } @empty { <tr><td colspan="8" class="empty">No incidents in this period. 🎉</td></tr> }
      </tbody></table></div></div>
    @if (data(); as d) { <app-pager [page]="d.page" [size]="d.size" [total]="d.total" (pageChange)="goto($event)" /> }
  `
})
export class IncidentsComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly lookups = inject(LookupsService);
  readonly fmt1 = fmt1;
  readonly time = formatTime;
  f = { siteId: '', type: '', rumType: '', from: daysAgo(89), to: '' };

  /** The rum type's name and colour, from the master data lists. */
  rumName(code: string): string { return this.lookups.rumTypes().find(r => r.code === code)?.name ?? code; }
  rumColor(code: string): string { return this.lookups.rumTypes().find(r => r.code === code)?.color ?? '#808080'; }
  data = signal<Page<IncidentRow> | null>(null);
  private page = 0;

  ngOnInit(): void { this.lookups.ensureLoaded(); this.load(); }
  apply(): void { this.page = 0; this.load(); }
  goto(p: number): void { this.page = p; this.load(); }
  private load(): void { this.api.incidents({ ...this.f, page: this.page, size: 25 }).subscribe(d => this.data.set(d)); }
}
