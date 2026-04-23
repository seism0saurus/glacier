import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PollView } from '../../model/readonly-toot-view';

/**
 * Renders a read-only poll from a toot.
 *
 * Accessibility:
 * - Uses radio-group semantics (fieldset + legend).
 * - All inputs are `disabled` (readonly viewer cannot vote).
 * - aria-label on the fieldset.
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
            [attr.aria-label]="optionAriaLabel(option.title)"
            disabled
            [checked]="false"
          />
          <label [attr.for]="optionId($index)">
            <!-- option.title via interpolation — no innerHTML -->
            {{ option.title }}
            <span class="poll-votes">({{ option.votesCount }})</span>
          </label>
        </div>
      }
      <p class="poll-total" aria-live="off">
        {{ poll.votesCount }} Stimmen gesamt
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
    .poll-votes { color: var(--mat-sys-on-surface-variant, #666); font-size: 0.875rem; }
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

  optionAriaLabel(title: string): string {
    return $localize`:@@share.toot.poll.option.label:Option: ${title}`;
  }
}
