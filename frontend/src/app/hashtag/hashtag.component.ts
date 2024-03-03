import {Component} from '@angular/core';
import {SubscriptionService} from "../subscription.service";
import {AnimationService} from "../animation.service";

@Component({
  selector: 'app-hashtag',
  templateUrl: './hashtag.component.html',
  styleUrls: ['./hashtag.component.css']
})
export class HashtagComponent {

  hashtag: string = "Enter a hashtag (without #)";
  submitted = false;

  constructor(private subscriptionService: SubscriptionService,
              private animationService: AnimationService) {
  }

  onSubmit() {
    this.submitted = true;
    this.animationService.setSubscribed();
    this.subscriptionService.subsribeHashtag(this.hashtag);
  }
}
