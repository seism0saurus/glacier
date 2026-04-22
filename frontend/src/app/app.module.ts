import {NgModule} from '@angular/core';
import {BrowserModule} from '@angular/platform-browser';
import {NgIf} from '@angular/common';

import {AppComponent} from './app.component';
import {RxStompService} from "./rx-stomp.service";
import {rxStompServiceFactory} from "./rx-stomp.factory";
import {WallComponent} from './wall/wall.component';
import {HeaderComponent} from './header/header.component';
import {HashtagComponent} from './hashtag/hashtag.component';
import {FormsModule, ReactiveFormsModule} from "@angular/forms";
import {FooterComponent} from './footer/footer.component';
import {HttpClient, provideHttpClient, withInterceptorsFromDi} from "@angular/common/http";
import {TootComponent} from './toot/toot.component';
import {MatFormField} from "@angular/material/form-field";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {MatIconModule} from "@angular/material/icon";
import {MatInputModule} from "@angular/material/input";
import {BrowserAnimationsModule} from "@angular/platform-browser/animations";
import {ResourceUrlSanitizerPipe} from "./wall/resource-url-sanitizer.pipe";
import {CookieService} from "ngx-cookie-service";
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import {MatDialogModule} from "@angular/material/dialog";
import {DOCUMENT} from "@angular/common";
import {MatSnackBarModule} from "@angular/material/snack-bar";
import {MatMenuModule} from "@angular/material/menu";
import {MatButtonModule} from "@angular/material/button";
import {ConnectionStatusComponent} from "./connection-status/connection-status.component";

@NgModule({
  declarations: [
    AppComponent,
    WallComponent,
    HeaderComponent,
    HashtagComponent,
    FooterComponent,
    TootComponent,
    ConnectionStatusComponent,
  ],
  bootstrap: [AppComponent],
  imports: [
    BrowserModule,
    FormsModule,
    MatFormField,
    MatChipGrid,
    MatChipRow,
    MatIconModule,
    ReactiveFormsModule,
    BrowserAnimationsModule,
    MatInputModule,
    MatChipInput,
    MatChipGrid,
    MatChipRow,
    MatChipRemove,
    ResourceUrlSanitizerPipe,
    MatGridList,
    MatGridTile,
    MatDialogModule,
    MatSnackBarModule,
    MatMenuModule,
    MatButtonModule,
    NgIf,
  ],
  providers: [
    CookieService,
    {
      provide: RxStompService,
      useFactory: rxStompServiceFactory,
      deps: [HttpClient, DOCUMENT],
    },
    provideHttpClient(withInterceptorsFromDi()),
  ],
})
export class AppModule {
}
