import {Component, Inject} from '@angular/core';
import {MAT_DIALOG_DATA, MatDialogActions, MatDialogClose, MatDialogContent} from "@angular/material/dialog";
import {MatIcon} from "@angular/material/icon";
import {InstanceOperator} from "../footer/instance-operator";

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
  constructor(@Inject(MAT_DIALOG_DATA) public data: InstanceOperator) {
  }
}
