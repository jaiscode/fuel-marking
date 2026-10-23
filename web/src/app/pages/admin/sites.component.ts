import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { LookupsService } from '../../core/lookups.service';
import { MasterDataStatus, SITE_TYPES, Site } from '../../core/models';
import { formatAge } from '../../core/dates';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-admin-sites',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <div><h1>Sites</h1><p>Marking sites and warehouses. Changes reach every site through Kafka.</p></div>
      <div class="form-row">
        <button type="button" (click)="republish()" title="Send all sites, oil companies and users to the sites again">Re-send master data</button>
        <button type="button" class="primary" (click)="startNew()">New site</button>
      </div>
    </div>

    @if (backlog(); as b) {
      @if (b.pending > 0) {
        <div class="notice" role="status">
          <b>{{ b.pending }} change(s) are waiting to be sent to the sites</b>
          @if (b.oldestPendingSeconds !== null) { (oldest {{ ageText(b.oldestPendingSeconds) }}) }.
          They are safe and will be delivered automatically. If this does not clear within a minute or two, the message broker
          (Kafka) may be unavailable.@if (b.lastError) { <br /><span class="mono">{{ b.lastError }}</span> }
        </div>
      }
    }

    @if (issued(); as k) {
      <div class="notice" role="status">
        <b>API key for {{ nameOf(k.siteId) }}</b> — copy it now, it is shown only once and cannot be retrieved later.<br />
        <code>{{ k.apiKey }}</code>
        <button type="button" class="link" (click)="copy(k.apiKey)">Copy</button>
        <button type="button" class="link" (click)="issued.set(null)">Done</button>
      </div>
    }

    @if (edit(); as e) {
      <form class="card" (ngSubmit)="save()" style="margin-bottom:16px">
        <h2>{{ e.id ? 'Edit site' : 'New site' }}</h2>
        <div class="form-row">
          <div class="field"><label for="c">Code</label><input id="c" name="code" [(ngModel)]="e.code" required maxlength="30" /></div>
          <div class="field"><label for="n">Name</label><input id="n" name="name" [(ngModel)]="e.name" required maxlength="200" /></div>
          <div class="field"><label for="t">Type</label><select id="t" name="type" [(ngModel)]="e.siteType">
            @for (t of types; track t) { <option [value]="t">{{ t.toLowerCase() }}</option> }</select></div>
          <button class="primary" type="submit" [disabled]="!e.code || !e.name">Save</button>
          <button type="button" (click)="edit.set(null)">Cancel</button>
        </div>
      </form>
    }

    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Code</th><th>Name</th><th>Type</th><th>Status</th><th></th></tr></thead>
      <tbody>
        @for (s of sites(); track s.id) {
          <tr><td class="mono">{{ s.code }}</td><td>{{ s.name }}</td><td>{{ s.siteType.toLowerCase() }}</td>
            <td><span class="badge" [class.ok]="s.active">{{ s.active ? 'active' : 'inactive' }}</span></td>
            <td class="right">
              <button type="button" class="link" (click)="editCopy(s)">Edit</button>
              <button type="button" class="link" (click)="toggle(s)">{{ s.active ? 'Deactivate' : 'Activate' }}</button>
              <button type="button" class="link" (click)="issueKey(s)">New API key</button>
            </td></tr>
        } @empty { <tr><td colspan="5" class="empty">No sites yet.</td></tr> }
      </tbody></table></div></div>
  `
})
export class AdminSitesComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly lookups = inject(LookupsService);
  readonly types = SITE_TYPES;
  sites = signal<Site[]>([]);
  edit = signal<Partial<Site> | null>(null);
  issued = signal<{ siteId: string; apiKey: string } | null>(null);
  backlog = signal<MasterDataStatus | null>(null);

  ageText(seconds: number): string { return formatAge(seconds); }

  editCopy(s: Site): void { this.edit.set({ ...s }); }

  ngOnInit(): void { this.load(); }

  /** The admin list includes inactive sites and is the source of truth for this page. */
  private load(): void {
    this.api.sites().subscribe(s => this.sites.set(s));
    this.api.masterDataStatus().subscribe(b => this.backlog.set(b));
    this.lookups.reload();
  }

  startNew(): void { this.edit.set({ code: '', name: '', siteType: 'FIXED', active: true }); }
  nameOf(id: string): string { return this.sites().find(s => s.id === id)?.name ?? id; }

  save(): void {
    const e = this.edit();
    if (!e) return;
    this.api.saveSite(e).subscribe(() => { this.toast.success('Site saved.'); this.edit.set(null); this.load(); });
  }

  toggle(s: Site): void {
    this.api.saveSite({ ...s, active: !s.active }).subscribe(() => this.load());
  }

  issueKey(s: Site): void {
    if (!confirm(`Issue a new API key for ${s.name}? The key it uses now will stop working.`)) return;
    this.api.issueKey(s.id).subscribe(k => this.issued.set(k));
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text).then(() => this.toast.success('Copied.'), () => this.toast.error('Copy failed — select and copy manually.'));
  }

  republish(): void {
    this.api.republish().subscribe(r => { this.toast.success(`Queued ${r.published} master data record(s) for the sites.`); this.load(); });
  }
}
