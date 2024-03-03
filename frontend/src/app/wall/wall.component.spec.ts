import { ComponentFixture, TestBed } from '@angular/core/testing';

import { WallComponent } from './wall.component';

describe('MessagesComponent', () => {
  let component: WallComponent;
  let fixture: ComponentFixture<WallComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [WallComponent]
    });
    fixture = TestBed.createComponent(WallComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
