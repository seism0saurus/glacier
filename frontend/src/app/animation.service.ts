import {Injectable} from '@angular/core';
import {BehaviorSubject, Observable} from "rxjs";

/**
 * A service to manage header animation states and provide observable state updates.
 */
@Injectable({
  providedIn: 'root'
})
export class AnimationService {

  private headerExtended: boolean = true;
  private readonly headerExtended$: BehaviorSubject<boolean>;

  constructor() {
    this.headerExtended$ = new BehaviorSubject<boolean>(this.headerExtended);
  }

  getHeaderExtended(): Observable<boolean> {
    return this.headerExtended$;
  }

  setSubscribed() {
    this.headerExtended = false;
    this.headerExtended$.next(this.headerExtended);
  }
}
