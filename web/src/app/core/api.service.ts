import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AppUser, AuthConfig, CodeName, ContainerTypeRow, JerricanInput, JerricanRow, Lookups, MasterDataStatus, Me, ContainerRow, IncidentRow, InventoryRow, MarkingDetail, MarkingRow, Omc, Overview, Page, ShiftRow,
  Site, SiteStatus, StockCountDetail, StockCountRow, SummaryRow, TransferDetail, TransferRow
} from './models';

export type Query = Record<string, string | number | null | undefined>;

/** Typed client for the central backend. Empty filter values are dropped. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  private params(q: Query = {}): HttpParams {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(q)) if (v !== null && v !== undefined && v !== '') p = p.set(k, String(v));
    return p;
  }

  // ---- identity
  authConfig(): Observable<AuthConfig> { return this.http.get<AuthConfig>('/api/public/auth-config'); }
  lookups(): Observable<Lookups> { return this.http.get<Lookups>('/api/web/lookups'); }
  me(): Observable<Me> { return this.http.get<Me>('/api/web/me'); }

  // ---- reads
  overview(q: Query): Observable<Overview> { return this.http.get<Overview>('/api/web/overview', { params: this.params(q) }); }
  siteStatus(): Observable<SiteStatus[]> { return this.http.get<SiteStatus[]>('/api/web/sites/status'); }
  markings(q: Query): Observable<Page<MarkingRow>> { return this.http.get<Page<MarkingRow>>('/api/web/markings', { params: this.params(q) }); }
  marking(id: string): Observable<MarkingDetail> { return this.http.get<MarkingDetail>(`/api/web/markings/${id}`); }
  markingSummary(groupBy: string, q: Query): Observable<SummaryRow[]> {
    return this.http.get<SummaryRow[]>('/api/web/markings/summary', { params: this.params({ ...q, groupBy }) });
  }
  containers(q: Query): Observable<Page<ContainerRow>> { return this.http.get<Page<ContainerRow>>('/api/web/containers', { params: this.params(q) }); }
  inventory(q: Query): Observable<InventoryRow[]> { return this.http.get<InventoryRow[]>('/api/web/inventory', { params: this.params(q) }); }
  incidents(q: Query): Observable<Page<IncidentRow>> { return this.http.get<Page<IncidentRow>>('/api/web/incidents', { params: this.params(q) }); }
  shifts(q: Query): Observable<Page<ShiftRow>> { return this.http.get<Page<ShiftRow>>('/api/web/shifts', { params: this.params(q) }); }
  stockCounts(q: Query): Observable<Page<StockCountRow>> { return this.http.get<Page<StockCountRow>>('/api/web/stock-counts', { params: this.params(q) }); }
  stockCount(id: string): Observable<StockCountDetail> { return this.http.get<StockCountDetail>(`/api/web/stock-counts/${id}`); }
  transfers(q: Query): Observable<Page<TransferRow>> { return this.http.get<Page<TransferRow>>('/api/web/transfers', { params: this.params(q) }); }
  transfer(id: string): Observable<TransferDetail> { return this.http.get<TransferDetail>(`/api/web/transfers/${id}`); }
  signatureImage(id: string): Observable<Blob> { return this.http.get(`/api/web/signatures/${id}/image`, { responseType: 'blob' }); }

  // ---- master data
  sites(): Observable<Site[]> { return this.http.get<Site[]>('/api/admin/sites'); }
  saveSite(s: Partial<Site>): Observable<Site> {
    return s.id ? this.http.put<Site>(`/api/admin/sites/${s.id}`, s) : this.http.post<Site>('/api/admin/sites', s);
  }
  issueKey(siteId: string): Observable<{ siteId: string; apiKey: string }> {
    return this.http.post<{ siteId: string; apiKey: string }>(`/api/admin/sites/${siteId}/api-key`, null);
  }
  omcs(): Observable<Omc[]> { return this.http.get<Omc[]>('/api/admin/omcs'); }
  saveOmc(o: Partial<Omc>): Observable<Omc> {
    return o.id ? this.http.put<Omc>(`/api/admin/omcs/${o.id}`, o) : this.http.post<Omc>('/api/admin/omcs', o);
  }
  locations(): Observable<CodeName[]> { return this.http.get<CodeName[]>('/api/admin/locations'); }
  saveLocation(l: Partial<CodeName>): Observable<CodeName> {
    return l.id ? this.http.put<CodeName>(`/api/admin/locations/${l.id}`, l) : this.http.post<CodeName>('/api/admin/locations', l);
  }
  fuelTypes(): Observable<CodeName[]> { return this.http.get<CodeName[]>('/api/admin/fuel-types'); }
  saveFuelType(f: Partial<CodeName>): Observable<CodeName> {
    return f.id ? this.http.put<CodeName>(`/api/admin/fuel-types/${f.id}`, f) : this.http.post<CodeName>('/api/admin/fuel-types', f);
  }
  rumTypes(): Observable<CodeName[]> { return this.http.get<CodeName[]>('/api/admin/rum-types'); }
  saveRumType(r: Partial<CodeName>): Observable<CodeName> {
    return r.id ? this.http.put<CodeName>(`/api/admin/rum-types/${r.id}`, r) : this.http.post<CodeName>('/api/admin/rum-types', r);
  }
  containerTypes(): Observable<ContainerTypeRow[]> { return this.http.get<ContainerTypeRow[]>('/api/admin/container-types'); }
  saveContainerType(t: Partial<ContainerTypeRow>): Observable<ContainerTypeRow> {
    return t.id ? this.http.put<ContainerTypeRow>(`/api/admin/container-types/${t.id}`, t) : this.http.post<ContainerTypeRow>('/api/admin/container-types', t);
  }
  jerricans(): Observable<JerricanRow[]> { return this.http.get<JerricanRow[]>('/api/admin/jerricans'); }
  saveJerrican(j: JerricanInput): Observable<unknown> {
    return j.id ? this.http.put(`/api/admin/jerricans/${j.id}`, j) : this.http.post('/api/admin/jerricans', j);
  }
  registerJerricans(b: { barcodes: string[]; containerTypeId: string; capacityG: number; rumTypeId: string }): Observable<{ registered: number }> {
    return this.http.post<{ registered: number }>('/api/admin/jerricans/bulk', b);
  }
  users(): Observable<AppUser[]> { return this.http.get<AppUser[]>('/api/admin/users'); }
  saveUser(u: Partial<AppUser>): Observable<AppUser> {
    return u.id ? this.http.put<AppUser>(`/api/admin/users/${u.id}`, u) : this.http.post<AppUser>('/api/admin/users', u);
  }
  setPin(userId: string, pin: string): Observable<void> { return this.http.put<void>(`/api/admin/users/${userId}/pin`, { pin }); }
  masterDataStatus(): Observable<MasterDataStatus> { return this.http.get<MasterDataStatus>('/api/admin/master-data/status'); }
  republish(): Observable<{ published: number }> { return this.http.post<{ published: number }>('/api/admin/master-data/republish', null); }
}
