# TODO

## Manual tests

None of these have been run. The first two are suspicions, not known bugs —
both are things that were implemented without much thought and would only
fail in ways that show up much later.

1. **Set a custom download folder, restart the phone, then download
   something.** Android can revoke a folder permission across a reboot. If
   that happens, downloads to that folder fail and it would only surface
   weeks later. Fix would be re-picking the folder in Settings.

2. **Rotate the phone sideways and back.** Search text, multi-select and
   the suggestion list all use `remember`, which is lost on rotation. If
   they vanish it is a real bug; the fix is `rememberSaveable`.

3. **Favourite 10–12 posts in one go.** Batch favouriting works, but has
   only been tried with two or three. Each is a separate API call, so this
   is a good way to find out whether Sankaku rate-limits us. If some fail
   silently, that is a bug — nothing currently reports a partial failure.

4. **Log out of Sankaku on the website, then open the app.** Forces a real
   401 and is the only practical way to test token refresh without waiting
   seven days. The app should quietly get a new token. If the "Session
   expired" banner appears instead, the refresh is not working — though
   the website may kill the refresh token too, in which case the banner is
   correct and the refresh was pointless.

5. **Try to download something undownloadable** — an old or deleted post.
   The URL will 404. Expect a clean "Download failed" notification, no hang
   or crash. Also exercises the new message capping.

6. **Start a download, then force-stop the app.** Downloads run in the
   ViewModel, which dies with the process, so a partial file may be left.
   A design limit rather than a bug, but worth knowing.

7. **Sign in by pasting a token rather than using a password.** No password
   means the app cannot obtain its second token by itself. Settings should
   prompt for one to "enable full browsing"; if the prompt is missing or
   confusing, that needs fixing.

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
