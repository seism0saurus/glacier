import { Injectable } from '@angular/core';
import {HttpClient} from "@angular/common/http";
import {Handle} from "./handle";

@Injectable({
  providedIn: 'root'
})
export class FooterService {

  constructor(private http: HttpClient) { }

  getMastodonHandle() {
    return this.http.get<Handle>('http://localhost:8080/rest/mastodon-handle');
  }
}
