import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { MatIconModule } from '@angular/material/icon';

/**
 * Content-warning (CW) toggle using native `<details>`/`<summary>`.
 *
 * Accessibility:
 * - Native `<details>` semantics are understood by all major screen readers.
 * - On toggle, LiveAnnouncer announces the action with a text preview.
 * - `data-testid="cw-toggle"` is set on the root for tests.
 * - spoilerText rendered via {{ interpolation }} — no innerHTML.
 *
 * UX plan §1.2 Step 5: button aria-expanded reflects open/closed state.
 */
@Component({
  selector: 'app-cw-toggle',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, MatIconModule],
  template: `
    <details
      class="cw-toggle"
      data-testid="cw-toggle"
      [attr.id]="detailsId"
      (toggle)="onToggle($event)"
    >
      <summary
        class="cw-summary"
        [attr.aria-expanded]="isOpen"
      >
        <mat-icon aria-hidden="true" fontIcon="warning"></mat-icon>
        <span class="cw-spoiler-text">{{ spoilerText }}</span>
        <span class="cw-hint" i18n="@@share.toot.cw.toggle.show">Inhalt anzeigen</span>
      </summary>
      <div
        class="cw-body"
        [attr.id]="bodyId"
        role="region"
        [attr.aria-label]="spoilerText"
      >
        <ng-content></ng-content>
      </div>
    </details>
  `,
  styles: [`
    .cw-toggle { border: 1px solid var(--mat-sys-outline-variant, #ccc); border-radius: 4px; padding: 8px; margin: 8px 0; }
    .cw-summary { cursor: pointer; display: flex; align-items: center; gap: 8px; list-style: none; }
    .cw-summary::-webkit-details-marker { display: none; }
    .cw-hint { font-size: 0.75rem; color: var(--mat-sys-primary, #333); }
    .cw-body { padding-top: 8px; }
  `],
})
export class CwToggleComponent {
  @Input({ required: true }) tootId!: string;
  @Input({ required: true }) spoilerText!: string;

  isOpen = false;

  constructor(private liveAnnouncer: LiveAnnouncer) {}

  get detailsId(): string {
    return `cw-details-${this.tootId}`;
  }

  get bodyId(): string {
    return `cw-body-${this.tootId}`;
  }

  onToggle(event: Event): void {
    const details = event.target as HTMLDetailsElement;
    this.isOpen = details.open;
    if (this.isOpen) {
      // Announce with a preview (first 60 chars of spoiler text)
      const preview = this.spoilerText.substring(0, 60);
      this.liveAnnouncer.announce(
        $localize`:@@share.toot.cw.expanded.announce:Inhalt eingeblendet: ${preview}`,
        'polite',
      );
    }
  }
}
