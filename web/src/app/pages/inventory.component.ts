import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { LookupsService } from '../core/lookups.service';
import { ContainerRow, InventoryRow, Page } from '../core/models';
import { PagerComponent } from '../shared/pager.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { fmt0, fmtKg } from '../shared/format';

const CONTAINER_STATUSES = ['SEALED', 'OPENED', 'IN_TRANSIT', 'EMPTY', 'MISSING'];

@Component({
  selector: 'app-inventory',
  standalone: true,
  imports: [FormsModule, PagerComponent, StatusBadgeComponent],
  template: `
    <div class="page-head"><div><h1>Inventory</h1><p>Rum held in containers, as last reported by each site.</p></div>
      <div class="field"><label for="site">Site</label><select id="site" [(ngModel)]="siteId" (ngModelChange)="apply()">
        <option value="">All sites</option>@for (s of lookups.sites(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }</select></div>
    </div>

    <section class="card">
      <h2>Summary</h2>
      <div class="table-wrap"><table>
        <thead><tr><th>Site</th><th>Container</th><th>Status</th><th class="num">Count</th><th class="num">Rum (kg)</th></tr></thead>
        <tbody>
          @for (r of summary(); track r.siteName + r.type + r.status) {
            <tr><td>{{ r.siteName }}</td><td>{{ r.type }}</td><td><app-status-badge [value]="r.status" /></td>
              <td class="num">{{ fmt0(r.containers) }}</td><td class="num">{{ fmtKg(r.volumeMl) }}</td></tr>
          } @empty { <tr><td colspan="5" class="empty">No containers reported yet.</td></tr> }
        </tbody></table></div>
    </section>

    <section class="card">
      <h2>Containers</h2>
      <div class="filters">
        <div class="field"><label for="q">Barcode or seal number</label><input id="q" [(ngModel)]="q" (keyup.enter)="apply()" placeholder="Search" /></div>
        <div class="field"><label for="t">Type</label><select id="t" [(ngModel)]="type" (ngModelChange)="apply()"><option value="">All</option>
          @for (x of lookups.containerTypes(); track x.id) { <option [value]="x.code">{{ x.name }}</option> }</select></div>
        <div class="field"><label for="st">Status</label><select id="st" [(ngModel)]="status" (ngModelChange)="apply()"><option value="">All</option>
          @for (x of statuses; track x) { <option [value]="x">{{ x }}</option> }</select></div>
      </div>
      <div class="table-wrap"><table>
        <thead><tr><th>Barcode</th><th>Site</th><th>Type</th><th>Status</th><th>Seals</th><th class="num">Rum (kg)</th><th class="num">Capacity (kg)</th></tr></thead>
        <tbody>
          @for (c of containers()?.items ?? []; track c.id) {
            <tr><td class="mono">{{ c.barcode }}</td><td>{{ c.siteName }}</td><td>{{ c.type }}</td>
              <td><app-status-badge [value]="c.status" /></td><td class="mono">{{ seals(c) }}</td><td class="num">{{ fmtKg(c.volumeMl) }}</td><td class="num">{{ fmtKg(c.capacityMl) }}</td></tr>
          } @empty { <tr><td colspan="7" class="empty">No containers match.</td></tr> }
        </tbody></table></div>
      @if (containers(); as d) { <app-pager [page]="d.page" [size]="d.size" [total]="d.total" (pageChange)="goto($event)" /> }
    </section>
  `
})
export class InventoryComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly lookups = inject(LookupsService);
  readonly statuses = CONTAINER_STATUSES;
  readonly fmt0 = fmt0;
  readonly fmtKg = fmtKg;
  siteId = ''; q = ''; type = ''; status = '';
  summary = signal<InventoryRow[]>([]);
  containers = signal<Page<ContainerRow> | null>(null);
  private page = 0;

  /** The seal number(s) on the container: one, two, or a dash when it carries none. */
  seals(c: ContainerRow): string { return [c.topSeal, c.bottomSeal].filter(s => !!s).join(' · ') || '–'; }

  ngOnInit(): void { this.lookups.ensureLoaded(); this.apply(); }

  apply(): void {
    this.page = 0;
    this.api.inventory({ siteId: this.siteId }).subscribe(r => this.summary.set(r));
    this.loadList();
  }

  goto(p: number): void { this.page = p; this.loadList(); }

  private loadList(): void {
    this.api.containers({ siteId: this.siteId, q: this.q, type: this.type, status: this.status, page: this.page, size: 25 })
      .subscribe(d => this.containers.set(d));
  }
}
