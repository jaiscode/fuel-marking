import { Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { MarkingDetail } from '../core/models';
import { formatTime } from '../core/dates';
import { SignatureImageComponent } from '../shared/signature-image.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { fmt0, fmt1 } from '../shared/format';

@Component({
  selector: 'app-marking-detail',
  standalone: true,
  imports: [RouterLink, SignatureImageComponent, StatusBadgeComponent],
  template: `
    <p><a routerLink="/markings">‹ All markings</a></p>
    @if (d(); as x) {
      <div class="page-head">
        <div><h1 class="mono">{{ x.marking.orderNumber }}</h1><p>{{ x.marking.truckNumber }}@if (x.marking.trailerNumber) { + {{ x.marking.trailerNumber }} }· {{ x.marking.omc }} · {{ x.marking.siteName }}@if (x.marking.location) { at {{ x.marking.location }} }</p></div>
        <app-status-badge [value]="x.marking.status" />
      </div>

      <div class="grid two" style="margin-top:0">
        <section class="card">
          <h2>Details</h2>
          <dl>
            <dt>Trailer / wagon</dt><dd>{{ x.marking.trailerNumber || '–' }}</dd>
            <dt>Fuel</dt><dd>{{ x.marking.fuelType }}</dd>
            <dt>Declared volume</dt><dd>{{ fmt0(x.marking.declaredVolumeL) }} L</dd>
            <dt>Rum type</dt><dd>{{ x.marking.rumType ? x.marking.rumType.split('+').join(' + ') : '–' }}</dd>
            <dt>Rum required</dt><dd>{{ x.doseRequiredG === null ? '–' : fmt1(x.doseRequiredG) + ' g' }}</dd>
            <dt>Rum dosed</dt>
            <dd>{{ x.doseMeasuredG !== null ? fmt1(x.doseMeasuredG) + ' g' : (x.marking.status === 'REGISTERED' ? 'not yet dosed' : fmt1(x.marking.rumMl) + ' g') }}
              @if (x.doseSource === 'SCALE') { <span class="badge ok">weighed on scale</span> }
              @if (x.doseSource === 'MANUAL') { <span class="badge warn" title="Typed in by the operator, not read from the scale">entered manually</span> }
            </dd>
          </dl>
          <h3>Compartments</h3>
          @if (x.compartments.length) {
            <div class="table-wrap"><table>
              <thead><tr><th>No.</th><th>Fuel</th><th>Rum type</th><th class="num">Capacity (L)</th><th class="num">Loaded (L)</th></tr></thead>
              <tbody>@for (c of x.compartments; track c.number) { <tr><td>{{ c.number }}</td><td>{{ c.fuelType || '–' }}</td><td>{{ c.rumType || '–' }}</td><td class="num">{{ fmt0(c.capacity) }}</td><td class="num">{{ fmt0(c.volume) }}</td></tr> }</tbody>
            </table></div>
          } @else { <p class="muted">None recorded.</p> }
          @if (x.doses.length) {
            <h3 style="margin-top:16px">Doses</h3>
            <p class="muted">One dose for each compartment, against that compartment's own target.</p>
            <div class="table-wrap"><table>
              <thead><tr><th>Compartment</th><th>Fuel</th><th>Rum type</th><th class="num">Target (g)</th><th class="num">Weighed (g)</th><th>Source</th><th>Dosed</th></tr></thead>
              <tbody>@for (d of x.doses; track (d.compartment ?? '') + d.rumType) {
                <tr><td>{{ d.compartment && d.compartment !== 'ALL' ? d.compartment : 'Whole truck' }}</td><td>{{ fuelOf(x, d.compartment) }}</td><td>{{ d.rumType }}</td><td class="num">{{ fmt1(d.requiredG) }}</td><td class="num">{{ fmt1(d.measuredG) }}</td>
                  <td>@if (d.source === 'SCALE') { <span class="badge ok">scale</span> } @else { <span class="badge warn">typed in</span> }</td><td>{{ d.dosedAt ? t(d.dosedAt) : '–' }}</td></tr>
              }</tbody>
            </table></div>
          }
        </section>

        <section class="card">
          <h2>Timeline</h2>
          <ol class="timeline">
            <li><b>Registered</b><span>{{ t(x.marking.registeredAt) }}</span></li>
            <li [class.todo]="!x.dosedAt"><b>Dosed</b><span>{{ x.dosedAt ? t(x.dosedAt) : 'pending' }}</span></li>
            <li [class.todo]="!x.dispensedAt"><b>Dispensed</b><span>{{ x.dispensedAt ? t(x.dispensedAt) : 'pending' }}</span></li>
            <li [class.todo]="!x.marking.completedAt"><b>Verified</b><span>{{ x.marking.completedAt ? t(x.marking.completedAt) : 'pending' }}</span></li>
          </ol>
          <p class="muted">Times are as recorded at the site.</p>
        </section>
      </div>

      <section class="card" style="margin-top:16px">
        <h2>Signatures</h2>
        <div class="sigs">
          @for (s of x.signatures; track s.id) {
            <figure>
              <app-signature-image [id]="s.id" [alt]="'Signature of ' + s.signedBy + ' for ' + s.step" />
              <figcaption><b>{{ step(s.step) }}</b><br />{{ s.signedBy }}<br /><span class="muted">{{ t(s.signedAt) }}</span></figcaption>
            </figure>
          } @empty { <p class="muted">No signatures received yet.</p> }
        </div>
      </section>
    } @else { <div class="card empty">Loading…</div> }
  `,
  styles: [`
    dl { display: grid; grid-template-columns: 140px 1fr; gap: 6px 12px; margin: 0 0 16px; }
    dt { color: var(--muted); } dd { margin: 0; }
    .timeline { list-style: none; margin: 0 0 8px; padding: 0 0 0 16px; border-left: 2px solid var(--accent); }
    .timeline li { display: flex; justify-content: space-between; gap: 12px; padding: 6px 0 6px 10px; }
    .timeline li.todo { color: var(--muted); }
    .sigs { display: flex; flex-wrap: wrap; gap: 16px; }
    figure { margin: 0; } figcaption { margin-top: 6px; font-size: 13px; }
  `]
})
export class MarkingDetailComponent implements OnInit {
  private readonly api = inject(ApiService);
  id = input.required<string>(); // bound from the route (withComponentInputBinding)
  d = signal<MarkingDetail | null>(null);
  readonly fmt0 = fmt0;
  readonly fmt1 = fmt1;
  readonly t = formatTime;

  ngOnInit(): void { this.api.marking(this.id()).subscribe({ next: d => this.d.set(d) }); }

  /** The fuel in the compartment a dose was for. */
  fuelOf(x: MarkingDetail, compartment: string | null): string {
    return x.compartments.find(c => c.number === compartment)?.fuelType ?? x.marking.fuelType;
  }

  step(s: string): string { return s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' '); }
}
