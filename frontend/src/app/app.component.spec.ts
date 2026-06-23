import {TestBed} from '@angular/core/testing';
import {RouterModule} from '@angular/router';
import {AppComponent} from './app.component';

/**
 * AppComponent is now a thin router shell: it hosts the router outlet only.
 * The main-wall layout assertions moved to main-wall.component.spec.ts; the
 * share-view routes are covered by the share specs. Here we only verify the
 * shell creates and renders a <router-outlet>.
 */
describe('AppComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [AppComponent],
      imports: [RouterModule.forRoot([])],
    });
  });

  it('should create the app shell', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('should render a router outlet', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('router-outlet')).toBeTruthy();
  });
});
