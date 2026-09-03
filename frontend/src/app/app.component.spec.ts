import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { AppComponent } from './app.component';
import { ShortenResponse } from './models/models';

describe('AppComponent', () => {
  beforeEach(async () => TestBed.configureTestingModule({
    declarations: [AppComponent], imports: [FormsModule, HttpClientTestingModule]
  }).compileComponents());

  it('creates the URL shortener app', () => {
    expect(TestBed.createComponent(AppComponent).componentInstance).toBeTruthy();
  });

  it('renders the product heading', () => {
    const fixture = TestBed.createComponent(AppComponent); fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toContain('Shorten a URL');
  });

  it('rejects a destination without an HTTP scheme', () => {
    const app = TestBed.createComponent(AppComponent).componentInstance;
    app.longUrl = 'example.com'; app.shorten();
    expect(app.error).toContain('http://');
  });

  it('opens a short URL only when the user activates it', () => {
    const app = TestBed.createComponent(AppComponent).componentInstance;
    app.result = {shortUrl:'http://localhost:8080/code',shortCode:'code',longUrl:'https://example.com'} as ShortenResponse;
    const open = spyOn(window, 'open');

    app.openShortUrl();

    expect(open).toHaveBeenCalledOnceWith(app.result.shortUrl, '_blank', 'noopener,noreferrer');
  });
});
