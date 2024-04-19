import { ComponentFixture, TestBed } from '@angular/core/testing';

import { WallComponent } from './wall.component';
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import {ResourceUrlSanitizerPipe} from "./resource-url-sanitizer.pipe";

describe('WallComponent', () => {
  let component: WallComponent;
  let fixture: ComponentFixture<WallComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [WallComponent],
      imports: [
        MatGridList,
        MatGridTile,
        ResourceUrlSanitizerPipe
      ]
    });
    fixture = TestBed.createComponent(WallComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
