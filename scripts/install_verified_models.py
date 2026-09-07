#!/usr/bin/env python3
"""Install the exact released ONNX assets, validating bundle and model SHA-256."""
import argparse
import hashlib
from pathlib import Path
import tempfile
import urllib.request
import zipfile

BUNDLE_URL = "https://github.com/krishavh/nobonk/releases/download/v1.0.10-rc-ac1b822/nobonk-1.0.10-vc11-ac1b822-release-unsigned.aab"
BUNDLE_SHA = "84a161eac4f413136f346cb6e684133e5097bd756848d9ece0cf8d162af3cc60"
MODELS = {
    "yolo26n_416.onnx": "9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d",
    "yolo26s_416.onnx": "706b34041df74890c4d5720b26e47ced3d825a3b7dd1bad6cf73fcdcb7740356",
}


def digest(path):
    with path.open("rb") as source:
        checksum = hashlib.sha256()
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
        return checksum.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, help="Use an already downloaded release bundle")
    args = parser.parse_args()
    assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
    if all((assets / name).is_file() and digest(assets / name) == sha for name, sha in MODELS.items()):
        print("Both model assets already match the verified release.")
        return
    with tempfile.TemporaryDirectory(prefix="nobonk-models-") as temporary:
        bundle = args.bundle or Path(temporary) / "release.aab"
        if args.bundle is None:
            print("Downloading the pinned public NoBonk release bundle…")
            with urllib.request.urlopen(BUNDLE_URL, timeout=120) as response, bundle.open("wb") as target:
                import shutil
                shutil.copyfileobj(response, target)
        if digest(bundle) != BUNDLE_SHA:
            raise SystemExit("Bundle checksum mismatch; no model assets were changed.")
        staged = []
        with zipfile.ZipFile(bundle) as archive:
            for name, sha in MODELS.items():
                data = archive.read("base/assets/" + name)
                if hashlib.sha256(data).hexdigest() != sha:
                    raise SystemExit(f"Model checksum mismatch: {name}; no assets were changed.")
                staged.append((name, data))
        assets.mkdir(parents=True, exist_ok=True)
        for name, data in staged:
            target = assets / name
            temporary_asset = target.with_suffix(".onnx.tmp")
            temporary_asset.write_bytes(data)
            temporary_asset.replace(target)
            print(f"Verified and installed {name}")


if __name__ == "__main__":
    main()
