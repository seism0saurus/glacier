import {ComponentFixture, TestBed} from '@angular/core/testing';
import {ResourceUrlSanitizerPipe} from './resource-url-sanitizer.pipe';
import {
  DomSanitizer,
  SafeHtml,
  SafeResourceUrl,
  SafeScript,
  SafeStyle,
  SafeUrl,
  SafeValue
} from "@angular/platform-browser";
import {SecurityContext} from "@angular/core";

class MockDomSanitizer extends DomSanitizer {
  bypassSecurityTrustResourceUrl(url: string): SafeResourceUrl {
    return 'sanitized:' + url;
  }

  bypassSecurityTrustHtml(value: string): SafeHtml {
    return {};
  }
  bypassSecurityTrustScript(value: string): SafeScript {
    return {};
  }
  bypassSecurityTrustStyle(value: string): SafeStyle {
    return {};
  }
  bypassSecurityTrustUrl(value: string): SafeUrl {
    return {};
  }
  sanitize(context: SecurityContext, value: SafeValue | string | null): string | null;
  sanitize(context: SecurityContext, value: {} | string | null): string | null;
  sanitize(context: SecurityContext, value: SafeValue | string | null | {}): string | null {
    return '';
  }
}

describe('ResourceUrlSanitizerPipe', () => {
  let pipe= new ResourceUrlSanitizerPipe(new MockDomSanitizer());

  it('should create', () => {
    expect(pipe).toBeTruthy();
  });

  it('should return empty string if input url is empty', () => {
    const url: string = '';
    expect(pipe.transform(url)).toBe('');
  });

  it('should return sanitized url when input url is provided', () => {
    const url: string = 'http://example.com';
    expect(pipe.transform(url)).toEqual("sanitized:http://example.com");
  });
});
