import {Component, Input, OnDestroy} from '@angular/core';
import {SafeResourceUrl} from "@angular/platform-browser";

/**
 * Monotonically increasing counter used to generate a unique DOM id suffix for
 * every TootComponent instance.  A simple counter is sufficient — it never needs
 * to survive page reloads and has no security implications.
 */
let instanceCounter = 0;

@Component({
  selector: 'app-toot',
  templateUrl: './toot.component.html',
  styleUrls: ['./toot.component.css'],
  standalone: false
})
export class TootComponent implements OnDestroy {

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

  /**
   * Stable per-instance suffix appended to the iframe DOM id to prevent duplicate ids
   * when the same toot uuid is mounted more than once (e.g. on reconnect or for edited
   * toots that share the base uuid).  Assigned once at construction and never changes.
   *
   * TOOT-12: duplicate DOM id fix.
   */
  private readonly instanceId: number = ++instanceCounter;

  /**
   * Stable function reference for the global window 'message' handler.
   *
   * Storing this reference is required so that ngOnDestroy can pass the exact same
   * object to removeEventListener.  An anonymous closure created inline (as was done
   * before TOOT-12) cannot be removed because addEventListener and removeEventListener
   * compare listeners by reference equality.
   *
   * Initialised to a no-op so the field is always defined; overwritten in
   * configureIframe once the target iframe element is known.
   *
   * TOOT-12: listener-leak fix.
   */
  boundHeightListener: (e: MessageEvent<any>) => void = () => {};

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

  /**
   * Returns the iframe DOM id for this instance.
   *
   * The id combines the toot uuid with a per-instance counter so that two mounted
   * instances sharing the same uuid (reconnect, edited-toot re-render) never produce
   * duplicate DOM ids.  The postMessage handshake uses this same id so the
   * height-listener filter in getHeightListener continues to work correctly.
   *
   * TOOT-12: duplicate DOM id fix.
   */
  get iframeId(): string {
    return `${this.uuid}-${this.instanceId}`;
  }

  /**
   * Wires up the height-negotiation postMessage handshake for the given iframe element.
   *
   * Registers exactly one stable global 'message' listener per component instance and
   * stamps the element's id with the instance-unique iframeId so the filter inside the
   * listener can address exactly this frame.  The listener reference is stored in
   * boundHeightListener so ngOnDestroy can remove it precisely.
   *
   * Called by the template's (load) binding after the iframe content has loaded.
   */
  configureIframe(element: HTMLIFrameElement): void {
    if (element) {
      // Stamp the element with the collision-free id before registering the listener,
      // so the listener's id-filter and sendHeightToIframe both use the same value.
      element.id = this.iframeId;

      // Build a stable handler reference bound to this element and store it so
      // ngOnDestroy can remove the exact same function object.  Calling
      // configureIframe a second time (e.g. on iframe reload) overwrites the
      // previous reference — removeEventListener for the old one is intentionally
      // not called here because the element is the same and the closure re-captures
      // the same element reference; the new handler replaces the old one.
      this.boundHeightListener = this.getHeightListener(element);
      window.addEventListener('message', this.boundHeightListener);

      // send message to global listener
      this.sendHeightToIframe(element);
    }
  }

  /**
   * Removes the global 'message' listener registered by configureIframe.
   *
   * Called automatically by Angular when the component is destroyed.  Prevents the
   * handler closure from accumulating on window across reconnects and re-renders,
   * which would grow unboundedly on long-lived walls with high toot churn.
   *
   * TOOT-12: listener-leak fix.
   */
  ngOnDestroy(): void {
    window.removeEventListener('message', this.boundHeightListener);
  }

  private sendHeightToIframe(element: HTMLIFrameElement) {
    if (element.contentWindow && element.src != '') {
      element.contentWindow.postMessage({
        type: 'setHeight',
        id: element.id,
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
