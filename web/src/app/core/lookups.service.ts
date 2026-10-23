import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Lookup } from './models';

/** Sites, oil marketing companies, fuel types and rum types for filter drop-downs. Read-only, so every signed-in user can load it. */
@Injectable({ providedIn: 'root' })
export class LookupsService {
  private readonly api = inject(ApiService);
  readonly sites = signal<Lookup[]>([]);
  readonly omcs = signal<Lookup[]>([]);
  readonly fuelTypes = signal<Lookup[]>([]);
  readonly rumTypes = signal<Lookup[]>([]);
  readonly containerTypes = signal<Lookup[]>([]);
  private loaded = false;

  ensureLoaded(): void {
    if (this.loaded) return;
    this.loaded = true;
    this.reload();
  }

  reload(): void {
    this.api.lookups().subscribe({
      next: l => { this.sites.set(l.sites); this.omcs.set(l.omcs); this.fuelTypes.set(l.fuelTypes ?? []); this.rumTypes.set(l.rumTypes ?? []); this.containerTypes.set(l.containerTypes ?? []); },
      error: () => (this.loaded = false)
    });
  }
}
