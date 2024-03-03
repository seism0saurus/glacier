import {Component, OnDestroy, OnInit} from '@angular/core';
import {SubscriptionService} from "../subscription.service";
import {DomSanitizer} from "@angular/platform-browser";

@Component({
  selector: 'app-messages',
  templateUrl: './messages.component.html',
  styleUrls: ['./messages.component.css'],
})
export class MessagesComponent implements OnInit, OnDestroy{

  messages: string[] = [];

  constructor(private subscriptionService: SubscriptionService, public sanitizer: DomSanitizer) {}

  ngOnInit() {
    this.subscriptionService.getCreatedEvents().subscribe((messages: string[]) => {
      console.log('MessageComponent:', 'Got new messages');
      this.messages = messages;
    });
  }

  ngOnDestroy() {
    this.subscriptionService.unsubscribe();
  }

  sanitize(url: string){
    return this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
