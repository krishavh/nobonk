# Hosted WebKit regression tests

`BrowserIntegrationTests` exercises a real WKWebView/content process and the production `BrowserModel` using only local HTML and a generated PCM/WAV data URI. No remote media, website account, downloaded fixture or server is needed. The HTML has a restrictive content-security policy allowing inline test scripts and `data:` media only. `https://nobonk.invalid/fixture/` is a base origin, not a requested network page.

The tests cover an empty/no-navigation initial browser, actual JavaScript navigation cancellation for a custom app scheme and insecure HTTP, a custom-scheme popup that returns no window, and suspension after real media playback has begun. The media test proves the clock advances first, demonstrates that ordinary WebKit pause can be restarted by script as a positive control, then uses production `pause()` and checks that script playback cannot restart until `resume()` and an explicit play action. An additional same-origin history test drives actual WebKit URL/KVO changes while the address field is being edited and verifies that the draft is preserved. It does not relax the production autoplay configuration or replace navigation policy with a fake.

The camera/microphone case only counts as permission coverage if the local page is a secure nonopaque origin and real WebKit reaches its permission delegate. A small observing delegate forwards the decision to the production implementation unchanged and records that decision. Missing simulator capture hardware, an unsupported local secure context, or a pre-delegate WebKit refusal produces a **skip**, not a false permission-policy pass. It never grants capture permission; a real website/physical-device check remains necessary if this case skips.

Example on an independently created simulator (never replace an already-running test owner's device):

```sh
xcodegen generate --spec ios/project.yml
xcodebuild -project ios/NoBonk.xcodeproj -scheme NoBonk \
  -configuration Debug -destination 'platform=iOS Simulator,id=YOUR_DEDICATED_SIMULATOR' \
  -derivedDataPath /tmp/nobonk-webkit-tests \
  -resultBundlePath /tmp/nobonk-webkit-tests.xcresult \
  -parallel-testing-enabled NO \
  -only-testing:NoBonkQuickAccessTests/BrowserIntegrationTests \
  CODE_SIGNING_ALLOWED=NO test
```

No Fast model needs copying for these browser tests. The app's default explicit Start/safety gates leave camera scanning off in the hosted test. These tests do not establish compatibility with particular video sites, DRM, actual audio routes, fullscreen behavior or every privacy behavior of WebKit's network process.

September 7 validation: all six tests passed with zero skips on a dedicated iPhone 16 / iOS 26.5 simulator. This included an observed real WebKit permission callback forwarded to the production denial implementation, the ordinary-pause positive control and the persistent-suspension regression. Generic ARM64 simulator `build-for-testing` also succeeded. No physical phone or pre-existing simulator was used; the dedicated test device is shut down.
