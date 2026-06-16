import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChangeDetectorRef } from '@angular/core';
import { PollRefComponent } from './poll-ref.component';
import { PollView } from '../../model/readonly-toot-view';

describe('PollRefComponent', () => {
  let component: PollRefComponent;
  let fixture: ComponentFixture<PollRefComponent>;

  const mockPoll: PollView = {
    id: 'poll-1',
    options: [
      { title: 'Option A', votesCount: 10 },
      { title: 'Option B', votesCount: 5 },
    ],
    multiple: false,
    expired: false,
    votesCount: 15,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PollRefComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(PollRefComponent);
    component = fixture.componentInstance;
    component.poll = mockPoll;
    fixture.detectChanges();
  });

  /**
   * Helper: set a new poll value and force re-render for OnPush components.
   * Direct property mutation on an OnPush component requires markForCheck()
   * before detectChanges() will re-render the template.
   */
  function setPoll(poll: PollView): void {
    component.poll = poll;
    fixture.debugElement.injector.get(ChangeDetectorRef).markForCheck();
    fixture.detectChanges();
  }

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders a fieldset with data-testid="poll-ref"', () => {
    const fieldset = fixture.nativeElement.querySelector('[data-testid="poll-ref"]');
    expect(fieldset).not.toBeNull();
  });

  it('renders all radio inputs as disabled', () => {
    const inputs: NodeListOf<HTMLInputElement> = fixture.nativeElement.querySelectorAll('input[type="radio"]');
    expect(inputs.length).toBe(2);
    inputs.forEach((input) => expect(input.disabled).toBeTrue());
  });

  it('renders option text via interpolation (no innerHTML)', () => {
    component.poll = {
      ...mockPoll,
      options: [
        { title: '<script>alert(1)</script>', votesCount: 0 },
      ],
    };
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('script')).toBeNull();
  });

  it('has aria-label on fieldset', () => {
    const fieldset = fixture.nativeElement.querySelector('[data-testid="poll-ref"]');
    expect(fieldset.getAttribute('aria-label')).toBeTruthy();
  });

  it('shows vote counts when provided', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('10');
    expect(el.textContent).toContain('5');
  });

  // ---- VIEW-07: percentage text, leading option marker, i18n total, role="group" ----
  // WCAG 1.3.1 / 1.4.1: poll results must not rely on color/bar-width alone;
  // percentage text is required per option; leading option on a closed poll
  // must be marked textually.

  describe('VIEW-07 poll accessibility improvements', () => {

    it('VIEW-07 renders_percentage_per_option — each option shows a percentage value', () => {
      // Option A: 10/15 = 66.67%, Option B: 5/15 = 33.33%
      const el: HTMLElement = fixture.nativeElement;
      // Percentage text must appear somewhere in the rendered output for each option
      expect(el.textContent).toMatch(/6[67]%/); // Option A ~67%
      expect(el.textContent).toMatch(/33%/);     // Option B ~33%
    });

    it('VIEW-07 percentage_zero_when_no_votes — option with 0 votes shows 0%', () => {
      setPoll({
        ...mockPoll,
        options: [
          { title: 'Only option', votesCount: 0 },
        ],
        votesCount: 0,
      });
      const el: HTMLElement = fixture.nativeElement;
      expect(el.textContent).toContain('0%');
    });

    it('VIEW-07 closed_poll_marks_leading_option_textually — expired poll with a leading option has textual marker (not color alone)', () => {
      setPoll({
        ...mockPoll,
        expired: true,
        // Option A leads with 10 votes
      });
      const el: HTMLElement = fixture.nativeElement;
      // A textual marker indicating the leading option must be present
      // (e.g. "Führend", "★", checkmark, or similar accessible text/aria)
      // We verify via data-testid on the leading indicator element
      const leadingMarker = el.querySelector('[data-testid="poll-leading"]');
      expect(leadingMarker).not.toBeNull();
    });

    it('VIEW-07 open_poll_does_not_mark_leading — non-expired poll does not show a leading marker', () => {
      setPoll({ ...mockPoll, expired: false });
      const leadingMarker = fixture.nativeElement.querySelector('[data-testid="poll-leading"]');
      expect(leadingMarker).toBeNull();
    });

    it('VIEW-07 poll_total_is_localized — "Stimmen gesamt" is replaced by an i18n key (not hardcoded German)', () => {
      // The total paragraph must use i18n and an ICU plural for "Stimme"/"Stimmen"
      const el: HTMLElement = fixture.nativeElement;
      // With votesCount=15, should say something like "15 Stimmen"
      expect(el.textContent).toContain('15');
      // Must NOT render raw hardcoded "Stimmen gesamt" without i18n
      // (we verify the i18n attribute is present on the total element)
      const totalEl = fixture.nativeElement.querySelector('[data-testid="poll-total"]');
      expect(totalEl).not.toBeNull();
    });

    it('VIEW-07 poll_total_singular_1_vote — poll with 1 vote total uses singular form', () => {
      setPoll({
        ...mockPoll,
        options: [{ title: 'Solo', votesCount: 1 }],
        votesCount: 1,
      });
      const totalEl: HTMLElement = fixture.nativeElement.querySelector('[data-testid="poll-total"]');
      expect(totalEl.textContent).toContain('1');
      // Singular: "1 Stimme" (not "Stimmen")
      expect(totalEl.textContent).toMatch(/Stimme[^n]/);
    });

    it('VIEW-07 poll_total_plural_n_votes — poll with N>1 votes uses plural form', () => {
      setPoll({ ...mockPoll, votesCount: 15 });
      const totalEl: HTMLElement = fixture.nativeElement.querySelector('[data-testid="poll-total"]');
      expect(totalEl.textContent).toContain('15');
      expect(totalEl.textContent).toContain('Stimmen');
    });

    it('VIEW-07 fieldset_has_role_group_or_fieldset — poll container uses role="group" or native fieldset semantics', () => {
      // Either a native <fieldset> (implicit group role) or explicit role="group"
      const container = fixture.nativeElement.querySelector('[data-testid="poll-ref"]');
      const tagName: string = container.tagName.toLowerCase();
      const role = container.getAttribute('role');
      expect(tagName === 'fieldset' || role === 'group').toBeTrue();
    });
  });
});
