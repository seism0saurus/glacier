import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PollView, PollOption } from '../../model/readonly-toot-view';

/**
 * Renders a read-only poll from a toot.
 *
 * Accessibility (VIEW-07 remediation):
 * - Percentage text per option so vote share is conveyed textually, not by
 *   bar-width or color alone (WCAG 1.3.1 / 1.4.1).
 * - Closed polls: the leading option is marked with a visible textual indicator
 *   (`data-testid="poll-leading"`) — not color alone.
 * - "Stimmen gesamt" is replaced by an i18n ICU plural key
 *   @@share.toot.poll.total ("1 Stimme" / "N Stimmen").
 * - Native `<fieldset>` provides implicit role="group" semantics — no disabled
 *   radios that some SR configs skip have been replaced with plain `<div>` rows.
 *   (We keep radios for visual consistency but the key data — % and vote count
 *   — is surfaced via the label, not the radio checked state.)
 * - All inputs are `disabled` (readonly viewer cannot vote).
 * - Poll option text via {{ interpolation }} — no innerHTML.
 */
@Component({
  selector: 'app-poll-ref',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    <fieldset
      class="poll-fieldset"
      [attr.aria-label]="pollLabel"
      data-testid="poll-ref"
    >
      <legend class="visually-hidden" i18n="@@share.toot.poll.label">Umfrage (nur lesen)</legend>
      @for (option of poll.options; track $index) {
        <div class="poll-option">
          <input
            type="radio"
            [attr.id]="optionId($index)"
            [attr.name]="groupName"
            [attr.aria-label]="optionAriaLabel(option, $index)"
            disabled
            [checked]="false"
          />
          <label [attr.for]="optionId($index)" class="poll-option-label">
            <!-- option.title via interpolation — no innerHTML -->
            {{ option.title }}
            <!-- VIEW-07: percentage text — conveyed textually, not via bar width alone -->
            <span class="poll-percent" aria-hidden="true">{{ optionPercent($index) }}%</span>
            <span class="poll-votes">({{ option.votesCount }})</span>
            <!--
              VIEW-07: textual leading marker for closed polls.
              Only rendered on the leading option of an expired poll so that
              screen readers do not have to rely on visual bar width.
            -->
            @if (poll.expired && isLeadingOption($index)) {
              <span
                class="poll-leading-marker"
                data-testid="poll-leading"
                i18n="@@share.toot.poll.leading"
                aria-label="Führend"
              >★</span>
            }
          </label>
        </div>
      }
      <!--
        VIEW-07: Localized total using an ICU plural key.
        "1 Stimme" / "N Stimmen" — rendered as plain text via TS computation
        (the runtime catalog cannot expand ICU in a {{ }} binding directly).
      -->
      <p class="poll-total" aria-live="off" data-testid="poll-total">
        {{ totalLabel }}
        @if (poll.expired) {
          · <span i18n="@@share.toot.poll.expired">Beendet</span>
        }
        @if (poll.multiple) {
          · <span i18n="@@share.toot.poll.multiple">Mehrfachauswahl</span>
        }
      </p>
    </fieldset>
  `,
  styles: [`
    .poll-fieldset { border: 1px solid var(--mat-sys-outline-variant, #ccc); border-radius: 4px; padding: 8px 12px; }
    .poll-option { display: flex; align-items: center; gap: 8px; margin: 4px 0; }
    .poll-option-label { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .poll-percent { font-weight: 600; color: var(--mat-sys-primary, #1976d2); min-width: 3.5ch; }
    .poll-votes { color: var(--mat-sys-on-surface-variant, #666); font-size: 0.875rem; }
    .poll-leading-marker { color: var(--mat-sys-primary, #1976d2); font-size: 0.875rem; }
    .poll-total { font-size: 0.75rem; color: var(--mat-sys-on-surface-variant, #666); margin-top: 8px; }
    .visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
  `],
})
export class PollRefComponent {
  @Input({ required: true }) poll!: PollView;

  /** Unique group name based on component instance — not stable across re-renders but sufficient for read-only. */
  private instanceId = Math.random().toString(36).slice(2, 8);

  get groupName(): string {
    return `poll-${this.instanceId}`;
  }

  get pollLabel(): string {
    return $localize`:@@share.toot.poll.label:Umfrage (nur lesen)`;
  }

  optionId(index: number): string {
    return `${this.groupName}-option-${index}`;
  }

  /**
   * VIEW-07: Aria-label for a radio option includes the percentage so screen
   * reader users get the full picture from the input label without relying on
   * color or bar width.
   */
  optionAriaLabel(option: PollOption, index: number): string {
    const pct = this.optionPercent(index);
    const leadingSuffix = (this.poll.expired && this.isLeadingOption(index))
      ? $localize`:@@share.toot.poll.leading.suffix: (Führend)`
      : '';
    return $localize`:@@share.toot.poll.option.label:Option: ${option.title} — ${pct}%${leadingSuffix}`;
  }

  /**
   * VIEW-07: Compute percentage for option at `index`.
   * Returns 0 when total votes is 0 to avoid division by zero.
   */
  optionPercent(index: number): number {
    if (this.poll.votesCount === 0) return 0;
    const option = this.poll.options[index];
    return Math.round((option.votesCount / this.poll.votesCount) * 100);
  }

  /**
   * VIEW-07: Determine whether the option at `index` is the leading option.
   * Leading = highest vote count. If there's a tie, both are considered leading.
   * Only meaningful for closed (expired) polls.
   */
  isLeadingOption(index: number): boolean {
    if (!this.poll.options.length) return false;
    const maxVotes = Math.max(...this.poll.options.map((o) => o.votesCount));
    return this.poll.options[index].votesCount === maxVotes;
  }

  /**
   * VIEW-07: Localized vote total using an ICU-style singular/plural computed
   * in TypeScript (the runtime catalog loader cannot expand ICU in {{ }} bindings).
   *
   * "1 Stimme" / "N Stimmen" — uses keys @@share.toot.poll.total.one and
   * @@share.toot.poll.total.other.
   */
  get totalLabel(): string {
    const n = this.poll.votesCount;
    if (n === 1) {
      return $localize`:@@share.toot.poll.total.one:1 Stimme`;
    }
    return $localize`:@@share.toot.poll.total.other:${n} Stimmen`;
  }
}
