import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { LookupsService } from '../../core/lookups.service';
import { AppUser, ROLES } from '../../core/models';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <div><h1>Users</h1><p>People who sign in at the sites. A user can only sign in once a PIN has been set.</p></div>
      <button type="button" class="primary" (click)="newUser()">New user</button>
    </div>

    @if (edit(); as e) {
      <form class="card" (ngSubmit)="save()" style="margin-bottom:16px">
        <h2>{{ e.id ? 'Edit user' : 'New user' }}</h2>
        <div class="form-row">
          <div class="field"><label for="u">Username</label><input id="u" name="username" [(ngModel)]="e.username" required maxlength="60" autocomplete="off" /></div>
          <div class="field"><label for="n">Full name</label><input id="n" name="fullName" [(ngModel)]="e.fullName" required maxlength="200" /></div>
          <div class="field"><label for="r">Role</label><select id="r" name="role" [(ngModel)]="e.role">
            @for (r of roles; track r) { <option [value]="r">{{ label(r) }}</option> }</select></div>
          <div class="field"><label for="s">Site</label><select id="s" name="siteId" [(ngModel)]="e.siteId">
            <option [ngValue]="null">Any site</option>
            @for (s of sites(); track s.id) { <option [ngValue]="s.id">{{ s.name }}</option> }</select></div>
          <button class="primary" type="submit" [disabled]="!e.username || !e.fullName">Save</button>
          <button type="button" (click)="edit.set(null)">Cancel</button>
        </div>
      </form>
    }

    @if (pinFor(); as u) {
      <form class="card" (ngSubmit)="savePin()" style="margin-bottom:16px">
        <h2>Set PIN for {{ u.fullName }}</h2>
        <div class="form-row">
          <div class="field"><label for="pin">New PIN (4–8 digits)</label>
            <input id="pin" name="pin" type="password" inputmode="numeric" autocomplete="new-password" [(ngModel)]="pin" maxlength="8" /></div>
          <button class="primary" type="submit" [disabled]="!validShape()">Set PIN</button>
          <button type="button" (click)="pinFor.set(null)">Cancel</button>
        </div>
        <p class="muted">Avoid repeated digits and sequences such as 1234. The PIN is never stored; sites receive only a salted hash. Setting a new PIN also clears any lockout.</p>
      </form>
    }

    <div class="card" style="padding:0"><div class="table-wrap"><table>
      <thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Site</th><th>Status</th><th></th></tr></thead>
      <tbody>
        @for (u of users(); track u.id) {
          <tr><td>{{ u.fullName }}</td><td class="mono">{{ u.username }}</td><td>{{ label(u.role) }}</td><td>{{ siteName(u.siteId) }}</td>
            <td><span class="badge" [class.ok]="u.active">{{ u.active ? 'active' : 'inactive' }}</span></td>
            <td class="right">
              <button type="button" class="link" (click)="editCopy(u)">Edit</button>
              <button type="button" class="link" (click)="startPin(u)">Set PIN</button>
              <button type="button" class="link" (click)="toggle(u)">{{ u.active ? 'Deactivate' : 'Activate' }}</button>
            </td></tr>
        } @empty { <tr><td colspan="6" class="empty">No users yet.</td></tr> }
      </tbody></table></div></div>
  `
})
export class AdminUsersComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly lookups = inject(LookupsService);
  readonly roles = ROLES;
  readonly sites = this.lookups.sites;
  users = signal<AppUser[]>([]);
  edit = signal<Partial<AppUser> | null>(null);
  pinFor = signal<AppUser | null>(null);
  pin = '';

  newUser(): void {
    const first = this.sites();
    this.edit.set({ username: '', fullName: '', role: 'SUPERVISOR', siteId: first.length ? first[0].id : null, active: true });
  }

  editCopy(u: AppUser): void { this.edit.set({ ...u }); }

  ngOnInit(): void { this.lookups.reload(); this.refresh(); }

  label(role: string): string { return role.toLowerCase().replace(/_/g, ' ').replace(/^./, c => c.toUpperCase()); }
  siteName(id: string | null): string { return id ? this.sites().find(s => s.id === id)?.name ?? id : 'Any site'; }
  validShape(): boolean { return /^\d{4,8}$/.test(this.pin); }

  refresh(): void { this.api.users().subscribe(u => this.users.set(u)); }

  save(): void {
    const e = this.edit();
    if (!e) return;
    this.api.saveUser(e).subscribe(() => { this.toast.success('User saved.'); this.edit.set(null); this.refresh(); });
  }

  toggle(u: AppUser): void { this.api.saveUser({ ...u, active: !u.active }).subscribe(() => this.refresh()); }

  startPin(u: AppUser): void { this.pin = ''; this.pinFor.set(u); }

  savePin(): void {
    const u = this.pinFor();
    if (!u) return;
    this.api.setPin(u.id, this.pin).subscribe(() => {
      this.toast.success(`PIN set for ${u.fullName}.`);
      this.pin = '';
      this.pinFor.set(null);
    });
  }
}
