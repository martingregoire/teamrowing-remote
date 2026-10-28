# Changelog

All notable changes to this app are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the app uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) as described in the README's
[Releasing](README.md#releasing) section.

## [Unreleased]

## [1.1.1] - 2026-10-28

It works with teamrowing-display 1.0.0 and later.

### Changed

- An own launcher icon: a play button in front of the tempo, in place of the default Android
  icon.

## [1.1.0] - 2026-10-28

It works with teamrowing-display 1.0.0 and later. The next track without internet access on the tablet
needs both apps in version 1.1.0.

### Added

- Sends Spotify's queue to the display app, without the autoplay tracks after the session, so it
  shows the next track without internet access on the tablet. Older display apps ignore it.

### Changed

- The app's version comes from the release tag, and builds after a release show the number of
  commits since then and the commit, e.g. 1.0.0-3-gabc1234.
- The session list keeps the current track second, after the previous one, instead of in the
  middle, so it shows more of the following tracks.
- The current track's title takes one line, shortened with "…", so the controls don't move down
  for long titles.

## [1.0.0] - 2026-10-26

The first version. It works with teamrowing-display 1.0.0.

### Added

- Remote control for the Spotify app on the same phone (Spotify App Remote SDK): the current
  track with previous, play/pause and next, and a progress bar to seek.
- Sessions: the trainer's own Spotify playlists, with the chosen one remembered and a playlist
  started in the Spotify app picked up automatically.
- Session list with each track's tempo and duration, the current track kept in the middle; tapping
  a track plays the session from there.
- Each track's tempo, synced from teamrowing-display over Bluetooth, and tempo correction in a
  bottom sheet, synced back to the display app.
- Playback changes are sent to the display app over Bluetooth, so it shows them right away.
- The screen stays on while the app is open.

[Unreleased]: https://github.com/martingregoire/teamrowing-remote/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/martingregoire/teamrowing-remote/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/martingregoire/teamrowing-remote/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/martingregoire/teamrowing-remote/releases/tag/v1.0.0
