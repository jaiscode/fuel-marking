import { Injectable, signal } from '@angular/core';

export interface Toast { kind: 'error' | 'success'; text: string; }

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly current = signal<Toast | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;

  error(text: string): void { this.show({ kind: 'error', text }, 7000); }
  success(text: string): void { this.show({ kind: 'success', text }, 3500); }
  dismiss(): void { this.current.set(null); }

  private show(t: Toast, ms: number): void {
    clearTimeout(this.timer);
    this.current.set(t);
    this.timer = setTimeout(() => this.current.set(null), ms);
  }
}
