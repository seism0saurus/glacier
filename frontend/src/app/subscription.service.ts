import {Injectable} from '@angular/core';
import {Observable, of, Subscription} from "rxjs";
import {RxStompService} from "./rx-stomp.service";
import {Message} from "@stomp/stompjs";
import {SubscriptionAckMessage} from "./message-types/subscription-ack-message";
import {TerminationAckMessage} from "./message-types/termination-ack-message";
import {StatusCreatedMessage} from "./message-types/status-created-message";
import {StatusUpdatedMessage} from "./message-types/status-updated-message";
import {StatusDeletedMessage} from "./message-types/status-deleted-message";
import {SafeMessage} from "./message-types/safe-message";

@Injectable({
  providedIn: 'root'
})
export class SubscriptionService {

  // @ts-ignore
  private subcriptionsSubscription: Subscription;
  private terminationsSubscription: Subscription;
  private receivedMessages: MessageQueue = new MessageQueue();
  private messageObersavble$: Observable<MessageQueue> = of(this.receivedMessages);
  private subscriptions: { [key: string]: Subscription } = {};
  private destinations: string[] = [];
  private hashtags: string[] = [];
  private principal: string | null = null;

  constructor(private rxStompService: RxStompService) {
    this.subcriptionsSubscription = this.rxStompService
      .watch('/user/topic/subscriptions')
      .subscribe((message: Message) => {
        console.log('Subscription topic', message.body);
        const data: SubscriptionAckMessage = JSON.parse(message.body);
        this.principal = data.principal;
        this.handleSubscriptionAckMessage(data);
      });

    this.terminationsSubscription = this.rxStompService
      .watch('/user/topic/terminations')
      .subscribe((message: Message) => {
        console.log('Subscription topic', message.body);
        const data: TerminationAckMessage = JSON.parse(message.body);
        this.handleTerminationAckMessage(data);
      });

    // Restore hashtags from previous session
    const storedHashtags: string[] = JSON.parse(localStorage.getItem('hashtags') || '[]');
    storedHashtags.forEach( tag => this.subscribeHashtag(tag));
  }

  getCreatedEvents(): Observable<MessageQueue> {
    console.log('SubscriptionService:', 'New Observable created');
    this.receivedMessages.restore();
    // this.receivedMessages.enqueue({id:"1", url: 'https://c.im/@GertrudeZane/112038131697458407/embed'});
    // this.receivedMessages.enqueue({id:"2", url: 'https://botsin.space/@hourlyhectoc/112036125133971064/embed'});
    // this.receivedMessages.enqueue({id:"3", url: 'https://mastodon.zergy.net/@Zergy/112038414711064112/embed'});
    // this.receivedMessages.enqueue({id:"4", url: 'https://mstdn.social/@DemocracySpot/112039038866407099/embed'});
    // this.receivedMessages.enqueue({id:"5", url: 'https://social.growyourown.services/@FediTips/112039041940272554/embed'});
    return this.messageObersavble$;
  }

  private handleSubscriptionAckMessage(data: SubscriptionAckMessage) {
    if (data.subscribed) {
      console.log('Adding subscriptions for creation, modification and deletion.');

      this.hashtags.push(data.hashtag);
      localStorage.setItem('hashtags', JSON.stringify(this.hashtags));

      const creationDestination = this.destination(data.principal, data.hashtag, 'creation');
      this.destinations.push(creationDestination);
      this.subscriptions[creationDestination] = this.subscribeToStatusCreatedMessages(creationDestination);

      const modificationDestination = this.destination(data.principal, data.hashtag, 'modification');
      this.destinations.push(modificationDestination);
      this.subscriptions[modificationDestination] = this.subscribeToStatusUpdatedMessages(modificationDestination);

      const deletionDestination = this.destination(data.principal, data.hashtag, 'modification');
      this.destinations.push(deletionDestination);
      this.subscriptions[deletionDestination] = this.subscribeToStatusDeletedMessages(deletionDestination);

    } else {
      console.error('Could not subscribe to topic', data.hashtag);
    }
  }

  private handleTerminationAckMessage(data: TerminationAckMessage) {
    if (data.terminated) {

      this.hashtags = this.hashtags.filter( tag => tag !== data.hashtag);
      localStorage.setItem('hashtags', JSON.stringify(this.hashtags));

      const creationDestination = this.destination(data.principal, data.hashtag, 'creation');
      this.terminateSubscriptionByDestination(creationDestination);

      const modificationDestination = this.destination(data.principal, data.hashtag, 'modification');
      this.terminateSubscriptionByDestination(modificationDestination);

      const deletionDestination = this.destination(data.principal, data.hashtag, 'modification');
      this.terminateSubscriptionByDestination(deletionDestination);

    } else {
      console.error('Could not terminate subscription for principal ' + data.principal + " and hashtag " + data.hashtag);
    }
  }

  private terminateSubscriptionByDestination(dest: string) {
    console.log('Terminating subscriptions for',dest);
    if (this.subscriptions[dest]){
      this.subscriptions[dest].unsubscribe();
      delete this.subscriptions[dest];
    } else {
      console.error('No subscription found with destination',dest);
    }
  }

  terminateAll() {

    // Tell the backend, that you terminate all subscriptions
    this.hashtags.forEach(tag =>
    {
      this.unsubscribeHashtag(tag);
    });
    // this.hashtags = [];
    // localStorage.setItem('hashtags', JSON.stringify(this.hashtags));

    // unsubscribe your main subscription
    this.subcriptionsSubscription.unsubscribe();
    this.terminationsSubscription.unsubscribe();

    // Unsubscribe each hashtag subscription
    Object.entries(this.subscriptions).forEach(
      ([key, value]) => {
        value.unsubscribe();
        delete this.subscriptions[key];
      }
    );
  }

  subscribeToStatusCreatedMessages(dest: string) {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusCreatedMessage received:', message.body);
        const data: StatusCreatedMessage = JSON.parse(message.body);
        this.receivedMessages.enqueue(data);
      });
  }

  subscribeToStatusUpdatedMessages(dest: string) {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusUpdatedMessage received:', message.body);
        const data: StatusUpdatedMessage = JSON.parse(message.body);
        this.receivedMessages.dequeue(data.id);
        this.receivedMessages.enqueue(data);
      });
  }

  subscribeToStatusDeletedMessages(dest: string) {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusDeletedMessage received:', message.body);
        const data: StatusDeletedMessage = JSON.parse(message.body);
        this.receivedMessages.dequeue(data.id);
      });
  }

  subscribeHashtag(hashtag: string) {
    const message = {hashtag: hashtag};
    this.rxStompService.publish({destination: '/glacier/subscription', body: JSON.stringify(message)});
  }

  unsubscribeHashtag(hashtag: string) {
    const message = {hashtag: hashtag};
    this.rxStompService.publish({destination: '/glacier/termination', body: JSON.stringify(message)});
  }

  private destination(principal: string, hashtag: string, type: string): string {
    return '/topic/hashtags/' + principal + '/' + hashtag + '/' + type;
  }
}

export class MessageQueue {
  private storage: SafeMessage[] = [];

  constructor(private capacity: number = Infinity) {
  }

  restore(): void{
    let storedMessages: string | null = localStorage.getItem('messageQueue');
    if (storedMessages !== null) {
      this.storage = JSON.parse(storedMessages) as SafeMessage[];
    }
  }

  enqueue(item: SafeMessage): void {
    if (this.size() === this.capacity) {
      throw Error("MessageQueue if full. Remove items by calling 'dequeue()' before adding new ones.");
    }
    if (this.storage.filter( message => message.id === item.id).length){
      console.log('Message with id', item.id, 'is already known. Ignore new one');
      return;
    }
    this.storage.push(item);
    localStorage.setItem('messageQueue', JSON.stringify(this.storage));
  }

  dequeue(id: string): SafeMessage | undefined {
    var messageToRemove;
    this.storage.forEach(scm => {
      if (scm.id === id) {
        messageToRemove = scm;
      }
    })
    if (messageToRemove) {
      const index = this.storage.indexOf(messageToRemove);
      if (index > -1) {
        delete this.storage[index];
      }
    }
    localStorage.setItem('messageQueue', JSON.stringify(this.storage));
    return messageToRemove;
  }

  size(): number {
    return this.storage.length;
  }

  toArray(): SafeMessage[] {
    return this.storage;
  }
}
