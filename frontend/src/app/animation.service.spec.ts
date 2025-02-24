import {TestBed} from '@angular/core/testing';

import {AnimationService} from './animation.service';

describe('AnimationService', () => {
  let service: AnimationService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AnimationService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

    it('should initially return true from getHeaderExtended', (done) => {
        service.getHeaderExtended().subscribe((value) => {
            expect(value).toBeTrue();
            done();
        });
    });

    it('should return false from getHeaderExtended after setSubscribed is called', (done) => {
        service.setSubscribed();
        service.getHeaderExtended().subscribe((value) => {
            expect(value).toBeFalse();
            done();
        });
    });
});
