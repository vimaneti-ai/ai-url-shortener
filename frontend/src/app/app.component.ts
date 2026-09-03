import { Component } from '@angular/core';
import { ApiService } from './services/api.service';
import { AnalyticsResponse, ShortenResponse } from './models/models';
@Component({ selector: 'app-root', templateUrl: './app.component.html', styleUrl: './app.component.css' })
export class AppComponent {
  view: 'shorten'|'analytics' = 'shorten'; longUrl=''; alias=''; expiry=''; code='';
  result: ShortenResponse|null=null; analytics: AnalyticsResponse|null=null;
  loading=false; error=''; analyticsError=''; copied=false;
  editLongUrl=''; editExpiry=''; editLoading=false; editError=''; editSuccess=false;
  confirmingDelete=false; deleteLoading=false; deleteError='';
  constructor(private api: ApiService) {}
  shorten() {
    this.error='';
    if (!/^https?:\/\//i.test(this.longUrl.trim())) { this.error='Enter a URL beginning with http:// or https://.'; return; }
    this.loading=true;
    this.api.shortenUrl({url:this.longUrl.trim(),customAlias:this.alias.trim()||undefined,expiresAt:this.expiry||undefined}).subscribe({
      next:r=>{this.result=r;this.code=r.shortCode;this.loading=false;},
      error:e=>{this.error=e.status===409?'That alias is already in use.':'Unable to create the short link.';this.loading=false;}
    });
  }
  showAnalytics(code=this.code) { this.view='analytics'; if(code) this.loadAnalytics(code); }
  loadAnalytics(value=this.code) {
    this.analytics=null;this.analyticsError='';
    const cleaned=value.trim().replace(/\/$/,''); const code=cleaned.includes('/')?cleaned.slice(cleaned.lastIndexOf('/')+1):cleaned;
    if(!code){this.analyticsError='Enter a short code or short URL.';return;} this.code=code;this.loading=true;
    this.api.getAnalytics(code).subscribe({next:r=>{this.analytics=r;this.loading=false;},error:()=>{this.analyticsError='No records found.';this.loading=false;}});
  }
  copy(){if(this.result) navigator.clipboard.writeText(this.result.shortUrl).then(()=>{this.copied=true;setTimeout(()=>this.copied=false,1500);});}
  openShortUrl(){if(this.result) window.open(this.result.shortUrl,'_blank','noopener,noreferrer');}
  updateLink() {
    this.editError=''; this.editSuccess=false;
    if (!/^https?:\/\//i.test(this.editLongUrl.trim())) { this.editError='Enter a URL beginning with http:// or https://.'; return; }
    this.editLoading=true;
    this.api.updateUrl(this.code, {url:this.editLongUrl.trim(), expiresAt:this.editExpiry||undefined}).subscribe({
      next:()=>{this.editLoading=false;this.editSuccess=true;this.editLongUrl='';this.editExpiry='';setTimeout(()=>this.editSuccess=false,2500);this.loadAnalytics(this.code);},
      error:e=>{this.editLoading=false;this.editError=e.status===404?'Short code not found.':'Unable to update the link.';}
    });
  }
  deleteLink() {
    this.deleteError='';this.deleteLoading=true;
    this.api.deleteUrl(this.code).subscribe({
      next:()=>{this.deleteLoading=false;this.confirmingDelete=false;this.analytics=null;this.analyticsError='Link deleted.';},
      error:e=>{this.deleteLoading=false;this.deleteError=e.status===404?'Short code not found.':'Unable to delete the link.';}
    });
  }
}
