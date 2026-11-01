https://oshoapi-0i8tq.bunny.run/discourses/?page=1

All routes require the `x-api-key` header matching the configured API key.

GET /discourses
GET /discourses?page=2
GET /discourses?search=dhyan
GET /discourses?is_audio_cleaned=true
GET /discourses?language=hindi
GET /discourses?search=dhyan&is_audio_cleaned=true&page=1
GET /discourses?language=english&sort=most_liked&page=1
GET /discourses?sort=most_liked&page=1

GET /discourse-audios
GET /discourse-audios?page=2
GET /discourse-audios?search=upanishad
GET /discourse-audios?discourse_name=Adhyatam%20Upanishad
GET /discourse-audios?language=hindi
GET /discourse-audios?search=01&language=hindi&page=1
GET /discourse-audios?sort=most_liked&page=1

Add `sort=most_liked` to either paginated list to order by descending `total_likes`. Ties retain the route's default title/name and track ordering. Omitting `sort` preserves the default order.
The paginated `/discourses` route also accepts `language=hindi` or `language=english`; omitting `language` returns all languages.

# No pagination:
GET /discourse-audios?discourse_id=UUID

PUT /discourses/:id/like
BODY: { "user_id": "USER_UUID" }
Adds one to the discourse `total_likes` on the first like from this user. Repeated likes are idempotent.

PUT /discourse-audios/:id/like
BODY: { "user_id": "USER_UUID" }
Adds one only to the audio's `total_likes` on the first like from this user. Repeated likes are idempotent. Discourse and audio likes are independent; liking or unliking an audio never changes the discourse's count or liked state.

GET /likes?user_id=USER_UUID
Returns the discourse and discourse audio IDs liked by the user:
`{ "discourse": ["DISCOURSE_UUID"], "discourse_audio": ["AUDIO_UUID"] }`.
Either array is empty when the user has no likes of that type. Missing or blank `user_id` returns `400`.

DELETE /likes?user_id=USER_UUID&discourse_id=DISCOURSE_UUID
DELETE /likes?user_id=USER_UUID&discourse_audio_id=AUDIO_UUID
Removes the specified like for that user. `user_id` and exactly one entity ID are required; invalid combinations return `400`. Repeated requests are idempotent and return `unliked: false` without changing counts. A successful removal returns `unliked: true` and the updated `total_likes` for that entity only.

When deploying the independent-count behavior, run `api/migrations/independent-discourse-likes.sql` once against the backend database to recalculate historical discourse totals from direct discourse likes. It is idempotent and does not change users' likes or audio totals. Android does not run this migration.

# Listening stats

POST /stats
BODY: `{ "user_id": "USER_UUID", "discourse_id": "DISCOURSE_UUID", "discourse_audio_id": "AUDIO_UUID" }`
Both IDs are required and must refer to existing records. Android sends an event after 60 seconds of actual playback, including tracks reached with Previous or Next; paused and buffering time is excluded. Each event counts toward both the track and its discourse. If the same user has recorded the audio ID within the prior 24 hours, the event is suppressed; other tracks in the same discourse still count. New events return `201` with `{ "data": { "recorded": true } }`; suppressed repeats return `200` with `{ "data": { "recorded": false, "reason": "duplicate_within_24_hours" } }`.

GET /stats?by=discourse&time=7_days
GET /stats?by=discourse_audio&time=24_hours
`by` accepts `discourse` or `discourse_audio`; `time` accepts `7_days` or `24_hours`. Returns up to 16 full discourse or audio objects, each with an additional `plays` count, ordered by plays descending and ID ascending. Records are fetched separately after ranking IDs; no SQL join is used.


# Timeline

GET /timeline
GET /timeline?page=2
Returns a paginated list of timeline posts, 12 per page, newest first (`created_at DESC`, then `rowid DESC`). The response has `data` (an array of posts) and `meta` containing `page`, `page_size`, `total_records`, and `total_pages`. Missing or invalid page numbers default to 1. Currently returns both approved and unapproved posts; there is no approval filter.

POST /timeline
BODY: `{ "title": "A beautiful moment from Osho", "image_url": "https://example.com/image.jpg", "user_id": "USER_UUID", "discourse_audio_id": "AUDIO_UUID", "start_time": 120, "end_time": 180 }`
Required non-empty text fields: `title`, `image_url`, `user_id`, and `discourse_audio_id`. `start_time` and `end_time` are optional non-negative integer values (seconds); when both are supplied, `end_time` must be greater than `start_time`. Invalid fields return `400`. An unknown `discourse_audio_id` returns `404`.

The server generates a UUID for `id`, finds the referenced audio in `discourse_audio`, populates `discourse_id` from that record, and saves the full audio record as JSON in `discourse_audio`. `likes_count` defaults to `0`, and `is_approved` defaults to `false`. Both routes return `is_approved` as a JSON boolean and `discourse_audio` as a JSON object (or null). A successful POST returns `201` with `{ "data": { ...created timeline post... } }`.

Timeline database fields: `id` (TEXT primary key), `title` (TEXT required), `image_url` (TEXT required), `user_id` (TEXT required), `discourse_id` (TEXT), `discourse_audio_id` (TEXT required), `start_time` (INTEGER), `end_time` (INTEGER), `discourse_audio` (JSON), `likes_count` (INTEGER default 0), `is_approved` (BOOLEAN default false), `created_at` (TEXT), and `updated_at` (TEXT).

# Seed
// https://seeddata-pdiw5.bunny.run/seed
POST /seed
