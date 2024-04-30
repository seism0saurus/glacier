import {ComponentFixture, TestBed} from '@angular/core/testing';
import {FooterComponent} from './footer.component';
import {HttpClientTestingModule} from "@angular/common/http/testing";
import {Handle} from "./handle";
import {FooterService} from "./footer.service";
import {Observable, of} from "rxjs";

describe('FooterComponent', () => {
  let component: FooterComponent;
  let service: FooterService;
  let spy: any;
  let fixture: ComponentFixture<FooterComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [FooterComponent],
      imports: [HttpClientTestingModule]
    });
    fixture = TestBed.createComponent(FooterComponent);
    component = fixture.componentInstance;
    service = TestBed.inject(FooterService);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('howto hint', () => {
    it('should exist', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.howto')).toBeTruthy();
    });

    it('should contain the correct text', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.howto')?.innerHTML)
        .toContain('You want your toots to be shown here?&nbsp;Mention @unknown@example.com in your toot and use one of the hashtags.');
    });
  });

  describe('copyright', () => {
      it('should exist', () => {
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('.copyright')).toBeTruthy();
      });

      it('should have the correct copyright text', () => {
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('.copyright')?.innerHTML)
          .toContain('Glacier is Open Source: ')
      });

      it('should have the correct url text', () => {
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('.copyright > a')?.innerHTML)
          .toBe('https://github.com/seism0saurus/glacier')
      });

      it('should have the correct repository url', () => {
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('.copyright > a')?.getAttribute('href'))
          .toBe('https://github.com/seism0saurus/glacier')
      });

      it('should open the repository url in a new tab with data protection', () => {
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('.copyright > a')?.getAttribute('target'))
          .toBe('_blank')
        expect(compiled.querySelector('.copyright > a')?.getAttribute('rel'))
          .toBe('noopener noreferrer')
      });
    });

  it('should load mastodon handle on init', () => {
    spy = spyOn(service, 'getMastodonHandle').and.returnValue(of({name:'mastodon@example.com'}));
    component.ngOnInit()
    expect(component.mastodonHandle).toEqual('mastodon@example.com');
  });
});
