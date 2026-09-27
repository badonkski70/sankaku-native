# Progress

Last updated **2026-09-27**. A handoff note: where the app stands, what was
actually verified and how, and the traps that cost time. Written for someone
picking this up with no chat history.

For what the app *does*, see [README.md](README.md). For what's outstanding, see
[TODO.md](TODO.md). This file is about confidence — what is proven, what is
assumed, and what will bite.

## Needs you first

**The api token is dead and only a password can replace it.** Open Settings,
enter your login and password, sign in. Until then the app browses
anonymously: page 1 only, no favourites, no full results.

This is my fault, not a bug in the app. While finding out whether token
refresh worked, I probed `sankakuapi.com/auth/token` directly, and one of
those calls revoked the token the app was holding. Details under *Tokens*
below.

## What runs

Compose + Paging 3 + Coil + Media3, ~1,900 lines across four Kotlin files:

| File | Holds |
|---|---|
| `Posts.kt` | API surface, `AuthState`, `PostsPagingSource` |
| `MainActivity.kt` | all UI, `MainViewModel` |
| `Download.kt` | download naming, SAF and MediaStore writes |
| `Prefs.kt` | DataStore keys, download history |

## Verified on device, not just compiled

Everything below was watched happening on a Redmi Note 10 Pro (Android 11),
not merely built:

- Launcher icon, token sign-in, account favourites, two-way hearts
- Paging past page 1 (this is what proves the api token is alive)
- Multi-select, batch favourite, batch download, tag-derived filenames
- Duplicate-confirmation dialog, custom download folder surviving a real reboot
- Tag autofill, replace-on-select, Order / Type / rating / blacklist filters
- Rotation, forced over adb: search text, selected tab, a 2-post selection,
  the open post, the duplicate dialog, settings fields, zoom and video
  position all survive
- A forced 401, by corrupting the stored token in the DataStore

**Not verified:** downloading something that 404s, force-stopping mid-download,
and token-paste sign-in. Those are the three items in TODO.md.

## Tokens

This is the part that will bite, and it cost the most to work out.

Both access tokens are JWTs lasting **exactly 7 days** (`exp - iat = 604800`).
The `refresh_token` in the login response is unusable:

```
login.sankakucomplex.com/auth/token  + session refresh  ->  200, new tokens
login.sankakucomplex.com/auth/token  + api refresh      ->  403 jwt issuer invalid
sankakuapi.com/auth/token            + either refresh   ->  401
```

So there is no way to renew the `sankakuapi.com` token, which is the one that
matters — every post comes from that host. Refreshing the login token *does*
work, but it revokes the api token, so attempting it makes things worse. The
app therefore does not try, and a 401 shows a banner that links to Settings.

**Expect to enter the password weekly.** The `exp` claim is readable from the
stored token, so warning the user before expiry is cheap if that is ever
wanted.

An earlier version of the app *did* attempt the refresh, behind a
`runCatching` that swallowed the failure. It looked completely healthy and
silently never worked.

## Traps

- **Test API behaviour from the device, not from this machine.** Several auth
  probes to `sankakuapi.com` got this host's IP blocked; requests now fail to
  connect while ordinary browsing is fine. The phone is unaffected. The app's
  own `adb logcat` is the best signal available.
- **To force a 401:** stop the app, pull
  `files/datastore/settings.preferences_pb` with `run-as`, overwrite just the
  `api_token` JWT bytes with the same number of `x` characters (keeps the
  protobuf length prefix valid), and push it back over stdin. Back the file
  up first. Then `adb shell settings put system user_rotation` is how rotation
  gets forced without touching the phone.
- **`TextFieldValue` has no auto-registered saver** in this Compose version.
  `rememberSaveable` on it crashes the app on launch. It must be passed
  explicitly: `rememberSaveable(stateSaver = TextFieldValue.Saver)`. There is a
  comment on the line, but do not "tidy" it away.
- **Post ids are obfuscated when signed in** and numeric when anonymous, and
  `/posts/{id}` only accepts the obfuscated form. Cached favourites from a
  signed-out session cannot be refreshed by id.

## How this went, briefly

Three bugs in this project shared one cause: asserting something was true
without having tested it.

1. A `$list.size` interpolation dumped an entire serialised `Post` onto the
   screen.
2. The build instructions claimed a clean clone works; it did, but that had
   never been checked on a machine without a warm cache.
3. Token refresh looked fine in code and had never once succeeded.

The habit that would have caught all three: run the thing, then write it down.
A build is not a test, and a log line that prints `present=true` proves only
that a value arrived, not that it was stored.
