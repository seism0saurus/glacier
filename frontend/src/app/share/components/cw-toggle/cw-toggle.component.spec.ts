import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChangeDetectorRef } from '@angular/core';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { CwToggleComponent } from './cw-toggle.component';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

describe('CwToggleComponent', () => {
  let component: CwToggleComponent;
  let fixture: ComponentFixture<CwToggleComponent>;
  let liveAnnouncerSpy: jasmine.SpyObj<LiveAnnouncer>;

  /**
   * OnPush components do not re-render on direct property mutation unless the
   * component's ChangeDetectorRef is notified. This helper sets `isOpen` and
   * marks the component dirty so `fixture.detectChanges()` will re-render.
   */
  function setIsOpen(value: boolean): void {
    component.isOpen = value;
    fixture.debugElement.injector.get(ChangeDetectorRef).markForCheck();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    liveAnnouncerSpy = jasmine.createSpyObj<LiveAnnouncer>('LiveAnnouncer', ['announce']);

    await TestBed.configureTestingModule({
      imports: [CwToggleComponent],
      providers: [
        provideAnimations(),
        { provide: LiveAnnouncer, useValue: liveAnnouncerSpy },
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CwToggleComponent);
    component = fixture.componentInstance;
    component.tootId = 'test-toot-1';
    component.spoilerText = 'Content warning: sensitive topic';
    fixture.detectChanges();
  });

  it('renders the CW warning as a local SVG icon (not a Material font ligature)', () => {
    const icon: HTMLElement = fixture.nativeElement.querySelector('mat-icon');
    expect(icon).withContext('warning icon must exist').toBeTruthy();
    expect(icon.getAttribute('data-mat-icon-type'))
      .withContext('icon must use the local svgIcon path, not a font ligature')
      .toBe('svg');
    expect(icon.getAttribute('data-mat-icon-name')).toBe('warning_icon');
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders native <details> element (accessible by screen readers)', () => {
    const details = fixture.nativeElement.querySelector('details');
    expect(details).not.toBeNull();
  });

  it('renders <summary> with aria-expanded=false initially', () => {
    const summary = fixture.nativeElement.querySelector('summary');
    expect(summary.getAttribute('aria-expanded')).toBe('false');
  });

  it('renders spoilerText as literal text, not HTML', () => {
    // Use setInput so Angular OnPush properly re-renders the @Input() binding.
    fixture.componentRef.setInput('spoilerText', '<script>alert(1)</script>');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('script')).toBeNull();
    expect(el.textContent).toContain('<script>alert(1)</script>');
  });

  it('has data-testid="cw-toggle"', () => {
    const el = fixture.nativeElement.querySelector('[data-testid="cw-toggle"]');
    expect(el).not.toBeNull();
  });

  it('announces content shown when opened', () => {
    component.isOpen = false;
    fixture.detectChanges();

    // Simulate opening
    const details = fixture.nativeElement.querySelector('details');
    details.open = true;
    component.onToggle({ target: details } as unknown as Event);

    expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
      jasmine.stringMatching(/Inhalt eingeblendet/),
      'polite'
    );
  });

  it('uses unique IDs per tootId', () => {
    expect(component.detailsId).toBe('cw-details-test-toot-1');
    expect(component.bodyId).toBe('cw-body-test-toot-1');
  });

  // ---- VIEW-05: aria-controls, neutral announce, no redundant region aria-label ----

  describe('VIEW-05 aria-controls and announcement improvements', () => {

    it('VIEW-05 summary_has_aria_controls — <summary> has [aria-controls] pointing to the body id', () => {
      const summary: HTMLElement = fixture.nativeElement.querySelector('summary');
      expect(summary.getAttribute('aria-controls')).toBe('cw-body-test-toot-1');
    });

    it('VIEW-05 aria_controls_matches_body_id — aria-controls value matches the actual body element id', () => {
      const summary: HTMLElement = fixture.nativeElement.querySelector('summary');
      const controlsId = summary.getAttribute('aria-controls');
      expect(controlsId).toBeTruthy();
      // The body element with that ID must be present when open
      setIsOpen(true);
      const body = fixture.nativeElement.querySelector(`#${controlsId}`);
      expect(body).not.toBeNull();
    });

    it('VIEW-05 open_announce_is_neutral_no_spoiler_preview — opened announcement does NOT contain spoiler text preview', () => {
      // Previously announced "Inhalt eingeblendet: <spoiler preview>" leaking CW content.
      // Now must announce a neutral state-only message (no spoiler text in announcement).
      setIsOpen(false);

      const details = fixture.nativeElement.querySelector('details');
      details.open = true;
      component.onToggle({ target: details } as unknown as Event);

      // Verify announce was called
      expect(liveAnnouncerSpy.announce).toHaveBeenCalled();
      const callArgs = liveAnnouncerSpy.announce.calls.mostRecent().args;
      const announcedText: string = callArgs[0] as string;

      // Must NOT include the spoiler text (which would defeat the content warning)
      expect(announcedText).not.toContain('sensitive topic');
      // Must be a neutral state message
      expect(announcedText).toMatch(/eingeblendet|einblenden|angezeigt/i);
    });

    it('VIEW-05 closed_announce_is_neutral — closing announces "ausgeblendet" neutral message', () => {
      // Open first
      setIsOpen(true);

      // Now close
      const details = fixture.nativeElement.querySelector('details');
      details.open = false;
      component.onToggle({ target: details } as unknown as Event);

      expect(liveAnnouncerSpy.announce).toHaveBeenCalled();
      const callArgs = liveAnnouncerSpy.announce.calls.mostRecent().args;
      const announcedText: string = callArgs[0] as string;
      // Must mention "ausgeblendet" or equivalent state
      expect(announcedText).toMatch(/ausgeblendet|ausblenden/i);
      // Must NOT contain spoiler text
      expect(announcedText).not.toContain('sensitive topic');
    });

    it('VIEW-05 cw_body_has_no_redundant_region_aria_label — the body div does NOT have role="region" with aria-label duplicating the spoiler text', () => {
      // Open the panel so body is rendered
      setIsOpen(true);

      // Verify body is present (guard for subsequent assertions)
      const bodyEl: HTMLElement | null = fixture.nativeElement.querySelector('.cw-body');
      expect(bodyEl).not.toBeNull('body element must be present when isOpen=true');

      // The body must NOT have a role="region" aria-label that duplicates the spoiler text
      const roleAttr = bodyEl ? bodyEl.getAttribute('role') : null;
      if (roleAttr === 'region') {
        const ariaLabel = bodyEl!.getAttribute('aria-label');
        expect(ariaLabel).not.toBe('Content warning: sensitive topic');
      } else {
        // No role="region" at all — this is the correct implementation
        expect(roleAttr).not.toBe('region');
      }
    });

    it('VIEW-05 collapsed_body_absent_from_dom — when isOpen=false the CW body is absent (not just hidden)', () => {
      setIsOpen(false);
      // Body with the bodyId must NOT be present in the DOM when closed,
      // so collapsed content is truly not in the AT tree.
      const body = fixture.nativeElement.querySelector(`#${component.bodyId}`);
      expect(body).toBeNull();
    });

    it('VIEW-05 expanded_body_present_in_dom — when isOpen=true the CW body IS present', () => {
      setIsOpen(true);
      const body = fixture.nativeElement.querySelector(`#${component.bodyId}`);
      expect(body).not.toBeNull();
    });
  });
});
