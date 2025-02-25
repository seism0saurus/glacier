import { TestBed } from '@angular/core/testing';

import { FooterService } from './footer.service';
import { HttpTestingController, provideHttpClientTesting } from "@angular/common/http/testing";
import {Handle} from "./handle";
import {InstanceOperator} from "./instance-operator";
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

describe('FooterService', () => {
  let service: FooterService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [], providers: [provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()] });
    service = TestBed.inject(FooterService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should do correct http request on getMastodonHandle subscription', () => {
    let handle: Handle = {name: ''};
    service.getMastodonHandle()
      .subscribe(data => handle = data);
    const req = httpMock.expectOne('/rest/mastodon-handle');
    req.flush({name: 'mastodon@example.com'});
    expect(req.request.method).toBe('GET');
    httpMock.verify();
    expect(handle).toEqual({name: 'mastodon@example.com'});
  });

  it('should do correct http request on getInstanceOperator subscription', () => {
    let operator: InstanceOperator = {
      domain: '',
      operatorName: '',
      operatorStreetAndNumber: '',
      operatorZipcode: '',
      operatorCity: '',
      operatorCountry: '',
      operatorPhone: '',
      operatorMail: '',
      operatorWebsite: ''
    };

    service.getInstanceOperator().subscribe(data => operator = data);

    const req = httpMock.expectOne('/rest/operator');
    req.flush({
      domain: 'example.com',
      operatorName: 'OperatorName',
      operatorStreetAndNumber: 'Street 123',
      operatorZipcode: '12345',
      operatorCity: 'City',
      operatorCountry: 'Country',
      operatorPhone: '+123456789',
      operatorMail: 'example@mail.com',
      operatorWebsite: 'https://example.com'
    });

    // Überprüfe die HTTP-Methode
    expect(req.request.method).toBe('GET');
    httpMock.verify();

    // Überprüfe, ob die Daten korrekt gemappt wurden
    expect(operator).toEqual({
      domain: 'example.com',
      operatorName: 'OperatorName',
      operatorStreetAndNumber: 'Street 123',
      operatorZipcode: '12345',
      operatorCity: 'City',
      operatorCountry: 'Country',
      operatorPhone: '+123456789',
      operatorMail: 'example@mail.com',
      operatorWebsite: 'https://example.com'
    });
  });

});
