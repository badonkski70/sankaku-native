# TODO

## Manual tests

None of these have been run.

1. **Favourite 10–12 posts in one go.** Each is a separate API call, so this
   was the way to find out whether Sankaku rate-limits us. **Passed** — 13 at
   once, no failures, no rate limiting.

2. **Try to download something undownloadable** — an old or deleted post.
   The URL will 404. Expect a clean "Download failed" notification, no hang
   or crash. Also exercises the new message capping.

3. **Start a download, then force-stop the app.** Downloads run in the
   ViewModel, which dies with the process, so a partial file may be left.
   A design limit rather than a bug, but worth knowing.

4. **Sign in by pasting a token rather than using a password.** No password
   means the app cannot obtain its second token by itself. Settings should
   prompt for one to "enable full browsing"; if the prompt is missing or
   confusing, that needs fixing.

## Fixed

- ~~A custom download folder can lose its permission across a reboot.~~
  Tested with a real reboot: the grant survives, no re-picking needed.
- ~~Token refresh was a silent dead end.~~ The refresh token
  `sankakuapi.com` issues is rejected by *both* hosts, so the retry could
  never succeed, and `runCatching` swallowed the failure for the whole
  session. Worse, refreshing the login host's token revokes the api token,
  so the attempt was actively harmful. All of it is deleted; a 401 now just
  asks for the password, and the banner is tappable.
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
- Downloads run sequentially, so a large batch is slow. A real queue would
  fix it.
- Signing in with a pasted token still needs a password entered in Settings
  before full browsing works. Not a dead end, but hidden.
- **Sessions last 7 days and cannot be renewed**, so expect to re-enter the
  password weekly. The `exp` claim is readable from the token, so a "your
  session expires tomorrow" warning is cheap if it's ever worth adding.
