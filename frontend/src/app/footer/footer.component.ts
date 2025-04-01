import {Component, OnInit} from '@angular/core';
import {FooterService} from "./footer.service";
import {MatIconRegistry} from "@angular/material/icon";
import {DomSanitizer} from "@angular/platform-browser";
import {MatDialog} from "@angular/material/dialog";
import {GdprComponent} from "../gdpr/gdpr.component";
import {InstanceOperator} from "./instance-operator";

@Component({
    selector: 'app-footer',
    templateUrl: './footer.component.html',
    styleUrls: ['./footer.component.css'],
    standalone: false
})
export class FooterComponent implements OnInit{

  public mastodonHandle: string = "unknown@example.com";
  private instanceOperator: InstanceOperator = {
    domain: 'example.com',
    operatorName: 'Jon Doe',
    operatorStreetAndNumber: 'somewhere 1',
    operatorZipcode: '12345',
    operatorCity: 'somecity',
    operatorCountry: 'Germany',
    operatorPhone: '+123456789',
    operatorMail: 'mail@example.com',
    operatorWebsite: 'example.com',
  };


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
      .subscribe();

    this.footerService.getInstanceOperator()
      .subscribe();
  }

  openLegal() {
    this.dialog.open(GdprComponent, {
      width: '800px',
      data: this.instanceOperator,
      panelClass: 'glacier-modalbox'
    })
  }
}
