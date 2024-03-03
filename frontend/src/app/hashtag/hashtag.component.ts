import {Component} from '@angular/core';
import {SubscriptionService} from "../subscription.service";

@Component({
  selector: 'app-hashtag',
  templateUrl: './hashtag.component.html',
  styleUrls: ['./hashtag.component.css']
})
export class HashtagComponent {

  hashtag: string = "myHashtag";
  submitted = false;

  constructor(private subscriptionService: SubscriptionService) {
  }

  onSubmit() {
    this.submitted = true;
    this.subscriptionService.subsribeHashtag(this.hashtag);
  }
}
