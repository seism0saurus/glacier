import {Component, ElementRef, ViewChild} from '@angular/core';
import {COMMA, ENTER, SEMICOLON} from '@angular/cdk/keycodes';
import {SubscriptionService} from "../subscription.service";
import {MatChipEditedEvent, MatChipInputEvent} from "@angular/material/chips";
import {MatIconRegistry} from "@angular/material/icon";
import {DomSanitizer} from "@angular/platform-browser";


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
    standalone: false
})
export class HashtagComponent {

  addOnBlur = true;
  separatorKeysCodes: number[] = [ENTER, COMMA, SEMICOLON];
  hashtags: string[] = JSON.parse(localStorage.getItem('hashtags') || '[]');
  hashtag: string = "Enter a hashtag";

  // @ts-ignore
  @ViewChild('hashtagInput') hashtagInput: ElementRef<HTMLInputElement>;

  constructor(private subscriptionService: SubscriptionService,
              private matIconRegistry: MatIconRegistry,
              private domSanitizer: DomSanitizer
  ) {
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
