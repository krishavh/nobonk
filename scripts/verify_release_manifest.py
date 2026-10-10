#!/usr/bin/env python3
"""Check the merged public-release manifest, including permissions added by libraries."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
    "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
)
root = ET.parse(path).getroot()
android = "{http://schemas.android.com/apk/res/android}"
permissions = {node.get(android + "name") for node in root.findall("uses-permission")}
forbidden = {
    "android.permission.ACTIVITY_RECOGNITION",
    "android.permission.FOREGROUND_SERVICE_HEALTH",
    "android.permission.INTERNET",
}
assert not permissions & forbidden, f"Unexpected public permissions: {permissions & forbidden}"
app = root.find("application")
assert app is not None
assert app.get(android + "debuggable", "false") == "false", "Public app is debuggable"
service = next(node for node in app.findall("service")
               if node.get(android + "name", "").endswith(".DetectionService"))
assert service.get(android + "foregroundServiceType") == "camera", "Expected manual camera service only"
assert service.get(android + "exported") == "false", "Detection service must stay private"
print("PASS: public release uses a private camera service; no walking, health or internet permissions")
