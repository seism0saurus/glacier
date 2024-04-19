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

  describe('optout hint', () => {
    it('should exist', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.optout')).toBeTruthy();
    });

    it('should exist', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.optout')?.innerHTML)
        .toContain('You don\'t want your toots to be shown here?&nbsp;');
      expect(compiled.querySelector('.optout')?.innerHTML)
        .toContain('unknown@example.com in your account');
    });

    it('should have the correct url text', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.optout > a')?.innerHTML)
        .toBe('Block')
    });

    it('should have the correct url to the blocking documentation', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.optout > a')?.getAttribute('href'))
        .toBe('https://docs.joinmastodon.org/user/moderating/#block')
    });

    it('should open the url to the blocking documentation in a new tab with data protection', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.optout > a')?.getAttribute('target'))
        .toBe('_blank')
      expect(compiled.querySelector('.optout > a')?.getAttribute('rel'))
        .toBe('noopener noreferrer')
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
