# TODO

## Manual tests

None of these have been run.

1. **Try to download something undownloadable** — an old or deleted post.
   The URL will 404. Expect a clean "Download failed" notification, no hang
   or crash. Also exercises the new message capping.

2. **Start a download, then force-stop the app.** Downloads run in the
   ViewModel, which dies with the process, so a partial file may be left.
   A design limit rather than a bug, but worth knowing.

3. **Sign in by pasting a token rather than using a password.** No password
   means the app cannot obtain its second token by itself. Settings should
   prompt for one to "enable full browsing"; if the prompt is missing or
   confusing, that needs fixing.

## Fixed

- ~~Favouriting 10+ posts might hit a rate limit or fail silently.~~ 13 in
  one go: no failures, no rate limiting. Sequential calls are fine at this
  scale.
- ~~A custom download folder can lose its permission across a reboot.~~
  Tested with a real reboot: the grant survives, no re-picking needed.
- ~~Token refresh was a silent dead end.~~ The refresh token
  `sankakuapi.com` issues is rejected by *both* hosts, so the retry could
  never succeed, and `runCatching` swallowed the failure for the whole
  session. Worse, refreshing the login host's token revokes the api token,
  so the attempt was actively harmful. All of it is deleted; a 401 now just
  asks for the password, and the banner is tappable.
- ~~The Browse grid reset its scroll position on every rotation and tab
  switch.~~ It had no list state at all — the grid was built with no `state`
  parameter, so the position lived and died with the composable. The index now
  lives in the ViewModel. Verified on device by fingerprinting the post ids
  the grid shows (`uiautomator dump`, first id on screen): holds across a tab
  round-trip, a forced rotation and back, and all three again from about 20
  swipes deep, where re-entry has to wait for paging to load up to the saved
  index. A new search still starts at the top. Cold start still starts at the
  top — the ViewModel dies with the process, which is a separate feature.
- ~~Rotation lost everything in `remember`.~~ Now `rememberSaveable`
  throughout, verified on device: search text, selected tab, multi-select
  (a custom saver, since the maps hold whole `Post`s), the open post, the
  duplicate dialog, settings fields, image zoom and video position all
  survive a rotation. `TextFieldValue` needed an explicit saver — it has
  no auto-registered one, and the app crashed on launch without it.

## Code

- **No oldest-first sort.** `order:date` (the "Newest" chip) works, but
  nothing reverses it — see the trap in PROGRESS.md. If it is ever wanted it
  has to be faked client-side, which paging makes awkward: the oldest posts are
  the *last* pages, so it means walking to the end and reading backwards.
- **No automated tests.** The download naming, duplicate split, name
  disambiguation and rating-tag logic are all pure functions and are the
  obvious place to start.
- Downloads run sequentially, so a large batch is slow. A real queue would
  fix it.
- Signing in with a pasted token still needs a password entered in Settings
  before full browsing works. Not a dead end, but hidden.
- **Sessions last 7 days and cannot be renewed**, so expect to re-enter the
  password weekly. The `exp` claim is readable from the token, so a "your
  session expires tomorrow" warning is cheap if it's ever worth adding.
