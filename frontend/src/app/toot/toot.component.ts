import {AfterViewInit, Component, ElementRef, Input, OnInit, ViewChild} from '@angular/core';
import {SafeResourceUrl} from "@angular/platform-browser";

@Component({
  selector: 'app-toot',
  templateUrl: './toot.component.html',
  styleUrls: ['./toot.component.css']
})
export class TootComponent{

  @Input()
  url: SafeResourceUrl = "";

  public uuid: string = "";

  configureIframe(element: HTMLIFrameElement): void {
    this.uuid = self.crypto.randomUUID();

    if (element){
      // Register global listener that handles only messages for this iframe
      window.addEventListener('message', this.getHeightListener(element));

      // send message to global listener
      this.sendHeightToIframe(element);
    }
  }

  private sendHeightToIframe(element: HTMLIFrameElement) {
    if (element.contentWindow) {
      element.contentWindow.postMessage({
        type: 'setHeight',
        id: this.uuid,
      }, '*');
    } else {
      console.error('Could not access contentWindow of iframe ', this.uuid);
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
}
