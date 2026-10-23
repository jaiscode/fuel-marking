import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { LookupsService } from '../../core/lookups.service';
import { CONTAINER_CATEGORIES, ContainerTypeRow } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { fmtKg } from '../../shared/format';

interface Draft { id?: string; code: string; name: string; category: string; seals: number; kg: number | null; active: boolean; }

@Component({
  selector: 'app-admin-container-types',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <div><h1>Container types</h1><p>Bulk containers (IBC, drum ...) and jerricans (plastic, metal ...). The seals say how many seals the container carries; the usual capacity is only a suggestion when one is received.</p></div>
      <button type="button" class="primary" (click)="startNew()">New container type</button>
    </div>

    @if (form(); as f) {
      <form class="card" (ngSubmit)="save()" style="margin-bottom:16px">
        <h2>{{ f.id ? 'Edit container type' : 'New container type' }}</h2>
        <div class="form-row">
          <div class="field"><label for="c">Code</label><input id="c" name="code" [(ngModel)]="f.code" required maxlength="30" [disabled]="!!f.id" /></div>
          <div class="field"><label for="n">Name</label><input id="n" name="name" [(ngModel)]="f.name" required maxlength="100" /></div>
          <div class="field"><label for="g">Kind</label>
            <select id="g" name="category" [(ngModel)]="f.category" (ngModelChange)="kind(f)" [disabled]="!!f.id">
              @for (x of categories; track x) { <option [value]="x">{{ x === 'BULK' ? 'Bulk container (IBC, drum ...)' : 'Jerrican' }}</option> }</select></div>
          <div class="field"><label for="s">Seals</label>
            <select id="s" name="seals" [(ngModel)]="f.seals">
              @if (f.category === 'BULK') { <option [ngValue]="0">None required</option> }
              <option [ngValue]="1">1 seal</option><option [ngValue]="2">2 seals</option></select></div>
          <div class="field"><label for="k">Usual capacity (kg, optional)</label><input id="k" name="kg" type="number" step="0.001" min="0" [(ngModel)]="f.kg" placeholder="varies" /></div>
        </div>
        <div class="form-row" style="margin-top:8px">
          <button class="primary" type="submit" [disabled]="!f.code || !f.name">Save</button>
          <button type="button" (click)="form.set(null)">Cancel</button>
        </div>
        @if (f.id) { <p class="muted">The code and kind of a type that is in use cannot change; add a new type instead.</p> }
      </form>
    }

    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Code</th><th>Name</th><th>Kind</th><th class="num">Seals</th><th class="num">Usual capacity (kg)</th><th>Status</th><th></th></tr></thead>
      <tbody>
        @for (t of items(); track t.id) {
          <tr><td class="mono">{{ t.code }}</td><td>{{ t.name }}</td><td>{{ t.category === 'BULK' ? 'Bulk' : 'Jerrican' }}</td>
            <td class="num">{{ t.seals }}</td><td class="num">{{ t.defaultCapacityG === null ? 'varies' : kg(t.defaultCapacityG) }}</td>
            <td><span class="badge" [class.ok]="t.active">{{ t.active ? 'active' : 'inactive' }}</span></td>
            <td class="right"><button type="button" class="link" (click)="edit(t)">Edit</button>
              <button type="button" class="link" (click)="toggle(t)">{{ t.active ? 'Deactivate' : 'Activate' }}</button></td></tr>
        } @empty { <tr><td colspan="7" class="empty">None yet.</td></tr> }
      </tbody></table></div></div>
  `
})
export class AdminContainerTypesComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly lookups = inject(LookupsService);
  readonly categories = CONTAINER_CATEGORIES;
  readonly kg = fmtKg;
  items = signal<ContainerTypeRow[]>([]);
  form = signal<Draft | null>(null);

  ngOnInit(): void { this.load(); }

  private load(): void {
    this.api.containerTypes().subscribe(l => this.items.set(l));
    this.lookups.reload();
  }

  startNew(): void { this.form.set({ code: '', name: '', category: 'JERRICAN', seals: 1, kg: null, active: true }); }

  edit(t: ContainerTypeRow): void {
    this.form.set({ id: t.id, code: t.code, name: t.name, category: t.category, seals: t.seals, kg: t.defaultCapacityG === null ? null : t.defaultCapacityG / 1000, active: t.active });
  }

  /** A jerrican always carries at least one seal. */
  kind(f: Draft): void { if (f.category === 'JERRICAN' && f.seals < 1) f.seals = 1; }

  save(): void {
    const f = this.form();
    if (!f) return;
    this.api.saveContainerType(this.body(f)).subscribe(() => { this.toast.success('Container type saved.'); this.form.set(null); this.load(); });
  }

  toggle(t: ContainerTypeRow): void { this.api.saveContainerType({ ...t, active: !t.active }).subscribe(() => this.load()); }

  private body(f: Draft): Partial<ContainerTypeRow> {
    return { id: f.id, code: f.code.trim(), name: f.name.trim(), category: f.category as 'BULK' | 'JERRICAN', seals: f.seals,
      defaultCapacityG: f.kg ? Math.round(f.kg * 1000 * 10) / 10 : null, active: f.active };
  }
}
