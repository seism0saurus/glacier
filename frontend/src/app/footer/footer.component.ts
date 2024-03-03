import {Component, OnInit} from '@angular/core';
import {FooterService} from "./footer.service";

@Component({
  selector: 'app-footer',
  templateUrl: './footer.component.html',
  styleUrls: ['./footer.component.css']
})
export class FooterComponent implements OnInit{

  public mastodonHandle: string = "unknown@example.com";

  constructor(private footerService: FooterService) {
  }
  ngOnInit() {
    this.footerService.getMastodonHandle()
      .subscribe(data => this.mastodonHandle = data.name);
  }
}
