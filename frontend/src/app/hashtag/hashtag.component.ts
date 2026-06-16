import {Component, ElementRef, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {COMMA, ENTER, SEMICOLON} from '@angular/cdk/keycodes';
import {SubscriptionService} from "../subscription.service";
import {SubscriptionPersistence} from "../subscription-persistence.service";
import {MatChipEditedEvent, MatChipInputEvent} from "@angular/material/chips";
import {MatIconRegistry} from "@angular/material/icon";
import {DomSanitizer} from "@angular/platform-browser";
import {MatSnackBar} from "@angular/material/snack-bar";
import {RxStompService} from "../rx-stomp.service";
import {Message} from "@stomp/stompjs";
import {SubscriptionAckMessage} from "../message-types/subscription-ack-message";
import {Subscription} from "rxjs";
import {normalizeHashtag} from "../util/hashtag";


/**
 * The `HashtagComponent` manages a list of hashtags for a user interface.
 * It supports adding, editing, removing, and clearing hashtags while ensuring proper sanitization of input.
 * This component also interacts with the `SubscriptionService` to manage subscriptions for each hashtag.
 *
 * Component Decorator:
 * - Selector: `app-hashtag`
 * - Template URL: `./hashtag.component.html`
 * - Style URL: `./hashtag.component.css`
 *
 * Key Functionalities:
 * - Add new hashtags based on sanitized user input.
 * - Remove existing hashtags from the list.
 * - Edit hashtags and update subscriptions accordingly.
 * - Clear all hashtags from the list and unsubscribe them.
 * - Clear all associated content (such as "toots") using `SubscriptionService`.
 *
 * Dependencies:
 * - `SubscriptionService`: Manages subscriptions for hashtags and clears associated content.
 * - `MatIconRegistry` and `DomSanitizer`: Used to register and secure SVG icons used in the component.
 *
 * DOM Interactions:
 * - Uses `@ViewChild` to access the hashtag input element for direct manipulation.
 *
 * Sanitization:
 * - Ensures hashtags are trimmed, lowercased, and stripped of the `#` character before usage.
 */
@Component({
  selector: 'app-hashtag',
  templateUrl: './hashtag.component.html',
  styleUrls: ['./hashtag.component.css'],
  standalone: false,
})
export class HashtagComponent implements OnInit, OnDestroy {

  addOnBlur = true;
  separatorKeysCodes: number[] = [ENTER, COMMA, SEMICOLON];
  // SR-SPLIT-01b: use SubscriptionPersistence.loadHashtags() instead of reading
  // localStorage directly.  Direct access bypassed validateHashtagsList (CWE-20,
  // OWASP A03:2021, FIND-P3-SEC-4).  Initialised from the persistence service
  // in the constructor once Angular DI has resolved SubscriptionPersistence.
  hashtags: string[] = [];
  hashtag: string = "Enter a hashtag";

  // @ts-ignore
  @ViewChild('hashtagInput') hashtagInput: ElementRef<HTMLInputElement>;

  private _ackSubscription?: Subscription;
  private _settlingSubscription?: Subscription;

  /**
   * i18n labels for the settling spinner chip state (UX spec, chip.settling.*).
   *
   * These are resolved via $localize at component instantiation time so
   * they reflect the correct locale from the runtime catalog.
   */
  readonly settlingAriaLabel: string = $localize`:chip.settling.aria@@chip.settling.aria:Synchronisiert, kurz warten`;
  readonly settlingTooltip: string = $localize`:chip.settling.visual.tooltip@@chip.settling.visual.tooltip:Synchronisiert …`;

  /**
   * Set of currently settling hashtags (normalised).
   *
   * Populated by SubscriptionService.settlingHashtags$ and used by the
   * template to show/hide the MatProgressSpinner in place of the chip label.
   */
  settlingHashtags: Set<string> = new Set();

  constructor(
    private subscriptionService: SubscriptionService,
    private persistence: SubscriptionPersistence,
    private matIconRegistry: MatIconRegistry,
    private domSanitizer: DomSanitizer,
    private snackBar: MatSnackBar,
    private rxStompService: RxStompService,
  ) {
    // SR-SPLIT-01b: loadHashtags() applies validateHashtagsList, provides
    // try/catch around JSON.parse, and emits console.warn on parse failure —
    // none of which the prior direct localStorage call provided (CWE-20, AC-19).
    this.hashtags = this.persistence.loadHashtags();

    this.matIconRegistry.addSvgIcon(
      `cancel_icon`,
      this.domSanitizer.bypassSecurityTrustResourceUrl("../assets/cancel.svg")
    );
    this.matIconRegistry.addSvgIcon(
      `mastodon_icon`,
      this.domSanitizer.bypassSecurityTrustResourceUrl("../assets/mastodon.svg")
    );
    this.matIconRegistry.addSvgIcon(
      `trash_icon`,
      this.domSanitizer.bypassSecurityTrustResourceUrl("../assets/trash.svg")
    );
  }

  ngOnInit(): void {
    // Subscribe to STOMP ack messages to catch CAP_EXCEEDED rejections (D-12, D-17).
    // The SubscriptionService already subscribes to /user/topic/subscriptions, but
    // HashtagComponent needs its own subscription here to roll back the optimistic
    // chip addition when the server rejects.
    this._ackSubscription = this.rxStompService
      .watch('/user/topic/subscriptions')
      .subscribe((message: Message) => {
        const data: SubscriptionAckMessage = JSON.parse(message.body);
        if (!data.subscribed && data.rejection?.code === 'CAP_EXCEEDED') {
          this._rollbackCapExceeded(data.hashtag, data.rejection.details);
        }
      });

    // Subscribe to the settling hashtags set so the chip template can react
    // and show the MatProgressSpinner during the recentlyTerminated guard window.
    this._settlingSubscription = this.subscriptionService.settlingHashtags$.subscribe(
      (settling: Set<string>) => {
        this.settlingHashtags = settling;
      }
    );
  }

  ngOnDestroy(): void {
    this._ackSubscription?.unsubscribe();
    this._settlingSubscription?.unsubscribe();
  }

  /**
   * SHELL-04: Returns the localized chip edit description for screen readers.
   * Uses $localize interpolation to allow runtime catalog substitution.
   * @param tag - the hashtag name
   */
  chipEditDescription(tag: string): string {
    return $localize`:@@hashtag.chip.edit.description:Enter drücken, um Hashtag ${tag} zu bearbeiten`;
  }

  /**
   * SHELL-04: Returns the localized chip remove aria-label for screen readers.
   * @param tag - the hashtag name
   */
  chipRemoveLabel(tag: string): string {
    return $localize`:@@hashtag.chip.remove.label:Hashtag ${tag} entfernen`;
  }

  /**
   * Returns true if the given hashtag is currently in the settling state
   * (recentlyTerminated guard is active).
   *
   * Used by the template to conditionally show the MatProgressSpinner on the chip.
   *
   * @param tag - Raw hashtag string (as stored in this.hashtags[]).
   */
  isSettling(tag: string): boolean {
    return this.settlingHashtags.has(normalizeHashtag(tag));
  }

  /**
   * Rolls back the optimistic chip addition for a hashtag that the server
   * rejected with CAP_EXCEEDED, and shows a localised snackbar (D-12, D-17).
   */
  private _rollbackCapExceeded(hashtag: string, details?: Record<string, unknown>): void {
    // Remove optimistic chip from DOM
    const index = this.hashtags.indexOf(hashtag);
    if (index >= 0) {
      this.hashtags.splice(index, 1);
    }

    const limit = details?.['limit'] as number | undefined;
    const message = limit !== undefined
      ? $localize`:cap.reached.snackbar@@cap.reached.snackbar:Maximal ${limit} Hashtags pro Sitzung. '${hashtag}' wurde nicht hinzugefügt.`
      : $localize`:cap.reached.snackbar.no.limit@@cap.reached.snackbar.no.limit:Hashtag-Limit erreicht. '${hashtag}' wurde nicht hinzugefügt.`;

    this.snackBar.open(message, $localize`:gap.snackbar.dismiss@@gap.snackbar.dismiss:Schließen`, {
      duration: 8_000,
    });
  }

  add(event: MatChipInputEvent): void {
    const sanitizedTag = this.sanitize(event.value || '');

    // Add the hashtag if it isn't already in the list
    if (sanitizedTag && !this.hashtags.includes(sanitizedTag)) {
      this.hashtags.push(sanitizedTag);
      this.subscriptionService.subscribeHashtag(sanitizedTag);
    }

    // Clear the input sanitizedTag
    event.chipInput!.clear();
  }

  remove(tag: string): void {
    const index = this.hashtags.indexOf(tag);

    if (index >= 0) {
      this.hashtags.splice(index, 1);
      this.subscriptionService.unsubscribeHashtag(tag);
    }
  }

  edit(tag: string, event: MatChipEditedEvent) {
    const sanitizedTag = this.sanitize(event.value);

    //Do nothing, if the tag hasn't changed. Otherwise, change it
    if (tag !== sanitizedTag){
      // Remove tag and unsubscribe, if it is empty
      if (!sanitizedTag) {
        this.subscriptionService.unsubscribeHashtag(tag);
        this.remove(tag);
        return;
      }

      // Remove the tag if another tag with the same hashtag exists
      if (this.hashtags.includes(sanitizedTag)) {
        this.remove(tag);
        return;
      }

      // Edit existing tag
      const index = this.hashtags.indexOf(tag);
      if (index >= 0) {
        this.hashtags[index] = sanitizedTag;
        this.subscriptionService.unsubscribeHashtag(tag);
        this.subscriptionService.subscribeHashtag(sanitizedTag);
      }
    }
  }

  clearTags(): void {
    this.hashtags.forEach( tag => this.subscriptionService.unsubscribeHashtag(tag))
    this.hashtags = [];
  }

  clearToots(): void {
    this.subscriptionService.clearAllToots();
  }

  private sanitize(tag: string): string {
    if (tag){
      const trim = tag.trim();
      const lowercase = trim.toLowerCase();
      return lowercase.charAt(0) === '#' ? lowercase.slice(1) : lowercase;
    }
    return tag;
  }
}
