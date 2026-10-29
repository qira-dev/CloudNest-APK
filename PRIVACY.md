# Privacy Policy — CloudNest

_Last updated: 2026-10-30_

**Short version:** CloudNest has no server of its own. Your files go only to the
cloud accounts **you** connect, and all metadata stays on your phone.

## What the app does

CloudNest is a personal archive app. It splits files into chunks and stores those
chunks in cloud storage accounts that **you** connect (Google Drive, Dropbox,
OneDrive, pCloud). All uploads and downloads happen directly between your phone
and the provider you chose. We operate no server and never receive a copy of your
files.

## Data the app stores (on your device only)

- **App metadata** (file names, sizes, chunk locations, settings) — stored in an
  app-private database on your phone.
- **OAuth access/refresh tokens** for the cloud accounts you connect — encrypted
  with Android Keystore (AES-256-GCM, non-exportable key) and stored only on your
  device. The app never transmits your tokens anywhere except to the provider
  that issued them.

## Data the app does NOT collect

- We do not collect, transmit, sell or analyze your files, file contents, account
  credentials or usage data. There is no analytics SDK in the app.

## Ads

The app shows ads served by **Google AdMob**. Google may collect and process
certain device and usage data to serve and measure ads, as described in Google's
own policies:

- Google Privacy Policy: https://policies.google.com/privacy
- Google AdMob and data: https://support.google.com/admob/answer/6128543

On first launch the app asks for your consent choice for personalized ads
(Google UMP consent flow) where required by law. Ads are never shown on the
unlock, settings or diagnostics screens.

## Permissions

- **Internet** — to upload/download chunks to the cloud providers you connected.
- **Notifications** — to show upload/download progress and failure notices.
- **Storage access (SAF)** — only when you pick files to upload or choose where
  to save a download. The app reads only what you pick.

## Account disconnection and deletion

Disconnecting a cloud account removes the stored tokens from your phone
immediately. Uninstalling the app removes all app metadata from your phone. Files
already stored in your cloud accounts remain in those accounts under your
control. The app includes a manual backup/restore feature that exports your
database to a file you choose — that file is under your control alone.

## Children

The app is not directed at children under 13.

## Contact

Questions about this policy: open an issue at
https://github.com/qira-dev/CloudNest-APK or contact the app owner (qira-dev).
