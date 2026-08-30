# Privacy Policy

EXIF Remove (`si.jakobkreft.exifremove`), by Jakob Kreft.
Last updated 30 August 2026.

## What is collected

Nothing. There is no analytics, crash reporting, advertising, tracking or any
third-party SDK. The developer receives no information about you, your device or
your files.

## Network

The app does not request the `INTERNET` permission, so Android denies it network
access at the system level. It cannot upload your photos or reach a server, even
by accident. You can check this in the manifest or in the published APK.

## Permissions

* `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (Android 13+): open the files you pick.
* `READ_EXTERNAL_STORAGE` (Android 12 and older): the same on older versions.
* `ACCESS_MEDIA_LOCATION`: Android hides GPS coordinates in photos from apps that
  lack it. The app has to see them in order to delete them. They are never
  stored, logged or sent anywhere.

An internal permission ending in `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is
added automatically by AndroidX and grants nothing outside the app.

## Files

Originals are never changed. Cleaned copies are written to the app's private
cache and deleted after an hour. Saving to the gallery writes to
`Pictures/EXIF Remove` or `Movies/EXIF Remove`. Settings and templates stay on
the device.

Sharing a cleaned file hands it to the app you choose. What that app does with it
is covered by its own privacy policy.

## Control

You choose the files, the rules and where the results go. Permissions can be
revoked in Android settings. Without `ACCESS_MEDIA_LOCATION` the app still works,
but Android gives it location-redacted copies, so it can no longer show or remove
the original coordinates. Uninstalling deletes the cache, settings and templates.

No data is collected from anyone, including children under 13.

## Source

<https://github.com/jakobkreft/exif-remove>, licensed GPL-3.0-or-later. Builds
are reproducible: anyone can rebuild the published source and confirm it matches
the distributed app.

## Changes

Any update is published here with a new date. The history is in the repository's
commit log.

## Contact

<https://github.com/jakobkreft/exif-remove/issues>
