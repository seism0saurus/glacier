import {Injectable} from '@angular/core';
import {Observable, Subscription, BehaviorSubject} from "rxjs";
import {RxStompService} from "./rx-stomp.service";
import {Message} from "@stomp/stompjs";
import {SubscriptionAckMessage} from "./message-types/subscription-ack-message";
import {TerminationAckMessage} from "./message-types/termination-ack-message";
import {StatusCreatedMessage} from "./message-types/status-created-message";
import {StatusUpdatedMessage} from "./message-types/status-updated-message";
import {StatusDeletedMessage} from "./message-types/status-deleted-message";
import {SafeMessage} from "./message-types/safe-message";

/**
 * Service for managing subscriptions to topics, handling received messages,
 * and subscribing/unsubscribing to specific hashtags. This service interacts
 * with an RxStompService to subscribe to message topics or publish subscription
 * and termination requests.
 */
@Injectable({
  providedIn: 'root'
})
export class SubscriptionService {

  private subscriptionsSubscription: Subscription;
  private terminationsSubscription: Subscription;
  private receivedMessages: MessageQueue = new MessageQueue();
  private messageSubject$: BehaviorSubject<SafeMessage[]> = new BehaviorSubject<SafeMessage[]>([]);
  public messageObservable$: Observable<SafeMessage[]> = this.messageSubject$.asObservable(); // Nur als Observable nach außen exponieren.
  private subscriptions: { [key: string]: Subscription } = {};
  private destinations: string[] = [];
  private hashtags: string[] = [];

  constructor(private rxStompService: RxStompService) {
    this.subscriptionsSubscription = this.rxStompService
      .watch('/user/topic/subscriptions')
      .subscribe((message: Message) => {
        console.log('Subscription topic', message.body);
        const data: SubscriptionAckMessage = JSON.parse(message.body);
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
    storedHashtags.forEach(tag => this.subscribeHashtag(tag));
    this.messageSubject$.next(this.receivedMessages.toArray())
  }

  /**
   * Retrieves an observable stream of created events from the message queue.
   * The method also restores previously received messages.
   *
   * @return {Observable<MessageQueue>} An observable emitting events from the message queue.
   */
  getCreatedEvents(): Observable<SafeMessage[]> {
    console.log('SubscriptionService:', 'New Observable created');
    this.receivedMessages.restore();
    return this.messageObservable$;
  }

  /**
   * Handles the subscription acknowledgment message and manages subscriptions
   * for creation, modification, and deletion events based on the provided data.
   *
   * @param {SubscriptionAckMessage} data - The subscription acknowledgment message,
   * including subscription status, principal, and hashtag information.
   * @return {void} This method does not return a value.
   */
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

      const deletionDestination = this.destination(data.principal, data.hashtag, 'deletion');
      this.destinations.push(deletionDestination);
      this.subscriptions[deletionDestination] = this.subscribeToStatusDeletedMessages(deletionDestination);

    } else {
      console.error('Could not subscribe to topic', data.hashtag);
    }
  }

  /**
   * Handles termination acknowledgment messages by removing the associated hashtag
   * and terminating related subscriptions for the specified principal.
   *
   * @param {TerminationAckMessage} data - The termination acknowledgment message containing
   * properties such as `terminated`, `principal`, and `hashtag`.
   * @return {void} No value is returned by this method.
   */
  private handleTerminationAckMessage(data: TerminationAckMessage) {
    if (data.terminated) {

      this.hashtags = this.hashtags.filter(tag => tag !== data.hashtag);
      localStorage.setItem('hashtags', JSON.stringify(this.hashtags));

      const creationDestination = this.destination(data.principal, data.hashtag, 'creation');
      this.terminateSubscriptionByDestination(creationDestination);

      const modificationDestination = this.destination(data.principal, data.hashtag, 'modification');
      this.terminateSubscriptionByDestination(modificationDestination);

      const deletionDestination = this.destination(data.principal, data.hashtag, 'deletion');
      this.terminateSubscriptionByDestination(deletionDestination);

    } else {
      console.error('Could not terminate subscription for principal ' + data.principal + " and hashtag " + data.hashtag);
    }
  }

  /**
   * Terminates a subscription associated with the specified destination.
   * If a subscription exists for the given destination, it unsubscribes and removes the subscription.
   * If no subscription is found, an error is logged.
   *
   * @param {string} dest - The destination identifier for the subscription to terminate.
   * @return {void}
   */
  terminateSubscriptionByDestination(dest: string) {
    console.log('Terminating subscriptions for', dest);
    if (this.subscriptions[dest]) {
      this.subscriptions[dest].unsubscribe();
      delete this.subscriptions[dest];
    } else {
      console.error('No subscription found with destination', dest);
    }
  }

  /**
   * Terminates all active subscriptions and unsubscribes from hashtag-based subscriptions and main subscriptions.
   *
   * Removes all stored subscriptions and informs the backend that the subscriptions have been terminated.
   *
   * @return {void} Does not return a value.
   */
  terminateAllSubscriptions() {

    // Tell the backend, that you terminate all subscriptions
    this.hashtags.forEach(tag => {
      this.unsubscribeHashtag(tag);
    });

    // unsubscribe your main subscription
    this.subscriptionsSubscription.unsubscribe();
    this.terminationsSubscription.unsubscribe();

    // Unsubscribe each hashtag subscription
    Object.entries(this.subscriptions).forEach(
      ([key, value]) => {
        value.unsubscribe();
        delete this.subscriptions[key];
      }
    );
  }

  /**
   * Clears all the items from the receivedMessages collection.
   * This method removes all stored toots, resetting the state of the collection.
   *
   * @return {void} Does not return a value.
   */
  clearAllToots() {
    this.receivedMessages.clear();
    this.messageSubject$.next(this.receivedMessages.toArray())
  }

  /**
   * Subscribes to messages of the type 'StatusCreated' on the given destination.
   * Processes each received message by parsing its content and queuing it for further handling.
   *
   * @param {string} dest - The destination to subscribe to for 'StatusCreated' messages.
   * @return {Subscription} Returns a subscription object that can be used to manage the lifecycle of the subscription.
   */
  subscribeToStatusCreatedMessages(dest: string) {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusCreatedMessage received:', message.body);
        const data: StatusCreatedMessage = JSON.parse(message.body);
        this.receivedMessages.enqueue(data);
        this.messageSubject$.next(this.receivedMessages.toArray())
      });
  }

  /**
   * Subscribes to status updated messages from the specified destination.
   *
   * @param {string} dest The destination to subscribe to for status updated messages.
   * @return {Subscription} A subscription object that can be used to manage the subscription.
   */
  subscribeToStatusUpdatedMessages(dest: string) {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusUpdatedMessage received:', message.body);
        const data: StatusUpdatedMessage = JSON.parse(message.body);
        this.receivedMessages.update(data);
        this.messageSubject$.next(this.receivedMessages.toArray())
      });
  }

  /**
   * Subscribes to a destination for listening to status deleted messages.
   * Processes the message, logs it, and dequeues it from received messages based on its ID.
   *
   * @param {string} dest The destination to subscribe to for receiving status deleted messages.
   * @return {Subscription} A subscription object that can be used to manage the lifecycle of the subscription.
   */
  subscribeToStatusDeletedMessages(dest: string) {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusDeletedMessage received:', message.body);
        const data: StatusDeletedMessage = JSON.parse(message.body);
        this.receivedMessages.dequeue(data.id);
        this.messageSubject$.next(this.receivedMessages.toArray())
      });
  }

  /**
   * Subscribes to updates for the specified hashtag by publishing a subscription request.
   *
   * @param {string} hashtag - The hashtag to subscribe to for updates.
   * @return {void}
   */
  subscribeHashtag(hashtag: string) {
    const message = {hashtag: hashtag};
    this.rxStompService.publish({destination: '/glacier/subscription', body: JSON.stringify(message)});
  }

  /**
   * Unsubscribes from updates for the specified hashtag. Sends a termination request to the server.
   *
   * @param {string} hashtag - The hashtag to unsubscribe from.
   * @return {void} This method does not return a value.
   */
  unsubscribeHashtag(hashtag: string) {
    const message = {hashtag: hashtag};
    this.rxStompService.publish({destination: '/glacier/termination', body: JSON.stringify(message)});
  }

  /**
   * Constructs a destination string based on the provided principal, hashtag, and type.
   *
   * @param {string} principal - The principal or main identifier to be included in the destination path.
   * @param {string} hashtag - The hashtag to be included in the destination path.
   * @param {string} type - The type of the destination or category to be appended.
   * @return {string} The constructed destination path string.
   */
  private destination(principal: string, hashtag: string, type: string): string {
    return '/topic/hashtags/' + principal + '/' + hashtag + '/' + type;
  }
}

/**
 * Class representing a message queue with a limited capacity.
 * The queue provides persistence through localStorage and supports deduplication of messages.
 */
export class MessageQueue {
  private storage: SafeMessage[] = [];

  constructor(private capacity: number = 20) {
  }

  restore(): void {
    let storedMessages: string | null = localStorage.getItem('messageQueue');
    if (storedMessages !== null) {
      this.storage = JSON.parse(storedMessages) as SafeMessage[];
    }
  }

  enqueue(item: SafeMessage): void {
    if (this.size() >= this.capacity) {
      console.log('Queue is full. Removing oldest entries');
      while (this.size() >= this.capacity) {
        this.storage.shift();
      }
      console.log('Queue size is now', this.size());
    }
    if (this.storage.filter(message => message.id === item.id).length) {
      console.log('Message with id', item.id, 'is already known. Ignore new one');
      return;
    }
    this.storage.push(item);
    localStorage.setItem('messageQueue', JSON.stringify(this.storage));
  }

  dequeue(id: string | undefined): SafeMessage | undefined {
    let messageToRemove;
    if (id == undefined) {
      return messageToRemove;
    }
    this.storage.forEach(scm => {
      if (scm.id === id) {
        messageToRemove = scm;
      }
    })
    if (messageToRemove) {
      const index = this.storage.indexOf(messageToRemove);
      if (index > -1) {
        this.storage.splice(index,1);
      }
    }
    // Compact the array by filtering out any undefined or empty values (if any remain)
    this.storage = this.storage.filter(item => item !== undefined);

    localStorage.setItem('messageQueue', JSON.stringify(this.storage));
    return messageToRemove;
  }

  clear(): void {
    while (this.storage.length > 0){
      this.storage.pop();
    }
    localStorage.setItem('messageQueue', JSON.stringify(this.storage));
  }

  size(): number {
    return this.storage.length;
  }

  toArray(): SafeMessage[] {
    return this.storage;
  }

  update(item: StatusUpdatedMessage) {
    const index = this.storage.findIndex(scm => scm.id === item.id);
    if (index !== -1) {
      this.storage = this.storage.map(smc =>
        smc.id === item.id? {
          url: item.url + '?cachebreaker=' + new Date().getTime(),
          id: item.id,
          editedAt: item.editedAt
        } : smc
      );
      console.log('Updated messages', this.storage);
    }
  }
}
