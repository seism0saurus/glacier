import {ComponentFixture, TestBed} from '@angular/core/testing';

import {TootComponent} from './toot.component';
import {HttpClientTestingModule} from "@angular/common/http/testing";

describe('TootComponent', () => {
  let component: TootComponent;
  let fixture: ComponentFixture<TootComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [TootComponent],
      imports: [
        HttpClientTestingModule,
      ]
    });
    fixture = TestBed.createComponent(TootComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  xit('should create', () => {
    expect(component).toBeTruthy();
  });
});
