import { Component, computed, input, output } from '@angular/core';

@Component({
  selector: 'app-pager',
  standalone: true,
  template: `
    @if (total() > 0) {
      <div class="pager">
        <span class="muted">{{ from() }}–{{ to() }} of {{ total() }}</span>
        <button type="button" (click)="go(page() - 1)" [disabled]="page() === 0" aria-label="Previous page">‹ Prev</button>
        <button type="button" (click)="go(page() + 1)" [disabled]="to() >= total()" aria-label="Next page">Next ›</button>
      </div>
    }
  `,
  styles: [`.pager { display: flex; align-items: center; justify-content: flex-end; gap: 8px; padding-top: 12px; }`]
})
export class PagerComponent {
  page = input.required<number>();
  size = input.required<number>();
  total = input.required<number>();
  pageChange = output<number>();

  from = computed(() => this.page() * this.size() + 1);
  to = computed(() => Math.min(this.total(), (this.page() + 1) * this.size()));

  go(p: number): void { this.pageChange.emit(Math.max(0, p)); }
}
