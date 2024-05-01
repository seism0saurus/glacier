import {NgModule} from '@angular/core';
import {BrowserModule} from '@angular/platform-browser';

import {AppComponent} from './app.component';
import {RxStompService} from "./rx-stomp.service";
import {rxStompServiceFactory} from "./rx-stomp.factory";
import {WallComponent} from './wall/wall.component';
import {HeaderComponent} from './header/header.component';
import {HashtagComponent} from './hashtag/hashtag.component';
import {FormsModule, ReactiveFormsModule} from "@angular/forms";
import {FooterComponent} from './footer/footer.component';
import {HttpClient, HttpClientModule} from "@angular/common/http";
import {TootComponent} from './toot/toot.component';
import {MatFormField} from "@angular/material/form-field";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {MatIcon} from "@angular/material/icon";
import {MatInputModule} from "@angular/material/input";
import {BrowserAnimationsModule} from "@angular/platform-browser/animations";
import {ResourceUrlSanitizerPipe} from "./wall/resource-url-sanitizer.pipe";
import {CookieService} from "ngx-cookie-service";
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import {MatDialogModule} from "@angular/material/dialog";

@NgModule({
  declarations: [
    AppComponent,
    WallComponent,
    HeaderComponent,
    HashtagComponent,
    FooterComponent,
    TootComponent
  ],
  imports: [
    BrowserModule,
    FormsModule,
    HttpClientModule,
    MatFormField,
    MatChipGrid,
    MatChipRow,
    MatIcon,
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
    MatDialogModule
  ],
  providers: [
    CookieService,
    {
      provide: RxStompService,
      useFactory: rxStompServiceFactory,
      deps: [HttpClient]
    }
  ],
  bootstrap: [AppComponent]
})
export class AppModule {
}
