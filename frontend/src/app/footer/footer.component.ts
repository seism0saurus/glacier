import {Component, OnInit} from '@angular/core';
import {FooterService} from "./footer.service";
import {MatIconRegistry} from "@angular/material/icon";
import {DomSanitizer} from "@angular/platform-browser";
import {MatDialog} from "@angular/material/dialog";
import {GdprComponent} from "../gdpr/gdpr.component";

@Component({
  selector: 'app-footer',
  templateUrl: './footer.component.html',
  styleUrls: ['./footer.component.css']
})
export class FooterComponent implements OnInit{

  public mastodonHandle: string = "unknown@example.com";

  constructor(private footerService: FooterService,
              private matIconRegistry: MatIconRegistry,
              private domSanitizer: DomSanitizer,
              private dialog: MatDialog
  ) {
    this.matIconRegistry.addSvgIcon(
      `legal_notice_icon`,
      this.domSanitizer.bypassSecurityTrustResourceUrl("../assets/legal.svg")
    );
  }

  ngOnInit() {
    this.footerService.getMastodonHandle()
      .subscribe(data => this.mastodonHandle = data.name);
  }

  openLegal() {
    this.dialog.open(GdprComponent, {
      width: '800px',
      panelClass: 'glacier-modalbox'
    })
  }
}
