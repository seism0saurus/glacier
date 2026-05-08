import {ComponentFixture, TestBed} from '@angular/core/testing';
import {FooterComponent} from './footer.component';
import {provideHttpClientTesting} from "@angular/common/http/testing";
import {FooterService} from "./footer.service";
import {of} from "rxjs";
import {MatButtonModule} from "@angular/material/button";
import {MatDialog, MatDialogModule} from "@angular/material/dialog";
import {MatIconModule} from "@angular/material/icon";
import {provideHttpClient, withInterceptorsFromDi} from '@angular/common/http';
import {GdprComponent} from "../gdpr/gdpr.component";

describe('FooterComponent', () => {
  let component: FooterComponent;
  let service: jasmine.SpyObj<FooterService>;
  let fixture: ComponentFixture<FooterComponent>;
  let dialog: jasmine.SpyObj<MatDialog>;

  beforeEach(() => {
    const footerServiceMock = jasmine.createSpyObj('FooterService', ['getMastodonHandle', 'getInstanceOperator']);
    const matDialogMock = jasmine.createSpyObj('MatDialog', ['open']);
    TestBed.configureTestingModule({
      declarations: [FooterComponent],
      imports: [MatButtonModule,
        MatIconModule,
        MatDialogModule],
      providers: [
        {provide: FooterService, useValue: footerServiceMock},
        {provide: MatDialog, useValue: matDialogMock},
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting()
      ]
    });
    dialog = TestBed.inject(MatDialog) as jasmine.SpyObj<MatDialog>;
    fixture = TestBed.createComponent(FooterComponent);
    component = fixture.componentInstance;
    service = TestBed.inject(FooterService) as jasmine.SpyObj<FooterService>;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should update mastodonHandle on ngOnInit', () => {
    const expectedHandle = 'mastodon@example.com';
    const expectedInstanceOperator = {
      domain: 'example.com',
      operatorName: 'Jane Doe',
      operatorStreetAndNumber: 'Some Street 1',
      operatorZipcode: '54321',
      operatorCity: 'New City',
      operatorCountry: 'USA',
      operatorPhone: '+987654321',
      operatorMail: 'jane@example.com',
      operatorWebsite: 'new-website.com',
    };
    service.getMastodonHandle.and.returnValue(of({name: expectedHandle}));
    service.getInstanceOperator.and.returnValue(of(expectedInstanceOperator));

    component.ngOnInit();
    expect(service.getMastodonHandle).toHaveBeenCalled();
    expect(component.mastodonHandle).toBe(expectedHandle);
  });

  it('should update instanceOperator on ngOnInit', () => {
    const expectedHandle = 'mastodon@example.com';
    const expectedInstanceOperator = {
      domain: 'example.com',
      operatorName: 'Jane Doe',
      operatorStreetAndNumber: 'Some Street 1',
      operatorZipcode: '54321',
      operatorCity: 'New City',
      operatorCountry: 'USA',
      operatorPhone: '+987654321',
      operatorMail: 'jane@example.com',
      operatorWebsite: 'new-website.com',
    };
    service.getMastodonHandle.and.returnValue(of({name: expectedHandle}));
    service.getInstanceOperator.and.returnValue(of(expectedInstanceOperator));

    component.ngOnInit();
    expect(service.getInstanceOperator).toHaveBeenCalled();
    expect(component['instanceOperator']).toEqual(expectedInstanceOperator);
  });

  describe('howto hint', () => {
    it('should exist', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.howto')).toBeTruthy();
    });

    it('should contain the correct text', () => {
      const expectedHandle = 'mastodon@example.com';
      const expectedInstanceOperator = {
        domain: 'example.com',
        operatorName: 'Jane Doe',
        operatorStreetAndNumber: 'Some Street 1',
        operatorZipcode: '54321',
        operatorCity: 'New City',
        operatorCountry: 'USA',
        operatorPhone: '+987654321',
        operatorMail: 'jane@example.com',
        operatorWebsite: 'new-website.com',
      };
      service.getMastodonHandle.and.returnValue(of({name: expectedHandle}));
      service.getInstanceOperator.and.returnValue(of(expectedInstanceOperator));

      const compiled = fixture.nativeElement as HTMLElement;
      fixture.detectChanges();
      expect(compiled.querySelector('.howto')?.innerHTML)
        .toContain('You want your toots to be shown here?&nbsp;Mention');
      expect(compiled.querySelector('.howto')?.innerHTML)
        .toContain('in your toot and use one of the hashtags.');
      expect(compiled.querySelector('.howto .handle')?.innerHTML)
        .toContain('@mastodon@example.com');
    });
  });

  describe('legal notice and GDPR', () => {
    it('should exist', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.legal')).toBeTruthy();
    });

    it('should contain the correct text', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.legal>span')?.innerHTML)
        .toContain('Legal Notice');
    });

    /**
     * A11Y-F-02 (WCAG 2.1.1 — Keyboard):
     * The legal notice trigger must be a <button> so keyboard users can
     * activate it with Enter/Space, and screen readers announce it correctly.
     */
    it('should render the legal notice trigger as a <button> element (A11Y-F-02)', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      const el = compiled.querySelector('.legal');
      expect(el).toBeTruthy();
      expect(el!.tagName.toLowerCase()).toBe('button');
    });

    /**
     * A11Y-F-02 — the legal button must have a non-empty aria-label
     * so icon-only or visually-identified buttons are announced correctly.
     */
    it('should have a non-empty aria-label on the legal button (A11Y-F-02)', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      const btn = compiled.querySelector('button.legal') as HTMLButtonElement;
      expect(btn).toBeTruthy();
      const label = btn.getAttribute('aria-label');
      expect(label).toBeTruthy();
      expect(label!.length).toBeGreaterThan(0);
    });
  });

  describe('copyright', () => {
    it('should exist', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.copyright')).toBeTruthy();
    });

    it('should have the correct copyright text', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.copyright')?.innerHTML)
        .toContain('Glacier is Open Source: ')
    });

    it('should have the correct url text', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.copyright > a')?.innerHTML)
        .toBe('https://github.com/seism0saurus/glacier')
    });

    it('should have the correct repository url', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.copyright > a')?.getAttribute('href'))
        .toBe('https://github.com/seism0saurus/glacier')
    });

    it('should open the repository url in a new tab with data protection', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.copyright > a')?.getAttribute('target'))
        .toBe('_blank')
      expect(compiled.querySelector('.copyright > a')?.getAttribute('rel'))
        .toBe('noopener noreferrer')
    });
  });

  // -------------------------------------------------------------------------
  // i18n catalog completeness — A11Y-F-02 new keys
  // -------------------------------------------------------------------------
  describe('i18n catalog completeness (messages.en.json)', () => {
    const REQUIRED_CATALOG_KEYS = [
      'footer.legal.button.aria',   // A11Y-F-02: Legal button aria-label
      'footer.legal.button.label',  // A11Y-F-02: Legal button visible text
    ] as const;

    for (const key of REQUIRED_CATALOG_KEYS) {
      it(`messages.en.json must contain key '${key}'`, async () => {
        const response = await fetch('/assets/i18n/messages.en.json');
        expect(response.ok)
          .withContext('messages.en.json must be fetchable by the Karma test runner')
          .toBeTrue();
        const catalog: Record<string, string> = await response.json();
        expect(catalog[key])
          .withContext(`messages.en.json is missing key '${key}'.`)
          .toBeDefined();
        expect(typeof catalog[key]).toBe('string');
        expect(catalog[key].length).toBeGreaterThan(0);
      });
    }
  });

  describe('openLegal', () => {
    it('should open the GDPR dialog with the correct configuration', () => {
      const instanceOperatorMock = {
        domain: 'example.com',
        operatorName: 'Jon Doe',
        operatorStreetAndNumber: 'somewhere 1',
        operatorZipcode: '12345',
        operatorCity: 'somecity',
        operatorCountry: 'Germany',
        operatorPhone: '+123456789',
        operatorMail: 'mail@example.com',
        operatorWebsite: 'example.com'
      };
      component['instanceOperator'] = instanceOperatorMock;

      component.openLegal();

      expect(dialog.open).toHaveBeenCalledWith(GdprComponent, {
        width: '800px',
        data: instanceOperatorMock,
        panelClass: 'glacier-modalbox'
      });
    });
  });
});
