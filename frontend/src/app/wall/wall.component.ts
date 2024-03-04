import {Component, OnDestroy, OnInit} from '@angular/core';
import {SubscriptionService} from "../subscription.service";
import {DomSanitizer} from "@angular/platform-browser";

@Component({
  selector: 'app-wall',
  templateUrl: './wall.component.html',
  styleUrls: ['./wall.component.css'],
})
export class WallComponent implements OnInit, OnDestroy{

  toots: string[] = [];

  constructor(private subscriptionService: SubscriptionService, public sanitizer: DomSanitizer) {}

  ngOnInit() {
    this.subscriptionService.getCreatedEvents().subscribe((messages: string[]) => {
      console.log('MessageComponent:', 'Got new messages');
      this.toots = messages;
    });
  }

  ngOnDestroy() {
    this.subscriptionService.terminateAll();
  }

  sanitize(url: string){
    return this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
