# Sankaku Native

An Android booru browser (Compose + Paging + Coil + Media3) that talks to
Sankaku's public API and your own account.

## Features

- Browse with paging, tag search, tag autofill (`tags/autosuggest`), and
  Order / Type / rating / blacklist filters
- Sign in to see the account's favourites, which sync both ways — a heart here
  appears on the website, and vice versa
- Multi-select any grid to favourite or download in bulk
- Downloads named after the artist and character tags, with duplicate
  detection and a configurable destination folder

## Build

Requires JDK 17 and the Android SDK. The Gradle wrapper is checked in, so a
clean clone builds with `./gradlew assembleDebug` — verified from a fresh
`git clone` with no `local.properties` present, as long as `ANDROID_HOME`
points at the SDK. Without either, the build stops with an error naming both
options.

The Android SDK location is set with `ANDROID_HOME`, or per-checkout with
`local.properties` in the project root:

```properties
sdk.dir=/path/to/Android/Sdk
```

The build also needs SDK platform **35** and build-tools **35.0.0**:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

Install onto a connected device with `./gradlew installDebug`.

## API notes

Things that are not obvious from the code, learned by reading the site's own
JavaScript and verified against the live API:

- **Favourites are not an endpoint.** They are a tag search on `/posts`:
  `tags=fav:<username>`. Writes are `POST`/`DELETE posts/{id}/favorite`.
- **Two tokens, two hosts.** `login.sankakucomplex.com` issues the session
  token; `sankakuapi.com` rejects it outright with `common_unauthorized` and
  needs its own. `Api` picks per host. Without the second one only the first
  page of results loads, because the session token 401s on page 2.
- **A rating is a tag, not a query param.** `rating=s` is silently ignored;
  `tags=rating:safe` works. Only one rating can be sent, since space-separated
  tags are AND and comma/pipe are not OR.
- **Excluded tags are refused** for non-premium accounts
  (`snackbar__account_regular_excluded-tags-limit`), so the blacklist and the
  "Image only" filter are applied client-side.
- `/auth/token` doubles as the refresh endpoint; the response carries a
  `refresh_token` that must be persisted or the session dies after 7 days.
- Post IDs are obfuscated strings when authenticated and numeric when
  anonymous, and `/posts/{id}` only accepts the obfuscated form. Cached
  signed-out favourites therefore cannot be refreshed by id.

## Known gaps

See [TODO.md](TODO.md) for the untested paths and outstanding code work.
