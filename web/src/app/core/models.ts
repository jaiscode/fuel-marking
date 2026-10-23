// Mirrors the central backend's read API (ReadModels.java) and admin API (MasterDataService.java).

export interface Page<T> { items: T[]; total: number; page: number; size: number; }

export interface DayPoint { day: string; trucks: number; rumMl: number; }

export interface Overview {
  trucks: number; completed: number; fuelLitres: number; rumMl: number;
  incidents: number; incidentMl: number; openShifts: number; sitesReporting: number; byDay: DayPoint[];
}

export interface SiteStatus {
  id: string; code: string; name: string; siteType: string; active: boolean;
  lastSyncAt: string | null; lastSyncAgeSeconds: number | null; shiftOpen: boolean; trucksToday: number;
}

export interface MarkingRow {
  id: string; siteId: string; siteName: string; orderNumber: string; truckNumber: string; trailerNumber: string | null; omc: string;
  fuelType: string; declaredVolumeL: number; rumMl: number; status: string; rumType: string | null;
  registeredAt: string; completedAt: string | null;
}
/** fuelType / rumType are empty on markings from before a compartment carried its own fuel. */
export interface Compartment { number: string; capacity: number; volume: number; fuelType: string | null; rumType: string | null; }
/** One dose of a marking: the rum for one compartment, as required and as weighed (SCALE | MANUAL). */
export interface Dose { compartment: string | null; rumType: string; requiredG: number; measuredG: number; source: string; dosedAt: string | null; }
export interface SignatureInfo { id: string; step: string; signedBy: string; signedAt: string; }
export interface MarkingDetail {
  marking: MarkingRow; dosedAt: string | null; dispensedAt: string | null;
  compartments: Compartment[]; signatures: SignatureInfo[]; doses: Dose[];
  /** Required by the rules, weighed on the scale, and where the number came from (SCALE | MANUAL). */
  doseRequiredG: number | null; doseMeasuredG: number | null; doseSource: string | null;
}

export interface SummaryRow { key: string; label: string; trucks: number; fuelLitres: number; rumMl: number; }

export interface ContainerRow {
  id: string; siteId: string; siteName: string; barcode: string; type: string; status: string;
  capacityMl: number; volumeMl: number; topSeal: string | null; bottomSeal: string | null;
}
export interface InventoryRow { siteName: string; type: string; status: string; containers: number; volumeMl: number; }

export interface IncidentRow {
  id: string; siteId: string; siteName: string; type: string; quantityMl: number; description: string | null;
  container: string | null; reportedBy: string; occurredAt: string; rumType: string | null;
}

export interface ShiftRow {
  id: string; siteId: string; siteName: string; supervisor: string; startedAt: string; endedAt: string | null;
  status: string; openingMl: number; closingMl: number | null; varianceMl: number | null;
}

export interface TransferRow {
  id: string; fromSite: string; toSite: string; status: string; dispatchedAt: string; items: number; received: number; kind: string;
}
export interface TransferItem { barcode: string; received: boolean; }
/** A warehouse stock count: grams of rum in the IBCs and drums, as counted against what the system held. */
export interface StockCountRow {
  id: string; siteId: string; siteName: string; countedBy: string; countedAt: string; comment: string | null;
  totalSystemG: number; totalCountedG: number; totalVarianceG: number; containers: number; differing: number; kind: string;
}
export interface StockCountLine { barcode: string; containerType: string | null; rumTypeCode: string | null; systemG: number; countedG: number; varianceG: number; source: string | null; cause: string | null; note: string | null;
  /** For a sealed jerrican counted at a reconciliation: FOUND (seal intact), MISSING or SEAL_BROKEN. */ check: string | null; }
export interface StockCountDetail { count: StockCountRow; lines: StockCountLine[]; }
export interface TransferDetail { transfer: TransferRow; items: TransferItem[]; }

// ---- master data (admin API)
export interface Site { id: string; code: string; name: string; siteType: string; active: boolean; }
export interface Omc { id: string; code: string; name: string; active: boolean; }
export interface AppUser { id: string; username: string; fullName: string; role: string; siteId: string | null; active: boolean; }

export const SITE_TYPES = ['FIXED', 'MOBILE', 'WAREHOUSE'] as const;
export const ROLES = ['SUPERVISOR', 'DOSING_OPERATOR', 'DISPENSE_OPERATOR', 'WAREHOUSE_MANAGER'] as const;
export const CONTAINER_CATEGORIES = ['BULK', 'JERRICAN'] as const;
/** DELETED: registered by mistake and deleted before its first dose; listed only when asked for, and counted nowhere. */
export const MARKING_STATUSES = ['REGISTERED', 'DOSED', 'DISPENSED', 'COMPLETED', 'DELETED'] as const;

/** Who the backend believes the caller is (GET /api/web/me). */
export interface Me { subject: string; name: string; email: string | null; admin: boolean; roles: string[]; }

/** Sign-in settings published by the backend (GET /api/public/auth-config). */
export interface AuthConfig { issuer: string; clientId: string; scope: string; audience?: string; }

/** Names for filter drop-downs (GET /api/web/lookups); open to anyone who can read reports. */
/** rumTypeId / rumTypeCode are set on fuel types: the one rum type the fuel is marked with. */
export interface Lookup { id: string; code: string; name: string; active: boolean; color?: string | null; rumTypeId?: string | null; rumTypeCode?: string | null; }
export interface Lookups { sites: Lookup[]; omcs: Lookup[]; fuelTypes: Lookup[]; rumTypes: Lookup[]; containerTypes: Lookup[]; }

/** Fuel types and rum types are master data with the same shape. */
export type CodeName = Lookup;

/** A jerrican registered at head office, with the latest state a site reported for it (null until one has). */
export interface JerricanRow {
  id: string; barcode: string; containerTypeId: string; containerTypeCode: string; containerTypeName: string; seals: number; capacityG: number; rumTypeId: string; rumTypeCode: string; rumColor: string; active: boolean;
  status: string | null; siteName: string | null; volumeG: number | null;
}
/** A container type from the master data: bulk (IBC, drum ...) or jerrican (plastic, metal ...); capacity is grams of rum, null = no usual size. */
export interface ContainerTypeRow { id: string; code: string; name: string; category: 'BULK' | 'JERRICAN'; seals: number; defaultCapacityG: number | null; active: boolean; }
export interface JerricanInput { id?: string; barcode: string; containerTypeId: string; capacityG: number; rumTypeId: string; active?: boolean; }

/** Master-data changes still waiting to be delivered to the sites (GET /api/admin/master-data/status). */
export interface MasterDataStatus { pending: number; oldestPendingSeconds: number | null; lastError: string | null; }
