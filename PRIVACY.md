# Privacy

This describes what data this application actually collects, stores, and shares with third
parties, based on the current implementation — not a generic privacy-policy template, and not
legal advice. If you deploy this publicly, have it reviewed against whatever regulations apply to
you (GDPR, CCPA, etc.) before relying on it.

The current instance is publicly accessible at
[https://short.vinodmaneti.com](https://short.vinodmaneti.com). This document is a technical data
inventory for that deployment, not a substitute for a user-facing privacy policy or legal review.

## What's collected

**When you shorten a URL**: the destination URL itself, an optional custom alias, and an optional
expiration timestamp. Nothing else — no account, no email, no name.

**When a short link is clicked** (`ClickEvent`, table `click_events`):
- **IP address** — from the `X-Forwarded-For` header if present, otherwise the direct connection
  address (`request.getRemoteAddr()`).
- **User-Agent header**, verbatim, as sent by the visitor's browser.
- **A country name**, resolved from the IP address (see "Third-party sharing" below).
- **A timestamp** of the click.

There are no cookies, no browser fingerprinting, and no persistent visitor identifier beyond the IP
address captured per click. There is no user authentication anywhere in this application — every
endpoint is open and unauthenticated.

## Third-party sharing

To resolve a click's country, the visitor's **IP address is sent to a third-party API** —
[ipwho.is](https://ipwho.is) first, falling back to [ip-api.com](https://ip-api.com) if that fails
(`GeoIpService`). Only the IP address is sent; no other data about the click accompanies that
request. This lookup is skipped entirely for private/loopback addresses (e.g. local development
traffic never leaves the machine). Results are cached in this application's own Redis instance for
24 hours, so the same visitor IP does not trigger a repeat lookup on every click within that window
— but the *first* click from a given IP within that window is still sent to whichever provider
resolves it.

Neither provider is contracted, endorsed, or controlled by this project — they are free public
services, and their own privacy/retention practices for that IP lookup are outside this
application's control. See their own privacy policies for how they handle logged lookups.

## Retention

- **Click data is retained for as long as the parent short URL exists.** There is no independent
  retention limit or automatic purge of old click events on their own — deleting them happens only
  as a side effect of the short URL itself being removed (via the
  `DELETE /api/v1/shorten/{code}` endpoint,
  or automatically by the hourly cleanup job once a URL's `expiresAt` has passed, which cascades to
  delete its click history along with it). A short URL created without an expiration date will
  retain its full click history indefinitely.
- **GeoIP lookup results are cached for 24 hours** in Redis (`geoip:{ip}` keys), independent of any
  specific click record, then expire naturally.

## No self-service data control

Because there are no accounts or sessions, there is no way for this application to answer "what
data exists about me" or "delete my data" as a general request — the only mechanism is knowing a
specific short code and calling `DELETE /api/v1/shorten/{code}`, which removes that link's
destination and its entire click history (including any IP addresses and resolved countries tied to it). If you
need per-visitor data-subject rights (access, deletion, export) as a compliance requirement, that
is not built into this application and would need to be added — for example by requiring
authentication and scoping links to accounts, which does not currently exist.

## What administrators can see

Anyone able to call `GET /api/v1/analytics/{code}` for a given short code can see every click's IP
address, User-Agent string, and resolved country for that code — there is no access control on
this endpoint. Because the application is publicly deployed, treat the analytics endpoint as
exposing visitor IP addresses to anyone who knows or guesses a short code, not just the link's
creator.

**The Grafana dashboard is a separate, narrower surface.** It shows aggregate operational metrics —
JVM memory, CPU, request rate by path, database connection counts — with no per-visitor IP
addresses, user agents, or click-level data of any kind. It's also the one piece of internal
tooling that actually requires a login (Grafana's own), unlike the analytics endpoint above.
