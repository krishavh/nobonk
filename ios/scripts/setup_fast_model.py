#!/usr/bin/env python3
"""Copy only the exact approved local Fast graph. No network or SDK downloads."""
import argparse
import hashlib
from pathlib import Path

EXPECTED = "9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Existing local yolo26n_416.onnx")
    args = parser.parse_args()
    data = args.source.read_bytes()
    if hashlib.sha256(data).hexdigest() != EXPECTED:
        raise SystemExit("Checksum mismatch: nothing copied. Do not substitute another checkpoint.")
    target = Path(__file__).resolve().parents[1] / "NoBonk" / "Models" / "yolo26n_416.onnx"
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".onnx.tmp")
    temporary.write_bytes(data)
    temporary.replace(target)
    print(f"Verified {EXPECTED}; copied {len(data)} bytes to {target}")

if __name__ == "__main__":
    main()
