import {Component, OnDestroy, OnInit} from '@angular/core';
import {AnimationService} from "../animation.service";
import {Subscription} from "rxjs";

/**
 * Represents the header component of the application.
 *
 * Handles the rendering of the application header and manages
 * the state of its extended property based on external service
 * data from the AnimationService.
 *
 * Lifecycle hooks included in this class:
 * - ngOnInit: Initializes the subscription to the AnimationService observable.
 * - ngOnDestroy: Cleans up by unsubscribing from the observable.
 *
 * Dependencies:
 * - AnimationService: Provides an observable to track the extended state of the header.
 */
@Component({
    selector: 'app-header',
    templateUrl: './header.component.html',
    styleUrls: ['./header.component.css'],
    standalone: false
})
export class HeaderComponent implements OnInit, OnDestroy{

  public extended: boolean = true;
  private extendedSubscription?: Subscription;

  constructor(private animationService: AnimationService) {}

  ngOnInit() {
    this.extendedSubscription = this.animationService.getHeaderExtended()
      .subscribe({
        next: value  => {
          console.log('Observable emitted a value: ' + value);
          this.extended = value;
        },
        error: err => console.error('Observable emitted an error: ' + err),
        complete: () => console.log('Observable emitted the complete notification')
      });
  }

  ngOnDestroy(): void {
    if (this.extendedSubscription) {
      this.extendedSubscription.unsubscribe();
    }
  }
}
