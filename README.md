<p align="center">
  <img src="docs/logo.png" width="120" alt="Sankaku Native">
</p>

# Sankaku Native

An Android booru browser for Sankaku, written in Jetpack Compose. It talks to
the public API, and if you sign in it talks to your account — your favourites
show up in the app and stay in sync both ways.

## Features

- Browse with endless scroll, tag search and autofill
- Filter by order (date / popularity), file type, rating and blacklisted tags
- Sign in to see your favourites, and favourite from the app
- Multi-select posts to favourite or download in bulk
- Downloads named after the artist and character tags, with duplicate
  detection and a folder you choose in Settings
- Image, video and GIF playback, including animated posts

## Build

Needs JDK 17 and the Android SDK (platform 35). The Gradle wrapper is checked
in, so a fresh clone builds with:

```bash
./gradlew installDebug
```

Point the build at your SDK with `ANDROID_HOME`, or a `local.properties` in
the project root:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Notes

A few things about the API that aren't obvious, and cost a while to work out:

- Favourites aren't an endpoint — they're a tag search, `tags=fav:<username>`.
- Two tokens are needed. `login.sankakucomplex.com` issues the session token,
  but `sankakuapi.com` rejects it and issues its own. Without the second one
  you only get the first page of results.
- A rating is a tag (`tags=rating:safe`), not a query param. `rating=s` is
  silently ignored.
- Blacklisted tags are refused for non-premium accounts, so the blacklist and
  the image-only filter are applied on-device instead.
- `/auth/token` doubles as the refresh endpoint, and the refresh token has to
  be stored or the session dies after seven days.

## Status

Work in progress, no tests yet. See [TODO.md](TODO.md) for what's untested
and what's still outstanding.
