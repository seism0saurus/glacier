import {
  animate,
  style,
  transition,
  trigger,
} from '@angular/animations';
import {Component, ElementRef, HostListener, OnDestroy, OnInit} from '@angular/core';
import {SubscriptionService} from "../subscription.service";
import {WallMessage} from "../model/wall-message";
import {Subscription} from "rxjs";
import {WallAnnouncerService} from "../services/wall-announcer.service";

/**
 * Animation trigger for toot removal (ADR-5 UX spec).
 *
 * When a toot leaves the DOM (`:leave` transition), it fades from full
 * opacity to transparent over 150 ms with an ease-out curve.
 *
 * FIX-1: The prefers-reduced-motion preference is honoured at the engine
 * level via AppModule's conditional AnimationDriver override (NoopAnimationDriver),
 * not via CSS. CSS @media (prefers-reduced-motion) rules only suppress CSS-declared
 * animations, not WAAPI-driven Angular animation triggers.
 */
export const pruneLeaveAnimation = trigger('pruneLeave', [
  transition(':leave', [
    animate('150ms ease-out', style({ opacity: 0 })),
  ]),
]);

/**
 * WallComponent is responsible for rendering a dynamic grid layout for displaying messages ("toots").
 * The layout adjusts based on the window size and organises messages efficiently into columns.
 * It subscribes to a service to receive new messages and handles clean-up when the component is destroyed.
 *
 * Phase 2 additions (prune-on-removal, ADR-5):
 *   - Injects WallAnnouncerService and subscribes to announcements$ for ARIA live region text.
 *   - Exposes announceText for the live region template binding (role="status" aria-live="polite").
 *   - Wires i18n messages to WallAnnouncerService so it can resolve plural strings at flush time.
 *   - Applies @pruneLeave animation on each article (150 ms opacity fade-out).
 *
 * FIX-2: The live region is now driven exclusively by Angular change detection via
 * {{ announceText }} data binding. WallAnnouncerService no longer writes textContent
 * directly — it emits on announcements$ instead. This eliminates the dual-write race
 * where Angular's change detection would reset textContent to '' (the initial value of
 * announceText), silencing screen-reader announcements.
 */
@Component({
    selector: 'app-wall',
    templateUrl: './wall.component.html',
    styleUrls: ['./wall.component.css'],
    standalone: false,
    animations: [pruneLeaveAnimation],
})
export class WallComponent implements OnInit, OnDestroy {

  toots: WallMessage[] = [];
  // @ts-ignore
  columns: number;
  // @ts-ignore
  rowHeight: number;
  private serviceSubscription: Subscription | null = null;

  /**
   * Subscription to WallAnnouncerService.announcements$ — tracks the live
   * region subscription so it can be cleaned up on destroy.
   */
  private announcerSubscription: Subscription | null = null;

  /**
   * Text content of the ARIA live region (role="status" aria-live="polite").
   *
   * Updated by the announcements$ subscription from WallAnnouncerService after
   * each 250 ms debounce window (ADR-5). The template binds this via
   * {{ announceText }}, making Angular change detection the sole writer to the
   * live region DOM node — preventing any dual-write race with direct DOM mutation.
   */
  announceText: string = '';

  constructor(
    private subscriptionService: SubscriptionService,
    public el: ElementRef,
    private wallAnnouncerService: WallAnnouncerService,
  ) {
  }

  @HostListener('window:resize', ['$event'])
  onResize() {
    this.rowHeight = this.el.nativeElement.offsetHeight - 40;
    this.columns = Math.floor(this.el.nativeElement.offsetWidth / 408)
  }

  ngOnInit() {
    this.rowHeight = this.el.nativeElement.offsetHeight - 40;
    this.columns = Math.floor(this.el.nativeElement.offsetWidth / 408)

    this.serviceSubscription = this.subscriptionService.getCreatedEvents().subscribe((messages: WallMessage[]) => {
      this.toots = [...messages];
    });

    // FIX-2: Subscribe to announcements$ from WallAnnouncerService.
    // The service emits coalesced announcement strings (after a 250 ms debounce).
    // Assigning here to announceText keeps Angular change detection as the sole
    // writer to the live region DOM node, eliminating the dual-write race that
    // occurred when the service wrote textContent directly.
    this.announcerSubscription = this.wallAnnouncerService.announcements$.subscribe(text => {
      this.announceText = text;
    });

    // Provide i18n-resolved strings for the announcer to use at flush time.
    // These are sourced from the Angular $localize runtime catalog so they
    // reflect the user's locale correctly.
    this.wallAnnouncerService.setMessages(
      $localize`:wall.prune.announce.single@@wall.prune.announce.single:{count, plural, =1 {Ein Beitrag entfernt.} other {# Beiträge entfernt.}}`,
      $localize`:wall.prune.announce.cancelAll@@wall.prune.announce.cancelAll:Alle Abonnements beendet. Pinnwand ist leer.`,
    );
  }

  ngOnDestroy() {
    console.log('Terminating subscriptions and storing messages for next session');
    this.subscriptionService.terminateAllSubscriptions();
    if (this.serviceSubscription) {
      this.serviceSubscription.unsubscribe();
    }
    // Unsubscribe from the announcer to prevent memory leaks.
    if (this.announcerSubscription) {
      this.announcerSubscription.unsubscribe();
    }
  }

  trackToot(index: number, toot: WallMessage) {
    if (toot){
      if (toot.editedAt) {
        return toot.id + toot.editedAt;
      }
      return toot.id;
    }
    return undefined;
  }

  getTootsForColumn(column: number): WallMessage[] {
    let reversedToots = this.toots.slice().reverse();
    return reversedToots.filter((e, i) => {
      return i % this.columns === column;
    });
  }
}
