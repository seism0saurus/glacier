import { ComponentFixture, TestBed } from '@angular/core/testing';

import { GdprComponent } from './gdpr.component';
import {MAT_DIALOG_DATA} from "@angular/material/dialog";

describe('GdprComponent', () => {
  let component: GdprComponent;
  let fixture: ComponentFixture<GdprComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GdprComponent],
      providers: [
        {provide: MAT_DIALOG_DATA, useValue: {
            domain: 'example.com',
            operatorName: 'Jon Doe',
            operatorStreetAndNumber: 'somewhere 1',
            operatorZipcode: '12345',
            operatorCity: 'somecity',
            operatorCountry: 'Germany',
            operatorPhone: '+123456789',
            operatorMail: 'mail@example.com',
            operatorWebsite: 'example.com',
          }}
      ],
    })
    .compileComponents();

    fixture = TestBed.createComponent(GdprComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
