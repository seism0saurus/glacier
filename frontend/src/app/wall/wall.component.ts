import {
  animate,
  style,
  transition,
  trigger,
} from '@angular/animations';
import {Component, ElementRef, HostListener, OnDestroy, OnInit, signal, computed, WritableSignal} from '@angular/core';
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

  /**
   * Backing signal for the toot list. {@link columnToots} is a computed() that reads
   * {@link toots}; backing the field with a signal makes that computed track changes
   * so live emissions from getCreatedEvents re-render the grid without a resize/reload.
   * The array-typed get/set accessors keep the existing `this.toots = [...]` /
   * `this.toots.length` API (and unit tests) unchanged.
   */
  private readonly _toots: WritableSignal<WallMessage[]> = signal<WallMessage[]>([]);

  get toots(): WallMessage[] {
    return this._toots();
  }

  set toots(value: WallMessage[]) {
    this._toots.set(value);
  }
  // @ts-ignore
  rowHeight: number;
  private serviceSubscription: Subscription | null = null;

  /**
   * Signal tracking whether the wall has no toots to display (P2 D.3).
   *
   * Updated in the getCreatedEvents() subscription so that Angular's signal
   * graph can drive the empty-wall CTA block via @if(noToots()) in the template.
   * Kept as a writable signal (set from the subscription callback) because
   * SubscriptionService.getCreatedEvents() returns an Observable, not a signal —
   * a computed() wrapping a toSignal() would add unnecessary complexity given that
   * the toots array is already reactively maintained here.
   */
  protected readonly noToots = signal<boolean>(true);

  /**
   * Writable signal for the column count (P2 D.3 — signals optimisation).
   *
   * Switching columns from a plain property to a WritableSignal allows the
   * columnToots computed() below to reactively recompute whenever the window
   * is resized and the column count changes.  The initial value of 1 prevents
   * a zero-column render on first paint before onResize/ngOnInit fires.
   */
  protected readonly _columns: WritableSignal<number> = signal(1);

  /**
   * Exposes the column count for the template.  The getter/setter pair bridges
   * the signal to code that still writes `this.columns = ...` (onResize, ngOnInit,
   * and tests), so no caller change is required.
   */
  get columns(): number { return this._columns(); }
  set columns(value: number) { this._columns.set(value); }

  /**
   * Computed signal that pre-slices toots into per-column arrays (P2 D.3).
   *
   * Replaces the per-render call to getTootsForColumn() in the template @for loop.
   * Recomputes only when _columns signal changes (set in onResize / ngOnInit).
   * When the toot list changes, the subscription callback directly updates
   * this.toots and we trigger recomputation by calling _columns.set() indirectly
   * via the existing onResize path.  This is a pragmatic optimisation that reduces
   * re-computation frequency without requiring a full Observable-to-signal migration.
   *
   * The template switches from getTootsForColumn(i) to columnToots()[i].
   * getTootsForColumn() is kept for backward compatibility with existing unit tests.
   */
  protected readonly columnToots = computed(() => {
    const cols = this._columns();
    const reversed = [...this.toots].reverse();
    return Array.from({ length: cols }, (_, i) =>
      reversed.filter((_t, idx) => idx % cols === i)
    );
  });

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
    // Math.max(1, ...) ensures at least one column on narrow viewports (< 408 px).
    // Without this guard, columns would be 0 on mobile and the MatGridList would
    // render nothing, leaving the wall blank on phones (P1-18).
    this.columns = Math.max(1, Math.floor(this.el.nativeElement.offsetWidth / 408))
  }

  ngOnInit() {
    this.rowHeight = this.el.nativeElement.offsetHeight - 40;
    // Math.max(1, ...) ensures at least one column on narrow viewports (< 408 px).
    this.columns = Math.max(1, Math.floor(this.el.nativeElement.offsetWidth / 408))

    this.serviceSubscription = this.subscriptionService.getCreatedEvents().subscribe((messages: WallMessage[]) => {
      this.toots = [...messages];
      // Keep the noToots signal in sync with the live toot list so the
      // empty-wall CTA block appears / disappears reactively (P2 D.3).
      this.noToots.set(messages.length === 0);
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

  /**
   * TOOT-02: Returns a localized, meaningful iframe title for a toot.
   * Guards against empty hashtags array (would produce "Toot #undefined").
   * @param toot - the toot object
   */
  tootLabel(toot: { hashtags: string[] }): string {
    const hashtag = toot.hashtags?.[0];
    if (hashtag) {
      return $localize`:@@wall.toot.label:Toot #${hashtag}`;
    }
    return $localize`:@@wall.toot.label.no-hashtag:Toot`;
  }
}
