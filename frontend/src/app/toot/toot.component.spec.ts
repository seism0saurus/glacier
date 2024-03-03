import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TootComponent } from './toot.component';

describe('TootComponent', () => {
  let component: TootComponent;
  let fixture: ComponentFixture<TootComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [TootComponent]
    });
    fixture = TestBed.createComponent(TootComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
