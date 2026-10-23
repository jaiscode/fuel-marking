import { Component, OnDestroy, OnInit, inject, input, signal } from '@angular/core';
import { ApiService } from '../core/api.service';

/** Loads a signature through the authenticated HTTP client (an <img src> cannot send the Authorization header). */
@Component({
  selector: 'app-signature-image',
  standalone: true,
  template: `
    @if (url()) { <img [src]="url()" [alt]="alt()" width="220" height="90" /> }
    @else if (failed()) { <span class="muted">Image unavailable</span> }
    @else { <span class="muted">Loading…</span> }
  `,
  styles: [`img { background: #fff; border: 1px solid var(--border); border-radius: 6px; object-fit: contain; max-width: 100%; }`]
})
export class SignatureImageComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  id = input.required<string>();
  alt = input('Signature');
  url = signal<string | null>(null);
  failed = signal(false);

  ngOnInit(): void {
    this.api.signatureImage(this.id()).subscribe({
      next: blob => this.url.set(URL.createObjectURL(blob)),
      error: () => this.failed.set(true)
    });
  }

  ngOnDestroy(): void {
    const u = this.url();
    if (u) URL.revokeObjectURL(u);
  }
}
