# Quick access to NoBonk on iPhone

NoBonk now provides **Open NoBonk** as an App Shortcut (iOS 17+) and a WidgetKit control (iOS 18+). These bring the normal app into the foreground. They do not start a camera session, acknowledge a warning, or claim that scanning is active while the app is closed.

## Set it up after installing

- **Siri or Shortcuts:** Open Shortcuts and look for NoBonk under App Shortcuts. Run **Open NoBonk**, or ask Siri to open NoBonk. To create a Home Screen shortcut, add the NoBonk action to a shortcut, open its details and choose **Add to Home Screen**.
- **Action button, on supported iPhones:** Settings → Action Button → Shortcut → Choose a Shortcut → **Open NoBonk**. Press and hold the button to open setup.
- **Control Center, iOS 18+:** Open Control Center, touch and hold to edit, choose **Add a Control**, search for NoBonk and choose **Open NoBonk**.
- **Lock Screen, iOS 18+:** Touch and hold the Lock Screen, choose Customize → Lock Screen, remove one of the bottom controls and add **Open NoBonk**. iOS requires local device authentication before running this intent.

The controls use a simple system viewfinder glyph because Control Center renders its own monochrome controls. The app and Home Screen retain the corrected NoBonk brand icon. No new Blender model is needed for the system control.

After opening NoBonk, complete the existing safety notice/reminder and tap **Start scanning**. This build stops scanning when it leaves the foreground. No hidden camera, recording extension, notification permission, App Group, server, subscription, or paid SDK was added. This work does not attempt a VoIP or Picture in Picture camera design.

## Implementation and safety boundary

`QuickAccess/OpenNoBonkIntent.swift` belongs to both the app and controls extension, as Apple requires for a control that opens its containing app. It conforms to `OpenIntent`, targets only **Safety and setup**, requires local device authentication, and returns an empty result. It has no camera dependency, notification, deep-link route, or preference write. Foreground presentation comes from `OpenIntent`; the normal root view remains responsible for acknowledgment and explicit Start.

`NoBonkShortcuts` registers the preconfigured shortcut from app initialization. `NoBonkControls` is a separate WidgetKit extension with minimum iOS 18; the containing app stays compatible with iOS 17. `project.yml` generates the checked-in Xcode project, including extension embedding and the focused test target. The controls extension has its own bundle identifier (`ai.genwhy.nobonk.controls`) and will need normal provisioning when the app is signed for a device or distributed. No provisioning purchase is part of this change.

## Verification

On September 7, 2026, the full simulator test build and generic iPhone device build succeeded, including both App Intents metadata exports and the embedded extension. All three hosted quick-access tests passed on the iPhone 17 Pro / iOS 26.5 simulator. The simulator test host reported a `linkd` helper connection warning when updating App Shortcut parameters, so actual system indexing and invocation are not claimed as verified.

Run the hosted intent tests using an installed iPhone simulator:

```sh
xcodebuild -project NoBonk.xcodeproj -scheme NoBonk \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \
  CODE_SIGNING_ALLOWED=NO test
```

The tests call the actual intent and verify that first-use acknowledgment is not written, a returning user's reminder stays pending, the destination is setup, and foreground/local-authentication metadata are enabled. Existing core safety tests remain separate in `swift test`.

Simulator and generic-device builds must include both the app and embedded controls extension and successfully export App Intents metadata. Compilation and direct intent tests do not prove Siri recognition, gallery indexing, Lock Screen authentication or physical Action button behavior; verify these on the signed phone. Also verify returning from another app never starts scanning automatically and the permission-denied state remains recoverable.

## Apple references

- [Creating controls to perform actions across the system](https://developer.apple.com/documentation/widgetkit/creating-controls-to-perform-actions-across-the-system)
- [OpenIntent](https://developer.apple.com/documentation/appintents/openintent)
- [AppShortcutsProvider](https://developer.apple.com/documentation/appintents/appshortcutsprovider)
- [Run shortcuts with the Action button](https://support.apple.com/guide/shortcuts/run-shortcuts-with-the-action-button-apdfea15680b/ios)
- [Add a shortcut to the Home Screen](https://support.apple.com/guide/shortcuts/apd735880972/ios)
- [Customize the Lock Screen](https://support.apple.com/guide/iphone/iph4d0e6c351/ios)
