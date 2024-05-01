import {Component} from '@angular/core';
import {MatDialogActions, MatDialogClose, MatDialogContent, MatDialogRef} from "@angular/material/dialog";
import {MatIcon} from "@angular/material/icon";
import {gdpr} from "../../environments/gdpr";
import {environment} from "../../environments/environment";

@Component({
  selector: 'app-gdpr',
  standalone: true,
  imports: [
    MatIcon,
    MatDialogActions,
    MatDialogClose,
    MatDialogContent
  ],
  templateUrl: './gdpr.component.html',
  styleUrl: './gdpr.component.css'
})
export class GdprComponent {
  constructor() {
  }

  protected readonly gdpr = gdpr;
  protected readonly environment = environment;
}
