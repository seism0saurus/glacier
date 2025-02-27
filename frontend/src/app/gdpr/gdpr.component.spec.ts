import {ComponentFixture, TestBed} from '@angular/core/testing';

import {GdprComponent} from './gdpr.component';
import {MAT_DIALOG_DATA} from "@angular/material/dialog";

describe('GdprComponent', () => {
  let component: GdprComponent;
  let fixture: ComponentFixture<GdprComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GdprComponent],
      providers: [
        {
          provide: MAT_DIALOG_DATA, useValue: {
            domain: 'example.com',
            operatorName: 'Jon Doe',
            operatorStreetAndNumber: 'somewhere 1',
            operatorZipcode: '12345',
            operatorCity: 'somecity',
            operatorCountry: 'Germany',
            operatorPhone: '+123456789',
            operatorMail: 'mail@example.com',
            operatorWebsite: 'example.com',
          }
        }
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

  it('should inject MAT_DIALOG_DATA with expected values', () => {
    const expectedData = {
      domain: 'example.com',
      operatorName: 'Jon Doe',
      operatorStreetAndNumber: 'somewhere 1',
      operatorZipcode: '12345',
      operatorCity: 'somecity',
      operatorCountry: 'Germany',
      operatorPhone: '+123456789',
      operatorMail: 'mail@example.com',
      operatorWebsite: 'example.com',
    };
    expect(component.data).toEqual(expectedData);
  });

  it('should display operator name correctly in template', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const operatorNameElement = compiled.querySelector('.operator');
    expect(operatorNameElement?.textContent).toBe('Jon Doe');
  });

  it('should display address correctly in template', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const addressNameElement = compiled.querySelector('.address');
    expect(addressNameElement?.textContent).toBe('somewhere 1 12345 somecity Germany');
  });

  it('should display contact information correctly in template', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const contactNameElement = compiled.querySelector('.contact');
    expect(contactNameElement?.textContent).toBe('Phone: +123456789 Email: mail@example.com Website: example.com');
  });
});
