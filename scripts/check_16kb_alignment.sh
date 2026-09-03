#!/usr/bin/env bash
# check_16kb_alignment.sh — verify every bundled native .so is 16 KB-aligned.
#
# WHY: Google Play requires all apps targeting Android 15+ that ship native code
# to support 16 KB memory pages for new submissions/updates from Nov 1, 2025.
# An unaligned .so fails at load on 16 KB-page devices. NoBonk bundles native
# libs from ONNX Runtime and CameraX, so this must pass before uploading.
#
# USAGE:
#   1) Build the release artifact:   ./gradlew bundleRelease   (or assembleRelease)
#   2) Run:  scripts/check_16kb_alignment.sh path/to/app-release.aab
#            scripts/check_16kb_alignment.sh path/to/app-release.apk
#      With no arg it auto-discovers the newest .aab/.apk under app/build/outputs.
#
# Requires: unzip, and ONE of: llvm-readelf / readelf / objdump (from the NDK or
# your distro's binutils). Exit code 0 = all aligned, 1 = a violation was found.
set -euo pipefail

ALIGN_OK=0
ALIGN_BAD=0

find_tool() {
  for t in "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/"*/bin/llvm-readelf \
           llvm-readelf readelf objdump; do
    if command -v "$t" >/dev/null 2>&1 || [ -x "$t" ]; then echo "$t"; return 0; fi
  done
  return 1
}

ARTIFACT="${1:-}"
if [ -z "$ARTIFACT" ]; then
  ARTIFACT="$(ls -t app/build/outputs/bundle/*/*.aab app/build/outputs/apk/*/*.apk 2>/dev/null | head -n1 || true)"
fi
if [ -z "$ARTIFACT" ] || [ ! -f "$ARTIFACT" ]; then
  echo "ERROR: no .aab/.apk found. Build first (./gradlew bundleRelease) or pass a path." >&2
  exit 2
fi

TOOL="$(find_tool || true)"
if [ -z "$TOOL" ]; then
  echo "ERROR: need llvm-readelf, readelf, or objdump on PATH (install NDK or binutils)." >&2
  exit 2
fi

echo "Artifact : $ARTIFACT"
echo "ELF tool : $TOOL"
echo

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$ARTIFACT" -d "$TMP"

# .so live under lib/<abi>/ in APKs and base/lib/<abi>/ (etc.) in AABs.
mapfile -t SOS < <(find "$TMP" -type f -name '*.so' | sort)
if [ "${#SOS[@]}" -eq 0 ]; then
  echo "No .so files bundled — nothing to align. PASS."
  exit 0
fi

check_one() {
  local so="$1" name; name="$(basename "$so")"
  local ok=1
  case "$TOOL" in
    *readelf)
      # Every LOAD segment's Align (last hex field on the line) must be >= 0x4000.
      # awk exits 1 the moment it sees an under-aligned LOAD segment.
      # NOTE: uses a portable hex parser (NOT gawk's strtonum) so the check is truthful
      # under mawk too — on a mawk-only box strtonum is undefined and every segment was
      # misread as unaligned (false FAIL).
      if "$TOOL" -lW "$so" 2>/dev/null | awk '
          function hex2dec(s,   n,i,c,d) {
            s = tolower(s); n = 0
            for (i = 1; i <= length(s); i++) {
              d = index("0123456789abcdef", substr(s,i,1)) - 1
              if (d >= 0) n = n*16 + d
            }
            return n
          }
          $1=="LOAD" {
            a = $NF; sub(/^0x/,"",a)
            if (hex2dec(a) < 16384) exit 1
          }
        '; then ok=1; else ok=0; fi
      ;;
    *objdump)
      # objdump -p prints "align 2**N" per LOAD; N must be >= 14 (2^14 = 16 KB).
      if "$TOOL" -p "$so" 2>/dev/null | grep -E 'LOAD' | grep -oE 'align 2\*\*[0-9]+' \
           | awk -F'\\*\\*' '{ if ($2 < 14) exit 1 }'; then ok=1; else ok=0; fi
      ;;
  esac
  if [ "$ok" -eq 1 ]; then
    printf '  ALIGNED   %s\n' "$name"; ALIGN_OK=$((ALIGN_OK+1))
  else
    printf '  UNALIGNED %s   <-- FAILS 16 KB requirement\n' "$name"; ALIGN_BAD=$((ALIGN_BAD+1))
  fi
}

echo "Native libraries:"
for so in "${SOS[@]}"; do check_one "$so"; done
echo
echo "Aligned: $ALIGN_OK   Unaligned: $ALIGN_BAD"

if [ "$ALIGN_BAD" -gt 0 ]; then
  echo "RESULT: FAIL — bump the offending dependency (e.g. ONNX Runtime to the"
  echo "latest 1.2x, which ships 16 KB-aligned libs) and rebuild. Ensure"
  echo "packaging.jniLibs.useLegacyPackaging=false and no extractNativeLibs=true."
  exit 1
fi
echo "RESULT: PASS — all bundled .so are 16 KB-aligned."
