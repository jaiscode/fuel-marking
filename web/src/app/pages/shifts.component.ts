import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { LookupsService } from '../core/lookups.service';
import { Page, ShiftRow } from '../core/models';
import { daysAgo, formatTime } from '../core/dates';
import { PagerComponent } from '../shared/pager.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { fmt1 } from '../shared/format';

@Component({
  selector: 'app-shifts',
  standalone: true,
  imports: [FormsModule, PagerComponent, StatusBadgeComponent],
  template: `
    <div class="page-head"><div><h1>Shifts</h1><p>Shift reconciliation of rum in opened jerricans. A non-zero variance means rum is unaccounted for.</p></div></div>
    <div class="filters">
      <div class="field"><label for="s">Site</label><select id="s" [(ngModel)]="f.siteId" (ngModelChange)="apply()"><option value="">All</option>
        @for (s of lookups.sites(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }</select></div>
      <div class="field"><label for="lc">Location</label><select id="lc" [(ngModel)]="f.locationId" (ngModelChange)="apply()"><option value="">All</option>
        @for (l of lookups.locations(); track l.id) { <option [value]="l.id">{{ l.name }}</option> }</select></div>
      <div class="field"><label for="st">Status</label><select id="st" [(ngModel)]="f.status" (ngModelChange)="apply()"><option value="">All</option>
        <option value="OPEN">Open</option><option value="CLOSED">Closed</option></select></div>
      <div class="field"><label for="fr">From</label><input id="fr" type="date" [(ngModel)]="f.from" (ngModelChange)="apply()" /></div>
      <div class="field"><label for="to">To</label><input id="to" type="date" [(ngModel)]="f.to" (ngModelChange)="apply()" /></div>
    </div>
    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Site</th><th>Location</th><th>Supervisor</th><th>Started</th><th>Ended</th><th>Status</th>
        <th class="num">Opening (g)</th><th class="num">Closing (g)</th><th class="num">Variance (g)</th></tr></thead>
      <tbody>
        @for (s of data()?.items ?? []; track s.id) {
          <tr><td>{{ s.siteName }}</td><td>{{ s.location || '–' }}</td><td>{{ s.supervisor }}</td><td>{{ time(s.startedAt) }}</td><td>{{ time(s.endedAt) || '–' }}</td>
            <td><app-status-badge [value]="s.status" /></td><td class="num">{{ fmt1(s.openingMl) }}</td>
            <td class="num">{{ fmt1(s.closingMl) }}</td>
            <td class="num">@if (s.varianceMl === null) { – } @else if (abs(s.varianceMl) < 0.05) { <span class="badge ok">0</span> } @else { <span class="badge bad">{{ fmt1(s.varianceMl) }}</span> }</td></tr>
        } @empty { <tr><td colspan="9" class="empty">No shifts in this period.</td></tr> }
      </tbody></table></div></div>
    @if (data(); as d) { <app-pager [page]="d.page" [size]="d.size" [total]="d.total" (pageChange)="goto($event)" /> }
  `
})
export class ShiftsComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly lookups = inject(LookupsService);
  readonly fmt1 = fmt1;
  readonly time = formatTime;
  readonly abs = Math.abs;
  f = { siteId: '', locationId: '', status: '', from: daysAgo(29), to: '' };
  data = signal<Page<ShiftRow> | null>(null);
  private page = 0;

  ngOnInit(): void { this.lookups.ensureLoaded(); this.load(); }
  apply(): void { this.page = 0; this.load(); }
  goto(p: number): void { this.page = p; this.load(); }
  private load(): void { this.api.shifts({ ...this.f, page: this.page, size: 25 }).subscribe(d => this.data.set(d)); }
}
