import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { LookupsService } from '../../core/lookups.service';
import { CodeName } from '../../core/models';
import { ToastService } from '../../core/toast.service';

/** Fuel types, rum types and locations: a short list of code + name that the sites choose from. The route says which one. */
@Component({
  selector: 'app-admin-code-list',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <div><h1>{{ title }}</h1><p>{{ blurb }}</p></div>
      <button type="button" class="primary" (click)="startNew()">New {{ one }}</button>
    </div>

    @if (edit(); as e) {
      <form class="card" (ngSubmit)="save()" style="margin-bottom:16px">
        <h2>{{ e.id ? 'Edit ' + one : 'New ' + one }}</h2>
        <div class="form-row">
          <div class="field"><label for="c">Code</label><input id="c" name="code" [(ngModel)]="e.code" required maxlength="30" /></div>
          <div class="field"><label for="n">Name</label><input id="n" name="name" [(ngModel)]="e.name" required maxlength="100" /></div>
          @if (fuel) {
            <div class="field"><label for="rt">Marked with rum type</label>
              <select id="rt" name="rumTypeId" [(ngModel)]="e.rumTypeId" required>
                <option [ngValue]="null" disabled>Choose the rum type</option>
                @for (r of rums(); track r.id) { @if (r.active || r.id === e.rumTypeId) { <option [ngValue]="r.id">{{ r.name }}</option> } }
              </select></div>
          }
          @if (rum) {
            <div class="field"><label for="col">Colour</label>
              <div style="display:flex;gap:8px;align-items:center">
                <input id="col" name="color" type="color" [(ngModel)]="e.color" aria-label="Pick the colour of this rum type" style="width:56px;height:38px;padding:2px" />
                <input name="colorText" [(ngModel)]="e.color" maxlength="7" pattern="#[0-9A-Fa-f]{6}" aria-label="Colour code" style="width:100px" class="mono" />
              </div></div>
          }
          <button class="primary" type="submit" [disabled]="!e.code || !e.name || (fuel && !e.rumTypeId)">Save</button>
          <button type="button" (click)="edit.set(null)">Cancel</button>
        </div>
      </form>
    }

    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Code</th><th>Name</th>@if (fuel) { <th>Rum type</th> }@if (rum) { <th>Colour</th> }<th>Status</th><th></th></tr></thead>
      <tbody>
        @for (o of items(); track o.id) {
          <tr><td class="mono">{{ o.code }}</td><td>{{ o.name }}</td>
            @if (fuel) { <td><span class="swatch" [style.background]="rumColor(o.rumTypeId)"></span> {{ rumName(o.rumTypeId) }}</td> }
            @if (rum) { <td><span class="swatch" [style.background]="o.color" [title]="o.color"></span> <span class="mono">{{ o.color }}</span></td> }
            <td><span class="badge" [class.ok]="o.active">{{ o.active ? 'active' : 'inactive' }}</span></td>
            <td class="right"><button type="button" class="link" (click)="editCopy(o)">Edit</button>
              <button type="button" class="link" (click)="toggle(o)">{{ o.active ? 'Deactivate' : 'Activate' }}</button></td></tr>
        } @empty { <tr><td [attr.colspan]="5" class="empty">None yet.</td></tr> }
      </tbody></table></div></div>
  `
})
export class AdminCodeListComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly lookups = inject(LookupsService);
  readonly fuel = inject(ActivatedRoute).snapshot.data['kind'] === 'fuel';
  readonly rum = inject(ActivatedRoute).snapshot.data['kind'] === 'rum';
  readonly title = this.fuel ? 'Fuel types' : this.rum ? 'Rum types' : 'Locations';
  readonly one = this.fuel ? 'fuel type' : this.rum ? 'rum type' : 'location';
  readonly blurb = !this.fuel && !this.rum
    ? 'The places a mobile marking site can work. A supervisor at a mobile site must choose one before starting a shift; head office then sees where and when each shift and marking happened. Sites get this list automatically and keep it offline.'
    : this.fuel
    ? 'What a truck can carry. Every fuel type is marked with one particular rum type. Supervisors choose the fuel of each compartment when they register a truck, and the rum follows from it.'
    : 'The kinds of rum, each with its own colour. Head office registers every jerrican for one rum type, and an IBC or drum holds one.';
  items = signal<CodeName[]>([]);
  edit = signal<Partial<CodeName> | null>(null);
  rums = signal<CodeName[]>([]);

  rumName(id: string | null | undefined): string { return this.rums().find(r => r.id === id)?.name ?? '–'; }
  rumColor(id: string | null | undefined): string { return this.rums().find(r => r.id === id)?.color ?? '#808080'; }

  startNew(): void { this.edit.set({ code: '', name: '', active: true, color: this.rum ? '#808080' : null, rumTypeId: null }); }

  editCopy(o: CodeName): void { this.edit.set({ ...o }); }

  ngOnInit(): void { this.load(); }

  private load(): void {
    this.api.rumTypes().subscribe(l => { this.rums.set(l); if (this.rum) this.items.set(l); });
    if (this.fuel) this.api.fuelTypes().subscribe(l => this.items.set(l));
    if (!this.fuel && !this.rum) this.api.locations().subscribe(l => this.items.set(l));
    this.lookups.reload();
  }

  private store(o: Partial<CodeName>): Observable<CodeName> { return this.fuel ? this.api.saveFuelType(o) : this.rum ? this.api.saveRumType(o) : this.api.saveLocation(o); }

  save(): void {
    const e = this.edit();
    if (!e) return;
    this.store(e).subscribe(() => { this.toast.success('Saved.'); this.edit.set(null); this.load(); });
  }

  toggle(o: CodeName): void { this.store({ ...o, active: !o.active }).subscribe(() => this.load()); }
}
