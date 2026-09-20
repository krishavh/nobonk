# Walking reminder — experimental Android

Walking reminders are optional and off by default. Enable motion access and notifications, then tap **Remind me on my next walk** while NoBonk is visible. This arms one reminder for up to 30 minutes. It does not start the camera or detection model in the service.

After at least 16 fresh step-detector events spanning 20 seconds (no gap over 3.5 seconds), NoBonk posts **Walking detected · Turn on NoBonk?** exactly once and ends motion monitoring. Open NoBonk to choose **Start scanning** or **Not now**. Opening the notification, acknowledging the safety notice, rotating the screen, ignoring the notification, or dismissing it never counts as permission to start scanning. A fresh reminder requires explicit rearming. There is no automatic camera start, pause/resume cycle, process-death restart, or reboot restart.

Motion stays on the phone; there is no location inference or distinction between indoor and outdoor walking. Delivery can depend on phone power management and sensor availability. Keep the screen on for reliable step delivery. Stop or opening NoBonk while waiting ends the monitoring session. The ordinary manual scanning controls remain available.

Android 14+ uses the health foreground-service type for step monitoring, with ACTIVITY_RECOGNITION; it does not use the camera service type while waiting. Explicit scanning uses the camera type. The new FOREGROUND_SERVICE_HEALTH declaration must be reflected accurately in Google Play before releasing this change. The reminder channel is independently controllable in Android notification settings. Notification volume and Do Not Disturb remain under Android's control.

Regression coverage includes lifecycle denial of model/camera work after walking qualification; repeated/late callbacks and Stop dominance; real service one-shot notification, dismissal and expiry; cold/warm reminder entry and rotation; and manual background camera/notification Stop. Emulator motion is synthetic; physical step-sensor delivery still needs phone validation.
