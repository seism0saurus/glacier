import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { MatIconModule } from '@angular/material/icon';

/**
 * Content-warning (CW) toggle using native `<details>`/`<summary>`.
 *
 * Accessibility (VIEW-05 remediation):
 * - `[attr.aria-controls]="bodyId"` on `<summary>` so screen readers can
 *   navigate directly to the disclosed content.
 * - Collapsed `<details>` body is gated with `@if (isOpen)` so the warned
 *   content is NOT in the AT tree while collapsed — preventing warned content
 *   from being read before the user consciously opens the toggle.
 * - Neutral state announcement ("Inhalt eingeblendet" / "Inhalt ausgeblendet")
 *   replaces the old spoiler-text preview announcement, which defeated the
 *   purpose of the content warning by leaking its text to screen readers.
 * - The `cw-body` div no longer carries a redundant `role="region"` +
 *   `aria-label` that duplicated the visible `<summary>` text.
 * - Native `<details>` semantics are understood by all major screen readers.
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
        [attr.aria-controls]="bodyId"
      >
        <mat-icon aria-hidden="true" fontIcon="warning"></mat-icon>
        <span class="cw-spoiler-text">{{ spoilerText }}</span>
        <span class="cw-hint" i18n="@@share.toot.cw.toggle.show">Inhalt anzeigen</span>
      </summary>
      @if (isOpen) {
        <!--
          VIEW-05: Body only rendered in the DOM when open so collapsed content
          is completely absent from the AT tree (not just hidden).
          No role="region" + aria-label to avoid duplicating the summary text.
        -->
        <div
          class="cw-body"
          [attr.id]="bodyId"
        >
          <ng-content></ng-content>
        </div>
      }
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

  /**
   * VIEW-05: Handle the native `<details>` toggle event.
   *
   * Announces a NEUTRAL state message — NOT the spoiler text preview.
   * Previewing the spoiler text in the announcement defeats the purpose of the
   * content warning (the user has not yet decided to reveal it).
   *
   * - Opened: announces "Inhalt eingeblendet" (@@share.toot.cw.expanded.announce)
   * - Closed:  announces "Inhalt ausgeblendet" (@@share.toot.cw.collapsed.announce)
   */
  onToggle(event: Event): void {
    const details = event.target as HTMLDetailsElement;
    this.isOpen = details.open;
    if (this.isOpen) {
      this.liveAnnouncer.announce(
        $localize`:@@share.toot.cw.expanded.announce:Inhalt eingeblendet`,
        'polite',
      );
    } else {
      this.liveAnnouncer.announce(
        $localize`:@@share.toot.cw.collapsed.announce:Inhalt ausgeblendet`,
        'polite',
      );
    }
  }
}
