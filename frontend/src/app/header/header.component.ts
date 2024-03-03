import {Component, OnDestroy, OnInit} from '@angular/core';
import {AnimationService} from "../animation.service";
import {Subscription} from "rxjs";

@Component({
  selector: 'app-header',
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css']
})
export class HeaderComponent implements OnInit, OnDestroy{

  public extended: boolean = true;
  private extendedSubscription?: Subscription;

  constructor(private animationService: AnimationService) {}

  ngOnInit() {
    this.extendedSubscription = this.animationService.getHeaderExtended()
      .subscribe({
        next: value  => {
          console.log('Observable emitted a value: ' + value);
          this.extended = value;
        },
        error: err => console.error('Observable emitted an error: ' + err),
        complete: () => console.log('Observable emitted the complete notification')
      });
  }

  ngOnDestroy(): void {
    if (this.extendedSubscription) {
      this.extendedSubscription.unsubscribe();
    }
  }
}
