import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { LookupsService } from '../core/lookups.service';
import { Page, TransferDetail, TransferRow } from '../core/models';
import { daysAgo, formatTime } from '../core/dates';
import { PagerComponent } from '../shared/pager.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { fmt0 } from '../shared/format';

@Component({
  selector: 'app-transfers',
  standalone: true,
  imports: [FormsModule, PagerComponent, StatusBadgeComponent],
  template: `
    <div class="page-head"><div><h1>Transfers</h1><p>Containers moved between sites - supplies out of a warehouse and finished jerricans sent back for a refill - and whether the destination has taken them in.</p></div></div>
    <div class="filters">
      <div class="field"><label for="s">Site (sender or receiver)</label><select id="s" [(ngModel)]="f.siteId" (ngModelChange)="apply()"><option value="">All</option>
        @for (s of lookups.sites(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }</select></div>
      <div class="field"><label for="st">Status</label><select id="st" [(ngModel)]="f.status" (ngModelChange)="apply()"><option value="">All</option>
        <option value="DISPATCHED">In transit</option><option value="RECEIVED">Received</option></select></div>
      <div class="field"><label for="fr">From</label><input id="fr" type="date" [(ngModel)]="f.from" (ngModelChange)="apply()" /></div>
      <div class="field"><label for="to">To</label><input id="to" type="date" [(ngModel)]="f.to" (ngModelChange)="apply()" /></div>
    </div>

    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Dispatched</th><th>Kind</th><th>From</th><th>To</th><th>Status</th><th class="num">Containers</th><th class="num">Received</th></tr></thead>
      <tbody>
        @for (t of data()?.items ?? []; track t.id) {
          <tr class="clickable" [class.selected]="detail()?.transfer?.id === t.id" tabindex="0" (click)="open(t)" (keyup.enter)="open(t)">
            <td>{{ time(t.dispatchedAt) }}</td><td>{{ t.kind === 'RETURN' ? 'Empties back' : 'Supplies' }}</td><td>{{ t.fromSite }}</td><td>{{ t.toSite }}</td>
            <td><app-status-badge [value]="t.status" /></td><td class="num">{{ fmt0(t.items) }}</td>
            <td class="num">{{ fmt0(t.received) }} of {{ fmt0(t.items) }}</td>
          </tr>
        } @empty { <tr><td colspan="7" class="empty">No transfers in this period.</td></tr> }
      </tbody></table></div></div>
    @if (data(); as d) { <app-pager [page]="d.page" [size]="d.size" [total]="d.total" (pageChange)="goto($event)" /> }

    @if (detail(); as x) {
      <section class="card" style="margin-top:16px">
        <h2>{{ x.transfer.fromSite }} → {{ x.transfer.toSite }}</h2>
        <div class="table-wrap"><table>
          <thead><tr><th>Container</th><th>State</th></tr></thead>
          <tbody>@for (i of x.items; track i.barcode) {
            <tr><td class="mono">{{ i.barcode }}</td>
              <td><span class="badge" [class.ok]="i.received" [class.warn]="!i.received">{{ i.received ? 'received' : 'in transit' }}</span></td></tr>
          }</tbody></table></div>
      </section>
    }
  `
})
export class TransfersComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly lookups = inject(LookupsService);
  readonly fmt0 = fmt0;
  readonly time = formatTime;
  f = { siteId: '', status: '', from: daysAgo(89), to: '' };
  data = signal<Page<TransferRow> | null>(null);
  detail = signal<TransferDetail | null>(null);
  private page = 0;

  ngOnInit(): void { this.lookups.ensureLoaded(); this.load(); }
  apply(): void { this.page = 0; this.detail.set(null); this.load(); }
  goto(p: number): void { this.page = p; this.load(); }
  open(t: TransferRow): void { this.api.transfer(t.id).subscribe(d => this.detail.set(d)); }
  private load(): void { this.api.transfers({ ...this.f, page: this.page, size: 25 }).subscribe(d => this.data.set(d)); }
}
