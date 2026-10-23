import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { LookupsService } from '../../core/lookups.service';
import { ContainerTypeRow, JerricanRow } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { fmtKg } from '../../shared/format';

@Component({
  selector: 'app-admin-jerricans',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <div><h1>Jerricans</h1><p>Every jerrican is registered here with its id, container type, capacity and the rum type it will hold. The warehouse can only receive a jerrican that is on this list.</p></div>
      <button type="button" class="primary" (click)="startNew()">Register jerricans</button>
    </div>

    @if (form(); as f) {
      <form class="card" (ngSubmit)="save()" style="margin-bottom:16px">
        <h2>{{ f.id ? 'Edit jerrican ' + f.barcode : 'Register jerricans' }}</h2>
        <div class="form-row">
          <div class="field"><label for="m">Container type</label>
            <select id="m" name="type" [(ngModel)]="f.containerTypeId" (ngModelChange)="usual(f)" required>
              <option value="">Select…</option>@for (x of jerricanTypes(); track x.id) { <option [value]="x.id">{{ x.name }} ({{ x.seals }} {{ x.seals === 1 ? 'seal' : 'seals' }})</option> }</select></div>
          <div class="field"><label for="k">Capacity (kg)</label><input id="k" name="kg" type="number" step="0.001" min="0" [(ngModel)]="f.kg" required /></div>
          <div class="field"><label for="r">Rum type</label>
            <select id="r" name="rum" [(ngModel)]="f.rumTypeId" required>
              <option value="">Select…</option>@for (r of activeRum(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }</select>
            @if (colourOf(f.rumTypeId); as c) { <span class="swatch" [style.background]="c" title="This rum type's colour"></span> }</div>
        </div>
        @if (f.id) {
          <div class="form-row"><div class="field"><label for="b">Jerrican id</label><input id="b" name="barcode" [(ngModel)]="f.barcode" required maxlength="100" /></div></div>
        } @else {
          <div class="field" style="margin-top:8px"><label for="ids">Jerrican ids (one per line - scan or paste a whole delivery)</label>
            <textarea id="ids" name="ids" rows="6" [(ngModel)]="f.ids" placeholder="JC-0001&#10;JC-0002"></textarea></div>
        }
        <div class="form-row" style="margin-top:8px">
          <button class="primary" type="submit" [disabled]="!valid(f)">{{ f.id ? 'Save' : 'Register ' + count(f) + ' jerrican(s)' }}</button>
          <button type="button" (click)="form.set(null)">Cancel</button>
        </div>
      </form>
    }

    <div class="filters">
      <div class="field"><label for="q">Search</label><input id="q" placeholder="Jerrican id" [(ngModel)]="q" /></div>
      <div class="field"><label for="ms">Container type</label><select id="ms" [(ngModel)]="typeFilter"><option value="">All</option>
        @for (x of jerricanTypes(); track x.id) { <option [value]="x.id">{{ x.name }}</option> }</select></div>
    </div>

    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Jerrican id</th><th>Container type</th><th class="num">Capacity (kg)</th><th>Rum type</th><th>Where it is</th><th class="num">Rum (kg)</th><th></th></tr></thead>
      <tbody>
        @for (j of shown(); track j.id) {
          <tr [class.muted]="!j.active">
            <td class="mono">{{ j.barcode }}</td><td>{{ j.containerTypeName }} <span class="muted">({{ j.seals }} {{ j.seals === 1 ? 'seal' : 'seals' }})</span></td><td class="num">{{ kg(j.capacityG) }}</td><td><span class="swatch" [style.background]="j.rumColor"></span> {{ j.rumTypeCode }}</td>
            <td>@if (j.status) { {{ j.status }}{{ j.siteName ? ' - ' + j.siteName : '' }} } @else { <span class="muted">not received yet</span> }</td>
            <td class="num">{{ j.volumeG === null ? '–' : kg(j.volumeG) }}</td>
            <td class="right"><button type="button" class="link" (click)="edit(j)">Edit</button>
              <button type="button" class="link" (click)="toggle(j)">{{ j.active ? 'Retire' : 'Restore' }}</button></td>
          </tr>
        } @empty { <tr><td colspan="7" class="empty">No jerricans registered yet.</td></tr> }
      </tbody></table></div></div>
  `
})
export class AdminJerricansComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly lookups = inject(LookupsService);
  types = signal<ContainerTypeRow[]>([]);
  readonly jerricanTypes = computed(() => this.types().filter(t => t.category === 'JERRICAN' && t.active));
  readonly kg = fmtKg;
  rows = signal<JerricanRow[]>([]);
  form = signal<{ id?: string; barcode: string; ids: string; containerTypeId: string; kg: number | null; rumTypeId: string } | null>(null);
  q = '';
  typeFilter = '';

  readonly activeRum = computed(() => this.lookups.rumTypes().filter(r => r.active));
  shown = computed(() => this.rows().filter(r =>
    (!this.q || r.barcode.toLowerCase().includes(this.q.toLowerCase())) && (!this.typeFilter || r.containerTypeId === this.typeFilter)));

  ngOnInit(): void { this.lookups.ensureLoaded(); this.api.containerTypes().subscribe(t => this.types.set(t)); this.load(); }

  private load(): void { this.api.jerricans().subscribe(r => this.rows.set(r)); }

  startNew(): void {
    const only = this.activeRum(), types = this.jerricanTypes();
    const first = types.length === 1 ? types[0] : null;
    this.form.set({ barcode: '', ids: '', containerTypeId: first?.id ?? '', kg: first?.defaultCapacityG ? first.defaultCapacityG / 1000 : null,
      rumTypeId: only.length === 1 ? only[0].id : '' });
  }

  edit(j: JerricanRow): void {
    this.form.set({ id: j.id, barcode: j.barcode, ids: '', containerTypeId: j.containerTypeId, kg: j.capacityG / 1000, rumTypeId: j.rumTypeId });
  }

  /** Choosing a type suggests its usual capacity (22 kg for plastic, 18 kg for metal at the start); it can be changed. */
  usual(f: { containerTypeId: string; kg: number | null; id?: string }): void {
    const g = this.types().find(t => t.id === f.containerTypeId)?.defaultCapacityG;
    if (!f.id && g) f.kg = g / 1000;
  }

  colourOf(id: string): string | null { return this.lookups.rumTypes().find(r => r.id === id)?.color ?? null; }

  private ids(f: { ids: string }): string[] { return f.ids.split(/\r?\n/).map(s => s.trim()).filter(Boolean); }
  count(f: { ids: string }): number { return this.ids(f).length; }
  valid(f: { id?: string; barcode: string; ids: string; kg: number | null; rumTypeId: string; containerTypeId: string }): boolean {
    return !!f.kg && f.kg > 0 && !!f.rumTypeId && !!f.containerTypeId && (f.id ? !!f.barcode.trim() : this.count(f) > 0);
  }

  save(): void {
    const f = this.form();
    if (!f || !this.valid(f)) return;
    const capacityG = Math.round((f.kg ?? 0) * 1000 * 10) / 10;
    if (f.id) {
      this.api.saveJerrican({ id: f.id, barcode: f.barcode.trim(), containerTypeId: f.containerTypeId, capacityG, rumTypeId: f.rumTypeId, active: this.rows().find(r => r.id === f.id)?.active ?? true })
        .subscribe(() => { this.toast.success('Jerrican saved.'); this.form.set(null); this.load(); });
    } else {
      this.api.registerJerricans({ barcodes: this.ids(f), containerTypeId: f.containerTypeId, capacityG, rumTypeId: f.rumTypeId })
        .subscribe(r => { this.toast.success(r.registered + ' jerrican(s) registered.'); this.form.set(null); this.load(); });
    }
  }

  toggle(j: JerricanRow): void {
    this.api.saveJerrican({ id: j.id, barcode: j.barcode, containerTypeId: j.containerTypeId, capacityG: j.capacityG, rumTypeId: j.rumTypeId, active: !j.active })
      .subscribe(() => this.load());
  }
}
