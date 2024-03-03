import { Injectable } from '@angular/core';
import {BehaviorSubject, Observable, of} from "rxjs";

@Injectable({
  providedIn: 'root'
})
export class AnimationService {

  private headerExtended: boolean = true;
  private readonly headerExtended$ : BehaviorSubject<boolean>;

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
