import {Component, Input} from '@angular/core';
import {SafeResourceUrl} from "@angular/platform-browser";
import {HttpClient} from "@angular/common/http";

@Component({
  selector: 'app-toot',
  templateUrl: './toot.component.html',
  styleUrls: ['./toot.component.css']
})
export class TootComponent {

  @Input()
  url: SafeResourceUrl = {};
  public uuid: string = "";

  constructor(private http: HttpClient) {
  }

  configureIframe(element: HTMLIFrameElement): void {
    this.uuid = self.crypto.randomUUID();

    if (element) {
      // Register global listener that handles only messages for this iframe
      window.addEventListener('message', this.getHeightListener(element));

      // send message to global listener
      this.sendHeightToIframe(element);

      if (this.cantBeLoaded()) {
        console.log("documentElement", element);
        element.style["display"] = "none";
      }
    }
  }

  private cantBeLoaded(): boolean {
    return false;
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
    // @ts-ignore
    frameDoc.removeChild(frameDoc.documentElement);
  }
}
