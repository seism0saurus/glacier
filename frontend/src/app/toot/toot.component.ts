import {Component, Input} from '@angular/core';
import {SafeResourceUrl} from "@angular/platform-browser";

@Component({
  selector: 'app-toot',
  templateUrl: './toot.component.html',
  styleUrls: ['./toot.component.css'],
  standalone: false
})
export class TootComponent {

  @Input()
  url?: SafeResourceUrl;

  @Input()
  uuid: string = "";

  /**
   * Human-readable label used as the iframe title attribute (WCAG 4.1.2 / A11Y-F-01).
   *
   * When the wall passes a list of hashtags associated with this toot, the label is
   * built from them so screen readers can announce something meaningful instead of
   * just "untitled frame".  Falls back to the UUID when no label is supplied.
   */
  @Input()
  label: string = "";

  constructor() {}

  /**
   * Returns the accessible title for the iframe (WCAG 4.1.2 / A11Y-F-01).
   *
   * Uses the provided label when set, otherwise falls back to the status UUID
   * so the attribute is always non-empty and screen readers can identify the frame.
   */
  get iframeTitle(): string {
    return this.label || `Toot ${this.uuid}`;
  }

  configureIframe(element: HTMLIFrameElement): void {
    if (element) {
      // Register global listener that handles only messages for this iframe
      window.addEventListener('message', this.getHeightListener(element));

      // send message to global listener
      this.sendHeightToIframe(element);
    }
  }

  private sendHeightToIframe(element: HTMLIFrameElement) {
    if (element.contentWindow && element.src != '') {
      element.contentWindow.postMessage({
        type: 'setHeight',
        id: this.uuid,
      }, element.src);
    } else {
      console.debug('Could not access contentWindow of iframe ', this.uuid);
    }
  }

  private getHeightListener(element: HTMLIFrameElement) {
    return function (e: MessageEvent<any>) {
      const data = e.data || {};
      if (typeof data !== 'object' || data.type !== 'setHeight' || data.id !== element.id) {
        return;
      }
      if ('source' in e && element.contentWindow !== e.source) {
        return;
      }

      element.height = data.height;
    };
  }

  handleError(iframe: HTMLIFrameElement) {
    console.log('error at iframe', iframe);
    let frameDoc = iframe.contentDocument || iframe.contentWindow?.document;
    if (frameDoc?.documentElement) {
      frameDoc.removeChild(frameDoc.documentElement);
    } else {
      console.warn('Cannot handle error: documentElement is not available');
    }
  }
}
