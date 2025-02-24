import {Component, ElementRef, HostListener, OnDestroy, OnInit} from '@angular/core';
import {MessageQueue, SubscriptionService} from "../subscription.service";
import {SafeMessage} from "../message-types/safe-message";
import {Subscription} from "rxjs";

/**
 * WallComponent is responsible for rendering a dynamic grid layout for displaying messages ("toots").
 * The layout adjusts based on the window size and organizes messages efficiently into columns.
 * It subscribes to a service to receive new messages and handles clean-up when the component is destroyed.
 */
@Component({
  selector: 'app-wall',
  templateUrl: './wall.component.html',
  styleUrls: ['./wall.component.css'],
})
export class WallComponent implements OnInit, OnDestroy {

  toots: SafeMessage[] = [];
  // @ts-ignore
  columns: number;
  // @ts-ignore
  rowHeight: number;
  private serviceSubscription: Subscription | null = null;

  constructor(private subscriptionService: SubscriptionService, private el: ElementRef) {
  }

  @HostListener('window:resize', ['$event'])
  onResize() {
    this.rowHeight = this.el.nativeElement.offsetHeight - 40;
    this.columns = Math.floor(this.el.nativeElement.offsetWidth / 408)
  }

  ngOnInit() {
    this.rowHeight = this.el.nativeElement.offsetHeight - 40;
    this.columns = Math.floor(this.el.nativeElement.offsetWidth / 408)

    this.serviceSubscription = this.subscriptionService.getCreatedEvents().subscribe((messages: MessageQueue) => {
      console.log('MessageComponent:', 'Got new messages');
      this.toots = messages.toArray();
    });

  }

  ngOnDestroy() {
    console.log('Terminating subscriptions and storing messages for next session');
    this.subscriptionService.terminateAllSubscriptions();
    if (this.serviceSubscription) {
      this.serviceSubscription.unsubscribe();
    }
  }

  trackToot(index: number, toot: SafeMessage) {
    return toot ? toot.id : undefined;
  }

  getTootsForColumn(column: number): SafeMessage[] {
    let reversedToots = this.toots.slice().reverse();
    return reversedToots.filter((e, i) => {
      return i % this.columns === column;
    });
  }
}
