import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { LookupsService } from '../core/lookups.service';
import { MARKING_STATUSES, MarkingRow, Page } from '../core/models';
import { daysAgo, formatTime } from '../core/dates';
import { PagerComponent } from '../shared/pager.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { fmt0, fmt1 } from '../shared/format';

@Component({
  selector: 'app-markings',
  standalone: true,
  imports: [FormsModule, RouterLink, PagerComponent, StatusBadgeComponent],
  template: `
    <div class="page-head"><div><h1>Markings</h1><p>Every truck registered for marking, newest first.</p></div></div>

    <div class="filters">
      <div class="field"><label for="q">Search</label><input id="q" placeholder="Order or truck no." [(ngModel)]="f.q" (keyup.enter)="apply()" /></div>
      <div class="field"><label for="s">Site</label><select id="s" [(ngModel)]="f.siteId" (ngModelChange)="apply()">
        <option value="">All</option>@for (s of lookups.sites(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }</select></div>
      <div class="field"><label for="o">Oil company</label><select id="o" [(ngModel)]="f.omcId" (ngModelChange)="apply()">
        <option value="">All</option>@for (o of lookups.omcs(); track o.id) { <option [value]="o.id">{{ o.name }}</option> }</select></div>
      <div class="field"><label for="fu">Fuel</label><select id="fu" [(ngModel)]="f.fuel" (ngModelChange)="apply()">
        <option value="">All</option>@for (x of lookups.fuelTypes(); track x.id) { <option [value]="x.code">{{ x.name }}</option> }</select></div>
      <div class="field"><label for="st">Status</label><select id="st" [(ngModel)]="f.status" (ngModelChange)="apply()">
        <option value="">All</option>@for (x of statuses; track x) { <option [value]="x">{{ x }}</option> }</select></div>
      <div class="field"><label for="fr">From</label><input id="fr" type="date" [(ngModel)]="f.from" (ngModelChange)="apply()" /></div>
      <div class="field"><label for="to">To</label><input id="to" type="date" [(ngModel)]="f.to" (ngModelChange)="apply()" /></div>
      <button type="button" (click)="reset()">Reset</button>
    </div>

    <div class="card" style="padding:0">
      <div class="table-wrap"><table>
        <thead><tr><th>Registered</th><th>Order</th><th>Truck</th><th>Trailer / wagon</th><th>Site</th><th>Oil company</th><th>Fuel</th>
          <th class="num">Volume (L)</th><th class="num">Rum (g)</th><th>Status</th></tr></thead>
        <tbody>
          @for (m of data()?.items ?? []; track m.id) {
            <tr>
              <td>{{ time(m.registeredAt) }}</td>
              <td><a [routerLink]="['/markings', m.id]" class="mono">{{ m.orderNumber }}</a></td>
              <td>{{ m.truckNumber }}</td><td>{{ m.trailerNumber || '–' }}</td><td>{{ m.siteName }}</td><td>{{ m.omc }}</td><td>{{ m.fuelType }}</td>
              <td class="num">{{ fmt0(m.declaredVolumeL) }}</td><td class="num">{{ fmt1(m.rumMl) }}</td>
              <td><app-status-badge [value]="m.status" /></td>
            </tr>
          } @empty {
            <tr><td colspan="10" class="empty">{{ loading() ? 'Loading…' : 'No markings match these filters.' }}</td></tr>
          }
        </tbody></table></div>
    </div>
    @if (data(); as d) { <app-pager [page]="d.page" [size]="d.size" [total]="d.total" (pageChange)="goto($event)" /> }
  `
})
export class MarkingsComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly lookups = inject(LookupsService);
  readonly statuses = MARKING_STATUSES;
  readonly fmt0 = fmt0;
  readonly fmt1 = fmt1;
  readonly time = formatTime;

  f = this.blank();
  data = signal<Page<MarkingRow> | null>(null);
  loading = signal(false);
  private page = 0;

  private blank() { return { q: '', siteId: '', omcId: '', fuel: '', status: '', from: daysAgo(29), to: '' }; }

  ngOnInit(): void { this.lookups.ensureLoaded(); this.load(); }
  apply(): void { this.page = 0; this.load(); }
  reset(): void { this.f = this.blank(); this.apply(); }
  goto(p: number): void { this.page = p; this.load(); }

  private load(): void {
    this.loading.set(true);
    this.api.markings({ ...this.f, page: this.page, size: 25 }).subscribe({
      next: d => { this.data.set(d); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }
}
