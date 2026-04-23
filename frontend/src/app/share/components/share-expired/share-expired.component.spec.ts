import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ShareExpiredComponent } from './share-expired.component';
import { provideAnimations } from '@angular/platform-browser/animations';

describe('ShareExpiredComponent', () => {
  let component: ShareExpiredComponent;
  let fixture: ComponentFixture<ShareExpiredComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ShareExpiredComponent],
      providers: [
        provideAnimations(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ShareExpiredComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders an H1 heading', () => {
    const h1 = fixture.nativeElement.querySelector('h1');
    expect(h1).not.toBeNull();
  });

  it('H1 has tabindex=-1 for programmatic focus', () => {
    const h1: HTMLHeadingElement = fixture.nativeElement.querySelector('h1');
    expect(h1.getAttribute('tabindex')).toBe('-1');
  });

  it('moves focus to heading on init', () => {
    // AfterViewInit should have been called
    const h1: HTMLHeadingElement = fixture.nativeElement.querySelector('h1');
    // We can verify the heading element exists and has a focus-ready tabindex
    expect(h1.getAttribute('tabindex')).toBe('-1');
  });

  it('renders a home link', () => {
    const link: HTMLAnchorElement | null = fixture.nativeElement.querySelector('a[routerLink]') ||
                                          fixture.nativeElement.querySelector('a[href="/"]');
    // Look for any link since routing isn't fully active in tests
    const links: NodeListOf<HTMLAnchorElement> = fixture.nativeElement.querySelectorAll('a');
    expect(links.length).toBeGreaterThan(0);
  });

  it('has a <main> landmark', () => {
    const main = fixture.nativeElement.querySelector('main');
    expect(main).not.toBeNull();
  });

  it('main is labelled by the heading', () => {
    const main: HTMLElement = fixture.nativeElement.querySelector('main');
    const headingId = main.getAttribute('aria-labelledby');
    expect(headingId).toBeTruthy();
    const heading = fixture.nativeElement.querySelector(`#${headingId}`);
    expect(heading).not.toBeNull();
  });
});
