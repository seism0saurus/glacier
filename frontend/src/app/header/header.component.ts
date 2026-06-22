import {Component, OnDestroy, OnInit} from '@angular/core';
import {AnimationService} from "../animation.service";
import {Subscription} from "rxjs";
import {MatDialog} from "@angular/material/dialog";
import {MatIconRegistry} from "@angular/material/icon";
import {DomSanitizer} from "@angular/platform-browser";
import {ShareDialogComponent} from "../share-dialog/share-dialog.component";
import {registerGlacierSvgIcons} from "../icons/glacier-svg-icons";

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
 * - MatDialog: Opens the ShareDialogComponent (P1-17).
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

  constructor(
    private animationService: AnimationService,
    private dialog: MatDialog,
    iconRegistry: MatIconRegistry,
    sanitizer: DomSanitizer,
  ) {
    // Local SVG icon — the Material Icons webfont is intentionally not shipped.
    registerGlacierSvgIcons(iconRegistry, sanitizer, ['share_icon']);
  }

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

  /**
   * Opens the ShareDialogComponent as a Material dialog (P1-17).
   *
   * The dialog is standalone and self-contained; it handles its own CSRF,
   * link creation, and revoke flows via ShareLinkService.  Focus is
   * automatically managed by MatDialog (moves to dialog title on open,
   * returns to trigger element on close).
   */
  openShareDialog(): void {
    this.dialog.open(ShareDialogComponent);
  }
}
