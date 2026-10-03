# HEARTBEAT HEAVEN
## Original Music by Madushanka

HEARTBEAT HEAVEN is a responsive music website for discovering and listening to music releases by Madushanka.

### Current features

- Home page and latest releases
- Search and mood/genre filtering
- Individual song pages with SEO metadata
- Audio player and lyrics
- Original songs and song versions
- Private Studio for song management
- Secure Studio login with protected song and upload APIs
- Direct Supabase Storage uploads using signed upload URLs
- Public audio and cover delivery through Supabase Storage
- Visitor counter with server-side rate limiting
- Privacy Policy, Terms of Use, Account Deletion and Community Guidelines pages
- Responsive layout for desktop and mobile
- Security headers and a strict Content Security Policy
- Realtime messaging, private groups, stories and account features are provided by the connected Supabase application/backend

## Production

Live website:

https://heartbeat-heaven.onrender.com

The production web service runs on Render.

## Architecture

The website uses:

- Node.js + Express for the web server and protected Studio API
- Supabase for application data, authentication-related backend services and storage
- Supabase Storage for audio and cover files
- Server-side Supabase service-role access for protected Studio operations
- Signed upload URLs so large media files are uploaded directly to storage instead of being sent through the Node.js JSON API

The Supabase service-role key must remain server-side and must never be placed in public HTML, browser JavaScript or an Android APK.

## Run it locally

Requirements:

- Node.js 18+
- A Supabase project configured for the application

Install dependencies:

    npm install

Set the required environment variables before starting the server:

- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`
- `ADMIN_USER`
- `ADMIN_PASSWORD`
- `STUDIO_SESSION_SECRET` (at least 32 characters)

Start the server:

    npm start

Open:

http://localhost:3000

If no `PORT` is provided, the application uses port 10000 in the server code.

## Studio

The private Studio is available at:

http://localhost:3000/admin.html

Unauthenticated visitors are redirected to the Studio login page.

Studio operations are protected server-side, including:

- Loading songs
- Creating songs and versions
- Editing songs
- Deleting songs
- Preparing audio/cover uploads

Large media files are uploaded directly to Supabase Storage through short-lived signed upload URLs.

## Storage

Current public media buckets:

- `audio`
- `covers`
- `profile-pictures`
- `stories`

Private media buckets are used for protected application content such as:

- `chat-media`
- `group-media`
- `group-profile-pictures`

Do not commit Supabase secrets, passwords, session secrets or other private credentials to GitHub.

## Security and performance

The current production code includes:

- Server-side Studio authentication
- Timing-safe credential comparisons
- Login rate limiting and temporary blocking
- Same-origin/CSRF protection for protected mutations
- Secure Studio session cookies
- Security response headers
- Strict Content Security Policy without inline scripts/styles
- Visitor-counter rate limiting
- Supabase Row Level Security and protected database functions
- Server-side use of the Supabase service-role key
- Realtime updates with reduced redundant polling
- Optimized Supabase RLS policies using `(select auth.uid())` where applicable
- Duplicate-index cleanup

Security-sensitive changes should be reviewed before merging and verified on production after deployment.

## Legal pages

Production legal pages:

- Privacy Policy — `/privacy-policy.html`
- Terms of Use — `/terms-of-use.html`
- Account Deletion — `/delete-account.html`
- Community Guidelines — `/community-guidelines.html`

These pages are part of the public website and should be kept in sync with actual application behavior.

## Android app

The Android application uses the package:

`com.heartbeatheaven.app`

The current GitHub `main` branch does not contain a complete Gradle/Android Studio build configuration. The Android release source/build project therefore needs to be verified separately before generating a new signed release APK.

The website's direct APK distribution system is not yet enabled in this repository.

## Content and brand

**HEARTBEAT HEAVEN**  
Original Music by Madushanka

The platform is designed to support Sinhala, Hindi, English, Tamil and other music releases across romantic, emotional, chill, cinematic, EDM, remix, nonstop and instrumental styles.

## Project safety rules

When making production changes:

1. Inspect the current code and database configuration first.
2. Make the smallest change that solves the identified issue.
3. Do not expose service-role credentials or other secrets.
4. Preserve existing authentication, RLS, private-group moderation access, realtime messaging, media security and account recovery behavior.
5. Verify the application after deployment before moving to the next production change.
