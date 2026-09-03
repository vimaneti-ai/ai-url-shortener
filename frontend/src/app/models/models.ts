export interface ShortenRequest { url: string; customAlias?: string; expiresAt?: string; }
export interface ShortenResponse { shortUrl: string; shortCode: string; longUrl: string; expiresAt?: string; }
export interface UpdateRequest { url: string; expiresAt?: string; }
export interface ClickDetails { ipAddress?: string; userAgent?: string; country?: string; clickedAt: string; }
export interface AnalyticsResponse { shortUrl: string; shortCode: string; clicks: number; uniqueVisitors: number; countries: Record<string, number>; recentClicks: ClickDetails[]; }
