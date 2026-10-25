# Principal's Office: Android app

A full-screen Android app that opens the Principal's Office notice board (hosted from the separate `principals-office` repo) with no address bar. The page address is never shown: pop-ups are titled "Principal's Office" and sit at the top of the screen, and the offline screen shows no address.

| Feature | How it works |
|---|---|
| Logo and splash | Launcher icon and splash screen are made from `tools/logo.png` (`python3 tools/make_icons.py` rebuilds them). The splash stays up until the page has loaded. |
| Downloads from any site | Normal links go through Android's download manager (cookies and referer are passed on, so logged-in downloads work). `blob:` and `data:` downloads are saved straight to Downloads. |
| Uploads | File inputs open the Android picker and offer the camera for picture fields. |
| Camera and microphone | Any page can ask (live classes). Android's permission prompt is shown, and the page gets access only if the student allows it. |
| Other links | `tel:`, `mailto:`, WhatsApp, UPI and similar links open the matching app. |
| Back button | Goes back in the page; closes the app from the first page. |

## Build it on GitHub

1. Push this repo to GitHub.
2. Tell it which page to open: **Settings > Secrets and variables > Actions > Variables > New repository variable**, name `APP_URL`, value the address of your hosted page, e.g. `https://<your-user>.github.io/principals-office/`.
3. Open **Actions > Build Android app > Run workflow**. You can also fill in **app_url** there instead of using the variable.
4. When it finishes, get the APK from either place:
   - **Releases > Principal's Office app > PrincipalsOffice.apk** (a stable link you can share with students).
   - the run's **Artifacts > PrincipalsOffice-apk**.

Students install the APK by opening it and allowing "install unknown apps" for their browser or file manager.

## Updating

- Notices, forms and header text change in the Google Sheet. The app picks them up on the next open.
- Changes to `index.html` (in the other repo) go live as soon as GitHub Pages republishes. The app does not need rebuilding.
- Rebuild the app only when this repo changes (icon, app code) or the page address changes. The workflow runs on every push to `main`, or by hand.

## Signing key (optional)

Builds are signed with the key in `app/debug.keystore`, so each new build installs over the previous one. To use your own key instead, create it once:

```
keytool -genkeypair -v -keystore release.jks -alias office -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks
```

Then add these repository secrets: `KEYSTORE_BASE64` (the base64 text), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`office`) and `KEY_PASSWORD`. Keep `release.jks` safe: apps signed with a different key cannot be updated in place. Switching from the bundled key to your own key needs a one-time uninstall.

## Project layout

```
app/src/main/java/com/deeksha/principalsoffice/MainActivity.java   all app code
app/src/main/res/                                                  icons, splash, layout, themes
app/debug.keystore                                                 bundled signing key
tools/logo.png, tools/make_icons.py                                logo and icon generator
.github/workflows/build-apk.yml                                    builds the APK
```
