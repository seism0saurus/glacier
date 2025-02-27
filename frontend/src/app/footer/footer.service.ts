import { Injectable } from '@angular/core';
import { HttpClient } from "@angular/common/http";
import {Handle} from "./handle";
import {InstanceOperator} from "./instance-operator";

/**
 * A service responsible for managing footer-related data and interactions, including
 * fetching information such as the Mastodon handle and instance operator details.
 */
@Injectable({
  providedIn: 'root'
})
export class FooterService {

  constructor(private http: HttpClient) { }

  getMastodonHandle() {
    return this.http.get<Handle>('/rest/mastodon-handle');
  }

  getInstanceOperator() {
    return this.http.get<InstanceOperator>('/rest/operator');
  }
}
