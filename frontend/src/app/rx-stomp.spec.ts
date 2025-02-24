import {TestBed} from '@angular/core/testing';
import {RxStompService} from './rx-stomp.service';
import {RxStomp} from '@stomp/rx-stomp';

describe('RxStompService', () => {
    let service: RxStompService;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [RxStompService],
        });
        service = TestBed.inject(RxStompService);
    });

    it('should be created', () => {
        expect(service).toBeTruthy();
    });

    it('should be an instance of RxStomp', () => {
        expect(service instanceof RxStomp).toBe(true);
    });
});
