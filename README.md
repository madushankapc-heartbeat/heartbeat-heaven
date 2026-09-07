# HEARTBEAT HEAVEN
## Original Music by Madushanka

A responsive music website with:
- Home page
- Latest releases
- Search and mood/genre filters
- Individual song pages
- Audio player
- Lyrics
- Private Studio upload page
- MP3/cover upload
- SQLite database
- Automatic song listing

## Run it on a computer

1. Install Node.js 18+.
2. Open a terminal in this folder.
3. Run:
   npm install
4. Run:
   npm start
5. Open:
   http://localhost:3000

## Add songs
Open:
http://localhost:3000/admin.html

Upload:
- Song title
- MP3/WAV/M4A/AAC audio
- JPG/PNG/WEBP cover
- Lyrics
- Genre, language and mood

The uploaded files and song records are stored locally in `uploads/` and `data/`.

## IMPORTANT BEFORE PUBLIC LAUNCH
The included Studio page is intentionally a simple private starter. Before putting it on the public internet, protect `/admin.html` and the `/api/songs` POST/DELETE endpoints with real authentication (or deploy behind a private admin system). For a production-scale site, use object storage/CDN for audio and images.

## Suggested brand
HEARTBEAT HEAVEN
Original Music by Madushanka

The site is intentionally genre-neutral so it can hold Sinhala, Hindi, English, romantic, sad, chill, cinematic, EDM and instrumental releases under one brand.
