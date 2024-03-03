import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';

import { AppComponent } from './app.component';
import {RxStompService} from "./rx-stomp.service";
import {rxStompServiceFactory} from "./rx-stomp.factory";
import { WallComponent } from './wall/wall.component';
import { HeaderComponent } from './header/header.component';
import { HashtagComponent } from './hashtag/hashtag.component';
import {FormsModule} from "@angular/forms";
import { FooterComponent } from './footer/footer.component';
import {HttpClientModule} from "@angular/common/http";
import { TootComponent } from './toot/toot.component';

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
    HttpClientModule
  ],
  providers: [
    {
      provide: RxStompService,
      useFactory: rxStompServiceFactory,
    }
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
