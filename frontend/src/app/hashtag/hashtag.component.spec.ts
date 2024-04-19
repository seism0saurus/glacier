import { ComponentFixture, TestBed } from '@angular/core/testing';

import { HashtagComponent } from './hashtag.component';
import {MatFormField} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {ResourceUrlSanitizerPipe} from "../wall/resource-url-sanitizer.pipe";
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import {MatIcon} from "@angular/material/icon";
import {HttpClientTestingModule} from "@angular/common/http/testing";
import {BrowserAnimationsModule} from "@angular/platform-browser/animations";

describe('HashtagComponent', () => {
  let component: HashtagComponent;
  let fixture: ComponentFixture<HashtagComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [HashtagComponent],
      imports: [
        HttpClientTestingModule,
        MatFormField,
        MatInputModule,
        MatChipInput,
        MatChipGrid,
        MatChipRow,
        MatChipRemove,
        MatIcon,
        BrowserAnimationsModule
      ]
    });
    fixture = TestBed.createComponent(HashtagComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
