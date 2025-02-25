import {DomSanitizer, SafeResourceUrl} from "@angular/platform-browser";
import {Pipe, PipeTransform} from "@angular/core";

/**
 * A custom Angular pipe to sanitize resource URLs, specifically for embedding
 * elements like iframes. This pipe ensures the provided URL is treated as safe
 * and bypasses Angular's built-in security restrictions using DomSanitizer.
 *
 * This pipe is especially useful to prevent frequent iframe reloads caused by
 * Angular change detection, as it allows the sanitized URL to be cached.
 *
 * Note: Use this pipe cautiously to avoid security vulnerabilities. Ensure
 * that the URLs being sanitized are from trusted sources.
 */
@Pipe({
  standalone: true,
  name: 'resourceUrlSanitizer'
})
export class ResourceUrlSanitizerPipe implements PipeTransform{

  constructor(private sanitizer: DomSanitizer) {}

  // This pipe is needed to cache the sanitized URL. Otherwise, angular will reload the iframe on each detected change.
  transform(url: string): SafeResourceUrl {
    if(!url){
      return '';
    }
    return this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
