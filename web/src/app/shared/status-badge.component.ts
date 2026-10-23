import { Component, computed, input } from '@angular/core';

const TONES: Record<string, string> = {
  COMPLETED: 'ok', CLOSED: 'ok', RECEIVED: 'ok', SEALED: 'info', OPENED: 'info', OPEN: 'info', DISPATCHED: 'info', IN_TRANSIT: 'warn',
  REGISTERED: 'warn', DOSED: 'warn', DISPENSED: 'warn', EMPTY: '', DELETED: 'bad', MISSING: 'bad', SEAL_BROKEN: 'bad', FOUND: 'ok', SPILLAGE: 'bad', LEAKAGE: 'bad', OTHER: 'warn', SHIFT: 'info', WAREHOUSE: 'info'
};

@Component({
  selector: 'app-status-badge',
  standalone: true,
  template: `<span class="badge" [class]="tone()">{{ label() }}</span>`
})
export class StatusBadgeComponent {
  value = input.required<string>();
  tone = computed(() => TONES[this.value()] ?? '');
  label = computed(() => this.value().replace(/_/g, ' ').toLowerCase().replace(/^./, c => c.toUpperCase()));
}
