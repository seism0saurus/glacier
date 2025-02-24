import {Component, Inject} from '@angular/core';
import {MAT_DIALOG_DATA, MatDialogActions, MatDialogClose, MatDialogContent} from "@angular/material/dialog";
import {MatIcon} from "@angular/material/icon";
import {InstanceOperator} from "../footer/instance-operator";

/**
 * Component responsible for managing GDPR-related data presentation and user interactions.
 * It is intended to be used as a modal dialog that displays relevant GDPR information and options.
 *
 * Selector: `app-gdpr`
 *
 * This component relies on Angular Material UI components for its visual and functional behavior.
 *
 * Dependencies:
 * - MatIcon: Provides material design icons used in the component.
 * - MatDialogActions: Defines dialog-specific actions, such as confirm or close functionalities.
 * - MatDialogClose: Adds close functionality to the dialog.
 * - MatDialogContent: Structures the content section of the dialog.
 *
 * Constructor Parameters:
 * - data: An instance of `InstanceOperator` injected from MAT_DIALOG_DATA, which provides the necessary inputs or meta-information to the component.
 */
@Component({
  selector: 'app-gdpr',
  imports: [
    MatDialogActions,
    MatDialogClose,
    MatDialogContent
  ],
  templateUrl: './gdpr.component.html',
  standalone: true,
  styleUrl: './gdpr.component.css'
})
export class GdprComponent {
  constructor(@Inject(MAT_DIALOG_DATA) public data: InstanceOperator) {
  }
}
