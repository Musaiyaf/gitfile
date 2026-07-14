# gitfile

A GitHub repository file manager for Android. Browse a repo, edit a file, commit
it, cut a branch, open a pull request — from a phone.

The app is one HTML file in `app/src/main/assets/`. `MainActivity` is a WebView
shell around it. GitHub Actions builds the APK; you never need Android Studio.

```
app/src/main/assets/index.html          the entire app
app/src/main/java/.../MainActivity.java the WebView shell
app/src/main/res/mipmap-*               launcher icons, all densities + adaptive
app/build.gradle                        signing, SDK levels
settings.gradle                         plugin + repository resolution
.github/workflows/build.yml             the APK build
.github/workflows/create-keystore.yml   run once, ever
```

## Setup

1. Push to a new repo.
2. Actions → **Create keystore (run once)** → Run workflow. Download the artifact.
3. Add four secrets (Settings → Secrets and variables → Actions):

   | Secret | Value |
   | --- | --- |
   | `KEYSTORE_B64` | the single line inside `KEYSTORE_B64.txt` |
   | `KEYSTORE_PASSWORD` | the password you chose |
   | `KEY_PASSWORD` | the same password |
   | `KEY_ALIAS` | the alias you chose |

4. Delete the artifact. Keep your own copy of `release.keystore` forever.
5. Actions → **Build APK** → Run workflow. Download `gitfile-apk`.

Tag a commit `v1.0.0` and the APK is attached to a GitHub Release instead —
much easier to install from a phone.

## Using it

Sign in with a **fine-grained personal access token**
([github.com/settings/tokens](https://github.com/settings/tokens?type=beta)):

- **Contents** — Read and write
- **Pull requests** — Read and write
- **Metadata** — Read

The token is stored on the device and goes to `api.github.com` and nowhere else.

## Changing the app

Edit `app/src/main/assets/index.html` and push. The next build picks it up.
`versionCode` comes from the Actions run number, so every build installs cleanly
over the last one.

## Limits

One file per commit, text only, nothing over 1 MB. Multi-file commits need the
Git Data API — blobs → tree → commit → ref — which isn't wired up yet.
