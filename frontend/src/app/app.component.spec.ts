import {TestBed} from '@angular/core/testing';
import {AppComponent} from './app.component';
import {WallComponent} from "./wall/wall.component";
import {HeaderComponent} from "./header/header.component";
import {HashtagComponent} from "./hashtag/hashtag.component";
import {FooterComponent} from "./footer/footer.component";
import {TootComponent} from "./toot/toot.component";
import {HttpClientTestingModule} from "@angular/common/http/testing";
import {BrowserModule} from "@angular/platform-browser";
import {FormsModule, ReactiveFormsModule} from "@angular/forms";
import {MatFormField} from "@angular/material/form-field";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {MatIcon} from "@angular/material/icon";
import {BrowserAnimationsModule} from "@angular/platform-browser/animations";
import {MatInputModule} from "@angular/material/input";
import {ResourceUrlSanitizerPipe} from "./wall/resource-url-sanitizer.pipe";
import {MatGridList, MatGridTile} from "@angular/material/grid-list";

describe('AppComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({
    declarations: [
      AppComponent,
      WallComponent,
      HeaderComponent,
      HashtagComponent,
      FooterComponent,
      TootComponent
    ],
    imports: [
      HttpClientTestingModule,
      BrowserModule,
      FormsModule,
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
      MatGridTile
    ]
  }));

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should contain a header component for title and logo', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-header')).toBeTruthy();
  });

  it('should contain a hashtag component for selecting hashtags', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-hashtag')).toBeTruthy();
  });

  it('should contain a wall component to show toots', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-wall')).toBeTruthy();
  });

  it('should contain a footer component for copyright and other hints', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-footer')).toBeTruthy();
  });
});
