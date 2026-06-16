import { ComponentFixture, TestBed } from '@angular/core/testing';
import { QrCodeComponent } from './qr-code.component';
import { provideAnimations } from '@angular/platform-browser/animations';

describe('QrCodeComponent', () => {
  let component: QrCodeComponent;
  let fixture: ComponentFixture<QrCodeComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [QrCodeComponent],
      providers: [provideAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(QrCodeComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  // ---- Accessibility ----

  it('renders a <button> with a role', () => {
    fixture.detectChanges();
    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="qr-badge-button"]');
    expect(btn).not.toBeNull();
    expect(btn.tagName.toLowerCase()).toBe('button');
  });

  it('button has an aria-label', () => {
    fixture.detectChanges();
    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="qr-badge-button"]');
    expect(btn.getAttribute('aria-label')).toBeTruthy();
  });

  it('uses the provided ariaLabel', () => {
    component.ariaLabel = '1 aktiver Link · Dialog öffnen';
    fixture.detectChanges();
    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="qr-badge-button"]');
    expect(btn.getAttribute('aria-label')).toBe('1 aktiver Link · Dialog öffnen');
  });

  // ---- QR rendering ----

  it('shows placeholder when url is null', () => {
    component.url = null;
    fixture.detectChanges();
    const placeholder = fixture.nativeElement.querySelector('.qr-placeholder');
    expect(placeholder).not.toBeNull();
  });

  it('shows placeholder when url is empty string', () => {
    component.url = '';
    fixture.detectChanges();
    const placeholder = fixture.nativeElement.querySelector('.qr-placeholder');
    expect(placeholder).not.toBeNull();
  });

  it('shows canvas when url is provided', () => {
    component.url = 'https://share.glacier.events/share/abc123';
    fixture.detectChanges();
    const canvas = fixture.nativeElement.querySelector('[data-testid="qr-canvas"]');
    expect(canvas).not.toBeNull();
  });

  it('canvas has aria-hidden (QR is decorative; label on button is the accessible name)', () => {
    component.url = 'https://share.glacier.events/share/abc123';
    fixture.detectChanges();
    const canvas = fixture.nativeElement.querySelector('[data-testid="qr-canvas"]');
    expect(canvas.getAttribute('aria-hidden')).toBe('true');
  });

  // ---- TOOT-04: aria-haspopup="dialog" on modal trigger ----

  it('TOOT-04: button must have aria-haspopup="dialog" to indicate modal opens', () => {
    fixture.detectChanges();
    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="qr-badge-button"]');
    expect(btn.getAttribute('aria-haspopup'))
      .withContext('QR badge button must declare aria-haspopup="dialog" (WCAG 4.1.2)')
      .toBe('dialog');
  });

  // ---- Event emission ----

  it('emits openDialog on button click', () => {
    fixture.detectChanges();
    let emitted = false;
    component.openDialog.subscribe(() => (emitted = true));

    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="qr-badge-button"]');
    btn.click();

    expect(emitted).toBeTrue();
  });

  it('emits openDialog on Enter keydown', () => {
    fixture.detectChanges();
    let emitted = false;
    component.openDialog.subscribe(() => (emitted = true));

    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="qr-badge-button"]');
    const event = new KeyboardEvent('keydown', { key: 'Enter' });
    btn.dispatchEvent(event);

    expect(emitted).toBeTrue();
  });

  it('emits openDialog on Space keydown', () => {
    fixture.detectChanges();
    let emitted = false;
    component.openDialog.subscribe(() => (emitted = true));

    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="qr-badge-button"]');
    const event = new KeyboardEvent('keydown', { key: ' ' });
    btn.dispatchEvent(event);

    expect(emitted).toBeTrue();
  });
});
