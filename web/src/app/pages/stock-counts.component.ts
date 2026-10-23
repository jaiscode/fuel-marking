import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { LookupsService } from '../core/lookups.service';
import { Page, StockCountDetail, StockCountRow } from '../core/models';
import { daysAgo, formatTime } from '../core/dates';
import { PagerComponent } from '../shared/pager.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { fmtKg } from '../shared/format';

@Component({
  selector: 'app-stock-counts',
  standalone: true,
  imports: [FormsModule, PagerComponent, StatusBadgeComponent],
  template: `
    <div class="page-head"><div><h1>Stock counts</h1><p>Counts of the rum held: the warehouse manager's count of IBCs and drums, and the supervisor's reconciliation of the opened jerricans during a shift. What was counted against what the system held.</p></div></div>
    <div class="filters">
      <div class="field"><label for="s">Site</label><select id="s" [(ngModel)]="f.siteId" (ngModelChange)="apply()"><option value="">All</option>
        @for (s of lookups.sites(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }</select></div>
      <div class="field"><label for="k">Kind</label><select id="k" [(ngModel)]="f.kind" (ngModelChange)="apply()"><option value="">All</option>
        <option value="WAREHOUSE">Warehouse count</option><option value="SHIFT">Shift reconciliation</option></select></div>
      <div class="field"><label for="fr">From</label><input id="fr" type="date" [(ngModel)]="f.from" (ngModelChange)="apply()" /></div>
      <div class="field"><label for="to">To</label><input id="to" type="date" [(ngModel)]="f.to" (ngModelChange)="apply()" /></div>
    </div>

    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Counted</th><th>Kind</th><th>Site</th><th>By</th><th class="num">Containers</th><th class="num">System (kg)</th><th class="num">Counted (kg)</th><th class="num">Difference (kg)</th></tr></thead>
      <tbody>
        @for (c of data()?.items ?? []; track c.id) {
          <tr class="clickable" [class.selected]="detail()?.count?.id === c.id" tabindex="0" (click)="open(c)" (keyup.enter)="open(c)">
            <td>{{ time(c.countedAt) }}</td><td>{{ kindLabel(c.kind) }}</td><td>{{ c.siteName }}</td><td>{{ c.countedBy }}</td><td class="num">{{ c.containers }}</td>
            <td class="num">{{ kg(c.totalSystemG) }}</td><td class="num">{{ kg(c.totalCountedG) }}</td>
            <td class="num"><span class="badge" [class.ok]="c.differing === 0" [class.bad]="c.differing > 0">{{ diff(c.totalVarianceG) }}</span></td>
          </tr>
        } @empty { <tr><td colspan="8" class="empty">No stock counts in this period.</td></tr> }
      </tbody></table></div></div>
    @if (data(); as d) { <app-pager [page]="d.page" [size]="d.size" [total]="d.total" (pageChange)="goto($event)" /> }

    @if (detail(); as x) {
      <section class="card" style="margin-top:16px">
        <h2>{{ x.count.siteName }} - {{ time(x.count.countedAt) }}</h2>
        @if (x.count.comment) { <p>{{ x.count.comment }}</p> }
        <div class="table-wrap"><table>
          <thead><tr><th>Container</th><th>Type</th><th>Rum type</th><th class="num">System (kg)</th><th class="num">Counted (kg)</th><th class="num">Difference (kg)</th><th>Source</th><th>Check</th><th>Cause</th><th>Note</th></tr></thead>
          <tbody>@for (l of x.lines; track l.barcode) {
            <tr [class.flagged]="differs(l.varianceG)"><td class="mono">{{ l.barcode }}</td><td>{{ l.containerType }}</td><td>{{ l.rumTypeCode }}</td>
              <td class="num">{{ kg(l.systemG) }}</td><td class="num">{{ kg(l.countedG) }}</td>
              <td class="num"><span class="badge" [class.ok]="!differs(l.varianceG)" [class.bad]="differs(l.varianceG)">{{ diff(l.varianceG) }}</span></td>
              <td>{{ l.check ? 'looked at' : (l.source === 'SCALE' ? 'weighed' : 'typed') }}</td>
              <td>@if (l.check) { <span class="badge" [class.ok]="l.check === 'FOUND'" [class.bad]="l.check !== 'FOUND'">{{ checkLabel(l.check) }}</span> } @else { <span class="muted">–</span> }</td>
              <td>@if (l.cause) { <app-status-badge [value]="l.cause" /> } @else { <span class="muted">–</span> }</td><td class="wrap">{{ l.note || '–' }}</td></tr>
          }</tbody></table></div>
      </section>
    }
  `
})
export class StockCountsComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly lookups = inject(LookupsService);
  readonly time = formatTime;
  readonly kg = fmtKg;
  f = { siteId: '', kind: '', from: daysAgo(89), to: '' };
  data = signal<Page<StockCountRow> | null>(null);
  detail = signal<StockCountDetail | null>(null);
  private page = 0;

  ngOnInit(): void { this.lookups.ensureLoaded(); this.load(); }
  apply(): void { this.page = 0; this.detail.set(null); this.load(); }
  goto(p: number): void { this.page = p; this.load(); }
  open(c: StockCountRow): void { this.api.stockCount(c.id).subscribe(d => this.detail.set(d)); }
  /** What was found for a sealed jerrican at a reconciliation. */
  checkLabel(c: string): string { return c === 'FOUND' ? 'Found, seal intact' : c === 'MISSING' ? 'Not found' : 'Seal broken'; }
  kindLabel(k: string): string { return k === 'SHIFT' ? 'Shift reconciliation' : 'Warehouse count'; }
  differs(g: number): boolean { return Math.abs(g) >= 1; }
  diff(g: number): string { return Math.abs(g) < 1 ? '0' : (g > 0 ? '+' : '-') + fmtKg(Math.abs(g)); }
  private load(): void { this.api.stockCounts({ ...this.f, page: this.page, size: 25 }).subscribe(d => this.data.set(d)); }
}
