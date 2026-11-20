# Mio Player design audit

The Stage 12 interface routed library browsing, settings and Now Playing through the same Compose shell. Settings were a long flat list. The classic player inherited cassette hardware colors and labels, so it lacked a clear modern identity. Queue and signal details competed for space in the same sheet. The library already supported paged tracks and albums, folders, favorites, playlists and track search. The data layer had no separate artist browser or user-selectable sort order.

## Design system

Mio uses graphite surfaces, warm brass actions and cool secondary text in dark mode. The light palette uses warm paper and a darker bronze accent. Material 3 supplies control states, dialogs and sheets. The hierarchy uses Android's system typeface, with 28–32 sp for titles, 16 sp for primary metadata and 14 sp for supporting text. A 4 dp spacing base scales to 12 dp group gaps and 20–24 dp screen gutters. Buttons and list actions retain at least 48 dp targets. Artwork uses 12 dp corners and includes a neutral fallback.

Library browsing retains the paging flows and existing actions. Modern Now Playing uses an artwork-first layout and switches to two columns on wide windows. Queue and Audio Lab open independently. Audio Lab labels the source, decoder, DSP and AudioTrack observations separately. Settings expose six sections and accept direct navigation from update notifications.

## Migration

Mio Player replaces the public app label and artwork. PlaybackService and Media3 continue to own playback. Database names, preference keys, saved display IDs, playback position and file permissions keep their prior formats. An unset presentation preference now opens Modern. Saved cassette and vinyl preferences remain readable. The charcoal-and-brass Mio finish is the default when no retro palette has been saved. The cassette landscape lock continues to apply only while the cassette player is on screen.

Version 110 / 1.5.0 follows production version 108. Version 109 was used by the Stage 12 OTA test probe. The application ID, OTA URL policy and production certificate are unchanged. The name was checked in a preliminary public web search. Mio previously used MioPlayer for a media player on its DigiWalker devices. This is not trademark clearance.


## 1.5.0 changelog

- Revised navigation and library metadata with a branded launcher and splash.
- Added an artwork-first modern player with separate queue and Audio Lab sheets.
- Grouped existing settings under Appearance, Playback, Audio, Library, Updates and About.
- Retained the retro display IDs and persisted colors with original palette names.

## Studio Edition previews

These PNGs are captured from the native Compose views with Robolectric API 35 and native Skia. The viewport sizes are 390 × 844 dp for dark and 360 × 800 dp for light, following the viewport sizes in the implementation request. Fixture tracks, signal values and drawn cover artwork live in the test only. The captures omit system status icons and phone bezels.

| Screen | Dark | Light |
| --- | --- | --- |
| Modern Now Playing | [Preview](mio-previews/now-playing-dark.png) | [Preview](mio-previews/now-playing-light.png) |
| Music Library | [Preview](mio-previews/library-dark.png) | [Preview](mio-previews/library-light.png) |
| Audio Lab | [Preview](mio-previews/audio-lab-dark.png) | [Preview](mio-previews/audio-lab-light.png) |
| Settings | [Preview](mio-previews/settings-dark.png) | [Preview](mio-previews/settings-light.png) |
| Retro Cassette | [Preview](mio-previews/retro-cassette.png) | |
| Playback Queue | [Preview](mio-previews/queue-dark.png) | |
| Equalizer | [Preview](mio-previews/equalizer-dark.png) | |

### Visual comparison

- **Now Playing.** The cover is wider, the source badge comes from reported PCM depth and sample rate, and the transport uses a larger outlined play key. The dark palette uses graphite and champagne gold. After the first capture, the lower panel grew on tall phones to reduce the empty band below the format strip. Short windows can scroll.
- **Library and Settings.** The album grid keeps the board’s square artwork and compact title stack. The mini-player now sits inside a thin rounded outline above the four destinations. Artist search remains in Tracks. Settings retains the existing OTA and About entries and their deep links.
- **Audio Lab and Equalizer.** The signal path shows source, decoder, DSP and observed Android output. The EQ plot draws actual configured gains for all ten bands. Presets update those gain values, while the disclosure opens the existing band and advanced controls. Its header switch controls Source Direct.
- **Cassette.** The embossed keys remain interactive and the reels still rotate in playback. The Studio finish adds a brushed face, brass C-60 label, perforated panel and lit play key. Its segmented display shows elapsed track progress. The board’s stereo level meters have no available per-channel signal source. Stop pauses and returns seekable audio to its start.
- **Queue.** Album art comes from the library and the current row is highlighted. The right-side action removes a song from the queue. Tap a row to play it. The board’s reorder handles are omitted because there is no reorder gesture in the existing queue.
- **Capture differences.** Fixture art is drawn in the test harness, the device bezel and status icons are absent, and copy uses the app’s existing localization. The captures are generated by host Compose/Skia. Device and hardware playback require their own validation.

Regenerate all eleven captures with `./gradlew :app:testDebugUnitTest --tests '*MioScreensTest'`. PNGs are written to `app/build/reports/mio-previews`.

## UI Kit v2 comparisons

The owner-supplied Developer Handoff v2 defines Now Playing, Library and Retro. The separate Cassette Sprite Kit supplies the ten production layers. Its `reference_original.png` is byte-identical to the v2 Retro reference. The comparison tool fits each reference without stretching and places it on the left. The captured Compose surface is on the right.

| Screen | Actual Compose · 390 × 844 dp | Actual Compose · 360 × 800 dp | Side by side | 50% overlay | Difference |
| --- | --- | --- | --- | --- | --- |
| Now Playing | [PNG](mio-ui-kit-v2/now-playing/actual.png) | [PNG](mio-ui-kit-v2/now-playing/compact.png) | [PNG](mio-ui-kit-v2/now-playing/side_by_side.png) | [PNG](mio-ui-kit-v2/now-playing/overlay_50pct.png) | [PNG](mio-ui-kit-v2/now-playing/difference.png) |
| Music Library | [PNG](mio-ui-kit-v2/library/actual.png) | [PNG](mio-ui-kit-v2/library/compact.png) | [PNG](mio-ui-kit-v2/library/side_by_side.png) | [PNG](mio-ui-kit-v2/library/overlay_50pct.png) | [PNG](mio-ui-kit-v2/library/difference.png) |
| Retro Cassette | [PNG](mio-ui-kit-v2/retro/actual.png) | [PNG](mio-ui-kit-v2/retro/compact.png) | [PNG](mio-ui-kit-v2/retro/side_by_side.png) | [PNG](mio-ui-kit-v2/retro/overlay_50pct.png) | [PNG](mio-ui-kit-v2/retro/difference.png) |

- **Now Playing.** Product header and favorite moved into their reference positions. The artwork dominates the upper half, with an inset source-derived badge, serif metadata, and the fine gold footer. The screenshots use drawn test covers, and system serif rendering differs from the mockup.
- **Music Library.** Tightened the spacing after comparing the first render and adjusted the aspect ratio of the later artwork rows. The product preserves its four functional categories and media actions. The reference shows an extra Artists category and per-album menus. Artist search is available in Tracks.
- **Retro.** The screen uses the manifest's uniform scale and layer coordinates. The 941 × 1672 artwork is centered on tall phones, so there is breathing room above and below the case. The Studio portrait deck uses repaired housing, separate tape bed, independently transformed reels, frame and key PNGs. Compose renders the header, track label, elapsed pattern, seek, transport state and utility controls. The cassette label uses system italic rather than handwriting. Rotating shadows and some highlights come from the extracted sprites, and the glass and texture repairs are approximations. The other palettes, landscape cassette and vinyl use their existing layouts.
- **Texture repairs.** Removed the baked header from the plate and repaired the areas around the utility glyphs and display clock by feathering adjacent brushed texture. Cleaned the gold key so its icon can change with play state and replaced clipped window lettering with live text. Added a restrained glass reflection over the reels. No reference artwork or sample metadata is used in the running app.
- **Evidence.** These are native Skia host captures of Compose. They omit Android system bars and are not measurements of physical-device rendering, GPU memory, or hardware playback.

## Final UI Kit v2 Visual Polish

The first pass was reviewed against the supplied side-by-sides. Now Playing needed a more continuous graphite surface and a tighter lower stack. Library needed less typographic weight and a quieter mini-player. Retro needed deeper tape winding, lighter script and less empty space around the deck.

| Screen | Before | Final Compose | Compact Compose | Final comparisons |
| --- | --- | --- | --- | --- |
| Now Playing | [Before](mio-ui-kit-v2/now-playing/actual.png) | [390 × 844](mio-ui-kit-v2/final-polish/now-playing/actual.png) | [360 × 800](mio-ui-kit-v2/final-polish/now-playing/compact.png) | [Side by side](mio-ui-kit-v2/final-polish/now-playing/side_by_side.png) · [Overlay](mio-ui-kit-v2/final-polish/now-playing/overlay_50pct.png) · [Difference](mio-ui-kit-v2/final-polish/now-playing/difference.png) · [Geometry](mio-ui-kit-v2/final-polish/now-playing/geometry.png) |
| Music Library | [Before](mio-ui-kit-v2/library/actual.png) | [390 × 844](mio-ui-kit-v2/final-polish/library/actual.png) | [360 × 800](mio-ui-kit-v2/final-polish/library/compact.png) | [Side by side](mio-ui-kit-v2/final-polish/library/side_by_side.png) · [Overlay](mio-ui-kit-v2/final-polish/library/overlay_50pct.png) · [Difference](mio-ui-kit-v2/final-polish/library/difference.png) · [Geometry](mio-ui-kit-v2/final-polish/library/geometry.png) |
| Retro Cassette | [Before](mio-ui-kit-v2/retro/actual.png) | [390 × 844](mio-ui-kit-v2/final-polish/retro/actual.png) | [360 × 800](mio-ui-kit-v2/final-polish/retro/compact.png) | [Side by side](mio-ui-kit-v2/final-polish/retro/side_by_side.png) · [Overlay](mio-ui-kit-v2/final-polish/retro/overlay_50pct.png) · [Difference](mio-ui-kit-v2/final-polish/retro/difference.png) · [Geometry](mio-ui-kit-v2/final-polish/retro/geometry.png) |

- **Now Playing** uses two cached graphite reflections, a lighter editorial title, clearer artist contrast and a shorter lower stack. The finer gold strands are cached decorative paths and have no signal input.
- **Library** uses square covers throughout, regular-weight serif titles, and a slightly shorter mini-player and navigation bar. The handoff's second and third cover rows are rectangular. The square treatment therefore changes their crop and row geometry. The Artists tab and album overflow shown in the reference have no corresponding actions in this UI.
- **Retro** uses the existing ten PNG layers and the same independently transformed reel centers. The reel sprites are 5.4% larger, with deeper winding texture behind them and less reflection on the glass. A 1.8% uniform scale trims only the outer background and moves the deck slightly up on tall phones. The platform cursive face and system glyph fallback replace the heavy italic title. The right-side display shows elapsed position. Baked shadows and the reference's hand lettering remain differences.

The 390 × 844 and 360 × 800 captures show the same dark theme. Now Playing and Library use cropped artwork and metadata from the supplied reference in test resources only. Badge marks were removed from the crops so the actual Now Playing badge is composed separately. The source reference files were left intact, and actual PNGs are copied byte-for-byte from the Compose reports. The geometry diagrams compare captured Compose layout bounds with fitted reference regions independently of artwork. Some bounds include touch areas. Difference images are diagnostic and do not provide a visual fidelity score.

Reproduce with `./gradlew :app:testDebugUnitTest --tests '*MioScreensTest'`, then run `python docs/mio-ui-kit-v2/compare-polish.py HANDOFF app/build/reports/mio-previews docs/mio-ui-kit-v2/final-polish`. These are native Skia host captures. Physical-device type rendering, GPU memory and animation frame pacing were outside this validation.

Validation passed for all 312 host tests (100 core, 43 playback, 44 library, 125 app), including the Compose captures, compact text scaling, motion and cassette actions. Four signing-policy tests, native FIR host checks, debug assembly and release lint for app, library and playback passed. The debug APK metadata reports `com.walkmanstudio.nativeplayer`, version 1.5.0 and versionCode 110. The compiled production OTA certificate pin was verified.
