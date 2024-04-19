import {DomSanitizer, SafeResourceUrl} from "@angular/platform-browser";
import {Pipe, PipeTransform} from "@angular/core";

@Pipe({
  standalone: true,
  name: 'resourceUrlSanitizer'
})
export class ResourceUrlSanitizerPipe implements PipeTransform{

  constructor(private sanitizer: DomSanitizer) {}

  // This pipe is needed to cache the sanitized URL. Otherwise angular will reload the iframe on each detected change.
  transform(url: string): SafeResourceUrl {
    if(!url){
      return '';
    }
    let sanitizedUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
    return sanitizedUrl;
  }
}
