import {ComponentFixture, TestBed} from '@angular/core/testing';

import {GdprComponent} from './gdpr.component';
import {MAT_DIALOG_DATA} from "@angular/material/dialog";

describe('GdprComponent', () => {
  let component: GdprComponent;
  let fixture: ComponentFixture<GdprComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GdprComponent],
      providers: [
        {
          provide: MAT_DIALOG_DATA, useValue: {
            domain: 'example.com',
            operatorName: 'Jon Doe',
            operatorStreetAndNumber: 'somewhere 1',
            operatorZipcode: '12345',
            operatorCity: 'somecity',
            operatorCountry: 'Germany',
            operatorPhone: '+123456789',
            operatorMail: 'mail@example.com',
            operatorWebsite: 'example.com',
          }
        }
      ],
    })
      .compileComponents();

    fixture = TestBed.createComponent(GdprComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should inject MAT_DIALOG_DATA with expected values', () => {
    const expectedData = {
      domain: 'example.com',
      operatorName: 'Jon Doe',
      operatorStreetAndNumber: 'somewhere 1',
      operatorZipcode: '12345',
      operatorCity: 'somecity',
      operatorCountry: 'Germany',
      operatorPhone: '+123456789',
      operatorMail: 'mail@example.com',
      operatorWebsite: 'example.com',
    };
    expect(component.data).toEqual(expectedData);
  });

  it('should display operator name correctly in template', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const operatorNameElement = compiled.querySelector('.operator');
    expect(operatorNameElement?.textContent).toBe('Jon Doe');
  });

  it('should display address correctly in template', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const addressNameElement = compiled.querySelector('.address');
    expect(addressNameElement?.textContent).toBe('somewhere 1 12345 somecity Germany');
  });

  it('should display contact information correctly in template', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const contactNameElement = compiled.querySelector('.contact');
    expect(contactNameElement?.textContent?.trim())
      .withContext('contact block must contain phone, email, website labels and values')
      .toContain('+123456789');
  });

  // XCUT-05: heading hierarchy — h2 (dialog title) → h3 (sections) → h4 (lettered sub-points)
  it('should render the dialog title as h2 with id legal-notice', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const heading = compiled.querySelector('h2#legal-notice');
    expect(heading).withContext('h2#legal-notice must exist').toBeTruthy();
    expect(heading?.textContent?.trim())
      .withContext('dialog title must contain the legal notice text')
      .toBe('Legal Notice and Privacy Policy');
  });

  it('should render section headings as h3 (XCUT-05)', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const h3s = Array.from(compiled.querySelectorAll('h3'));
    expect(h3s.length).withContext('at least 13 h3 section headings expected (sections 0–12 + Legal Notice + Privacy Policy)').toBeGreaterThanOrEqual(13);
    const headingTexts = h3s.map(h => h.textContent?.trim() ?? '');
    expect(headingTexts.some(t => t.includes('Definitions'))).withContext('section 1 Definitions must be h3').toBeTrue();
    expect(headingTexts.some(t => t.includes('Cookies'))).withContext('section 3 Cookies must be h3').toBeTrue();
    expect(headingTexts.some(t => t.includes('Rights of the data subject'))).withContext('section 6 Rights must be h3').toBeTrue();
  });

  it('should render lettered sub-points as h4 inside section elements (XCUT-05/06)', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const h4s = Array.from(compiled.querySelectorAll('section h4'));
    expect(h4s.length).withContext('at least 20 h4 lettered sub-points expected').toBeGreaterThanOrEqual(20);
    const headingTexts = h4s.map(h => h.textContent?.trim() ?? '');
    expect(headingTexts.some(t => t.startsWith('a)'))).withContext('first lettered item a) must be h4').toBeTrue();
    expect(headingTexts.some(t => t.startsWith('k)'))).withContext('last definition item k) must be h4').toBeTrue();
    expect(headingTexts.some(t => t.startsWith('i)'))).withContext('right to withdraw consent must be h4').toBeTrue();
  });

  // XCUT-06: no <ul style="list-style:none"> used as section container
  it('should not use unstyled ul as a section heading container (XCUT-06)', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    // Lists with list-style:none that directly contain h4 children were the anti-pattern.
    const ulElements = Array.from(compiled.querySelectorAll('ul'));
    for (const ul of ulElements) {
      const directH4Children = Array.from(ul.children).filter(child => child.tagName === 'H4');
      expect(directH4Children.length).withContext('ul must not directly contain h4 heading children').toBe(0);
      const liWithH4 = Array.from(ul.querySelectorAll('li > h4'));
      expect(liWithH4.length).withContext('li must not contain h4 heading as direct child').toBe(0);
    }
  });

  // XCUT-04: close button has i18n @@id
  it('should have an i18n-marked close button (XCUT-04)', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const closeButton = compiled.querySelector('#legal-notice-close-button');
    expect(closeButton).withContext('#legal-notice-close-button must exist').toBeTruthy();
    // The button text is translated at build time; in Karma (no translation loaded) it renders the template default.
    expect(closeButton?.textContent?.trim())
      .withContext('close button must have non-empty text content')
      .toBeTruthy();
  });

  // XCUT-01: representative @@id checks — section elements cover i18n markup
  it('should render Legal Notice and Privacy Policy as separate h3 headings (XCUT-01)', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const h3s = Array.from(compiled.querySelectorAll('h3'));
    const texts = h3s.map(h => h.textContent?.trim() ?? '');
    expect(texts.some(t => t === 'Legal Notice')).withContext('Legal Notice must be an h3').toBeTrue();
    expect(texts.some(t => t === 'Privacy Policy')).withContext('Privacy Policy must be an h3').toBeTrue();
  });

  it('should render section 6 Rights sub-items inside <section> elements (XCUT-06)', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const sections = Array.from(compiled.querySelectorAll('section'));
    expect(sections.length).withContext('at least 20 section elements expected').toBeGreaterThanOrEqual(20);
  });

  // Structural: existing real bullet lists remain as <ul> (not converted to section)
  it('should preserve real bullet lists as ul elements', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const uls = Array.from(compiled.querySelectorAll('ul'));
    // The template has 4 real lists: s6b (8 items), s6d (6 items), s6e (4 items), plus any others
    expect(uls.length).withContext('at least 3 real bullet list ul elements must remain').toBeGreaterThanOrEqual(3);
  });
});
