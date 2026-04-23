import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { CwToggleComponent } from './cw-toggle.component';
import { provideAnimations } from '@angular/platform-browser/animations';

describe('CwToggleComponent', () => {
  let component: CwToggleComponent;
  let fixture: ComponentFixture<CwToggleComponent>;
  let liveAnnouncerSpy: jasmine.SpyObj<LiveAnnouncer>;

  beforeEach(async () => {
    liveAnnouncerSpy = jasmine.createSpyObj<LiveAnnouncer>('LiveAnnouncer', ['announce']);

    await TestBed.configureTestingModule({
      imports: [CwToggleComponent],
      providers: [
        provideAnimations(),
        { provide: LiveAnnouncer, useValue: liveAnnouncerSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CwToggleComponent);
    component = fixture.componentInstance;
    component.tootId = 'test-toot-1';
    component.spoilerText = 'Content warning: sensitive topic';
    fixture.detectChanges();
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
});
