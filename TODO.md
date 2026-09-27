# TODO

## Manual tests

None of these have been run.

1. **Favourite 10–12 posts in one go.** Batch favouriting works, but has
   only been tried with two or three. Each is a separate API call, so this
   is a good way to find out whether Sankaku rate-limits us. If some fail
   silently, that is a bug — nothing currently reports a partial failure.

2. **Log out of Sankaku on the website, then open the app.** Forces a real
   401 and is the only practical way to test token refresh without waiting
   seven days. The app should quietly get a new token. If the "Session
   expired" banner appears instead, the refresh is not working — though
   the website may kill the refresh token too, in which case the banner is
   correct and the refresh was pointless.

3. **Try to download something undownloadable** — an old or deleted post.
   The URL will 404. Expect a clean "Download failed" notification, no hang
   or crash. Also exercises the new message capping.

4. **Start a download, then force-stop the app.** Downloads run in the
   ViewModel, which dies with the process, so a partial file may be left.
   A design limit rather than a bug, but worth knowing.

5. **Sign in by pasting a token rather than using a password.** No password
   means the app cannot obtain its second token by itself. Settings should
   prompt for one to "enable full browsing"; if the prompt is missing or
   confusing, that needs fixing.

## Fixed

- ~~A custom download folder can lose its permission across a reboot.~~
  Tested with a real reboot: the grant survives, no re-picking needed.
- ~~Rotation lost everything in `remember`.~~ Now `rememberSaveable`
  throughout, verified on device: search text, selected tab, multi-select
  (a custom saver, since the maps hold whole `Post`s), the open post, the
  duplicate dialog, settings fields, image zoom and video position all
  survive a rotation. `TextFieldValue` needed an explicit saver — it has
  no auto-registered one, and the app crashed on launch without it.

## Code

- **No automated tests.** The download naming, duplicate split, name
  disambiguation and rating-tag logic are all pure functions and are the
  obvious place to start.
- `runBlocking` inside the OkHttp interceptor for token refresh. Fine
  while one dispatcher thread is blocked, but it is the wrong tool under
  heavy concurrency.
- Downloads run sequentially, so a large batch is slow. A real queue would
  fix it.
- Signing in with a pasted token still needs a password entered in Settings
  before full browsing works. Not a dead end, but hidden.
