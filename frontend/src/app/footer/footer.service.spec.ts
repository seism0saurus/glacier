import { TestBed } from '@angular/core/testing';

import { FooterService } from './footer.service';
import {HttpClientTestingModule, HttpTestingController} from "@angular/common/http/testing";
import {Handle} from "./handle";

describe('FooterServiceService', () => {
  let service: FooterService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({imports: [HttpClientTestingModule]});
    service = TestBed.inject(FooterService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should do correct http request on subscription', () => {
    let handle: Handle = { name: ''};
    service.getMastodonHandle()
      .subscribe(data => handle = data);
    const req = httpMock.expectOne('/rest/mastodon-handle');
    req.flush({name:'mastodon@example.com'});
    expect(req.request.method).toBe('GET');
    httpMock.verify();
    expect(handle).toEqual({name:'mastodon@example.com'});
  });
});
