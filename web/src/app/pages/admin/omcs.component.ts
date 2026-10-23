import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { LookupsService } from '../../core/lookups.service';
import { Omc } from '../../core/models';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-admin-omcs',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <div><h1>Oil marketing companies</h1><p>Companies trucks can be registered under at the sites.</p></div>
      <button type="button" class="primary" (click)="edit.set({ code: '', name: '', active: true })">New company</button>
    </div>

    @if (edit(); as e) {
      <form class="card" (ngSubmit)="save()" style="margin-bottom:16px">
        <h2>{{ e.id ? 'Edit company' : 'New company' }}</h2>
        <div class="form-row">
          <div class="field"><label for="c">Code</label><input id="c" name="code" [(ngModel)]="e.code" required maxlength="30" /></div>
          <div class="field"><label for="n">Name</label><input id="n" name="name" [(ngModel)]="e.name" required maxlength="200" /></div>
          <button class="primary" type="submit" [disabled]="!e.code || !e.name">Save</button>
          <button type="button" (click)="edit.set(null)">Cancel</button>
        </div>
      </form>
    }

    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Code</th><th>Name</th><th>Status</th><th></th></tr></thead>
      <tbody>
        @for (o of omcs(); track o.id) {
          <tr><td class="mono">{{ o.code }}</td><td>{{ o.name }}</td>
            <td><span class="badge" [class.ok]="o.active">{{ o.active ? 'active' : 'inactive' }}</span></td>
            <td class="right"><button type="button" class="link" (click)="editCopy(o)">Edit</button>
              <button type="button" class="link" (click)="toggle(o)">{{ o.active ? 'Deactivate' : 'Activate' }}</button></td></tr>
        } @empty { <tr><td colspan="4" class="empty">No companies yet.</td></tr> }
      </tbody></table></div></div>
  `
})
export class AdminOmcsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly lookups = inject(LookupsService);
  omcs = signal<Omc[]>([]);
  edit = signal<Partial<Omc> | null>(null);

  editCopy(o: Omc): void { this.edit.set({ ...o }); }

  ngOnInit(): void { this.load(); }

  private load(): void {
    this.api.omcs().subscribe(o => this.omcs.set(o));
    this.lookups.reload();
  }

  save(): void {
    const e = this.edit();
    if (!e) return;
    this.api.saveOmc(e).subscribe(() => { this.toast.success('Company saved.'); this.edit.set(null); this.load(); });
  }

  toggle(o: Omc): void { this.api.saveOmc({ ...o, active: !o.active }).subscribe(() => this.load()); }
}
