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
        .toContain('Legal Notice &amp; GDPR');
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
