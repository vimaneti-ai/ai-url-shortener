import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AnalyticsResponse, ShortenRequest, ShortenResponse, UpdateRequest } from '../models/models';
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly baseUrl = '/api/v1';
  constructor(private http: HttpClient) {}
  shortenUrl(body: ShortenRequest): Observable<ShortenResponse> { return this.http.post<ShortenResponse>(`${this.baseUrl}/shorten`, body); }
  getAnalytics(code: string): Observable<AnalyticsResponse> { return this.http.get<AnalyticsResponse>(`${this.baseUrl}/analytics/${encodeURIComponent(code)}`); }
  updateUrl(code: string, body: UpdateRequest): Observable<ShortenResponse> { return this.http.put<ShortenResponse>(`${this.baseUrl}/${encodeURIComponent(code)}`, body); }
  deleteUrl(code: string): Observable<void> { return this.http.delete<void>(`${this.baseUrl}/${encodeURIComponent(code)}`); }
}
