import {Injectable, OnDestroy, OnInit} from '@angular/core';
import {Subscription, Observable, of} from "rxjs";
import {RxStompService} from "./rx-stomp.service";
import {Message} from "@stomp/stompjs";
import {SubscriptionAckMessage} from "./message-types/subscription-ack-message";
import {TerminationAckMessage} from "./message-types/termination-ack-message";
import {StatusCreatedMessage} from "./message-types/status-created-message";
import {StatusUpdatedMessage} from "./message-types/status-updated-message";
import {StatusDeletedMessage} from "./message-types/status-deleted-message";

@Injectable({
  providedIn: 'root'
})
export class SubscriptionService{

  // @ts-ignore
  private topicSubscription: Subscription;
  private subscriptionId: string = "";

  receivedMessages: string[] = [];
  subscriptions: { [key: string]: Subscription } = {};
  constructor(private rxStompService: RxStompService) {
    this.topicSubscription = this.rxStompService
      .watch('/user/topic/subscriptions')
      .subscribe((message: Message) => {
        console.log('Subscription topic', message.body);
        const data: SubscriptionAckMessage = JSON.parse(message.body);
        this.handleSubscriptionAckMessage(data);
      });

    this.topicSubscription = this.rxStompService
      .watch('/user/topic/terminations')
      .subscribe((message: Message) => {
        console.log('Subscription topic', message.body);
        const data: TerminationAckMessage = JSON.parse(message.body);
        this.handleTerminationAckMessage(data);
      });
  }

  getCreatedEvents(): Observable<string[]> {
    console.log('SubscriptionService:','New Observable created');
    const messageObersavble = of(this.receivedMessages);
    return messageObersavble;
  }

  private handleSubscriptionAckMessage(data: SubscriptionAckMessage) {
    if (data.subscribed) {
      console.log('Adding subscriptions for creation, modification and deletion.');
      this.subscriptionId = data.subscriptionId;
      this.subscriptions[data.subscriptionId + '-creation'] = this.subscribeToStatusCreatedMessages(data.subscriptionId);
      this.subscriptions[data.subscriptionId + '-modification'] = this.subscribeToStatusUpdatedMessages(data.subscriptionId);
      this.subscriptions[data.subscriptionId + '-deletion'] = this.subscribeToStatusDeletedMessages(data.subscriptionId);
    } else {
      console.error('Could not subscribe to topic', data.hashtag);
    }
  }

  private handleTerminationAckMessage(data: TerminationAckMessage) {
    if (data.terminated) {
      console.log('Terminating subscriptions for creation, modification and deletion.');
      this.subscriptions[data.subscriptionId + '-creation'].unsubscribe();
      this.subscriptions[data.subscriptionId + '-modification'].unsubscribe();
      this.subscriptions[data.subscriptionId + '-deletion'].unsubscribe();
      delete this.subscriptions[data.subscriptionId + '-creation'];
      delete this.subscriptions[data.subscriptionId + '-modification'];
      delete this.subscriptions[data.subscriptionId + '-deletion'];
    } else {
      console.error('Could not terminate subscription', data.subscriptionId);
    }
  }

  unsubscribe() {
    // Tell the backend, that you quit
    const message = {subscriptionId: this.subscriptionId};
    this.rxStompService.publish({ destination: '/glacier/termination', body: JSON.stringify(message) });

    // unsubscribe your main subscription
    this.topicSubscription.unsubscribe();

    // Unsubscribe each hashtag subscription
    Object.entries(this.subscriptions).forEach(
      ([key, value]) => {
        value.unsubscribe();
        delete this.subscriptions[key];
      }
    );
  }

  subscribeToStatusCreatedMessages(id: string) {
    const dest = '/topic/hashtags/'+id+'/creation';
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusCreatedMessage received:', message.body);
        const data: StatusCreatedMessage = JSON.parse(message.body);
        this.receivedMessages.push(data.url + '/embed');
      });
  }

  subscribeToStatusUpdatedMessages(id: string) {
    const dest = '/topic/hashtags/'+id+'/modification';
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusUpdatedMessage received:', message.body);
        const data: StatusUpdatedMessage = JSON.parse(message.body);
        //this.receivedMessages.push(data.id + ' updated: ' + data.text);
      });
  }

  subscribeToStatusDeletedMessages(id: string) {
    const dest = '/topic/hashtags/'+id+'/deletion';
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusDeletedMessage received:', message.body);
        const data: StatusDeletedMessage = JSON.parse(message.body);
        //this.receivedMessages.push(data.id + ' deleted.');
      });
  }

  subsribeHashtag(hashtag: string) {
    const message = {hashtag: hashtag};
    this.rxStompService.publish({ destination: '/glacier/subscription', body: JSON.stringify(message) });
  }
}
