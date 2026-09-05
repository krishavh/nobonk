#!/usr/bin/env python3
"""glm_kotlin.py — improve ONE Kotlin file via the GLM-5.3 CODING LANE.

Hardening pass, not a rewrite of behavior: robustness, edge cases, KDoc,
small clean-ups. The caller gates with the real test suite; this script only
does the generation + basic sanity.

Usage: glm_kotlin.py <file.kt> <pass>
Exit: 0 wrote candidate · 3 invalid after retries · 4 endpoint error
"""
import sys, json, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

LANE = "http://192.168.186.14:8888/v1/chat/completions"
MODEL = "qwen3.8-flash-next"

def call(prompt, max_tokens=8000):
    body = json.dumps({"model": MODEL, "messages": [{"role": "user", "content": prompt}],
                       "max_tokens": max_tokens, "temperature": 0.3,
                       "chat_template_kwargs": {"enable_thinking": True}}).encode()
    req = Request(LANE, data=body, headers={"Content-Type": "application/json"})
    with urlopen(req, timeout=1200) as r:
        d = json.load(r)
    if d.get("error"): raise RuntimeError(str(d["error"])[:200])
    return ((d.get("choices") or [{}])[0].get("message", {}) or {}).get("content", "") or ""

def clean(s):
    s = s.strip()
    if s.startswith("```"):
        s = s.split("\n", 1)[1] if "\n" in s else s
        if s.rstrip().endswith("```"): s = s.rstrip()[:-3]
    return s.strip()

def main():
    f = Path(sys.argv[1]); pass_no = sys.argv[2] if len(sys.argv) > 2 else "1"
    cur = f.read_text(encoding="utf-8")
    pkg = next((l for l in cur.splitlines() if l.startswith("package ")), "")
    prompt = f"""You are hardening ONE Kotlin file in "NoBonk", a privacy-first on-device Android
safety app (Kotlin/Compose, on-device YOLO detection; nothing leaves the phone). Pass {pass_no}.

Improve THIS file only — pick what genuinely helps:
- robustness / edge cases (null-safety, bounds, div-by-zero, empty inputs)
- clearer KDoc on public members; brief comments on non-obvious math
- small idiomatic clean-ups (no behavior change)

HARD RULES:
- DO NOT change any public API signature, class/package name, or observable behavior —
  the existing unit tests must still pass unchanged.
- DO NOT add dependencies, network code, logging of user data, or TODOs.
- Keep the same package line: {pkg}
- Return ONLY the complete Kotlin file, no markdown fences, no commentary.

CURRENT FILE ({f.name}):
{cur}"""
    for attempt in range(3):
        try:
            out = clean(call(prompt))
        except (HTTPError, URLError, RuntimeError) as e:
            print(f"GLM_ERR attempt {attempt}: {str(e)[:140]}", flush=True)
            time.sleep(10 * (attempt + 1)); continue
        has_code = ("class " in out) or ("object " in out) or ("fun " in out)
        if len(out) > 100 and pkg and pkg in out and has_code:
            f.write_text(out if out.endswith("\n") else out + "\n", encoding="utf-8")
            print(f"GLM_OK: rewrote {f} ({len(out)} chars)", flush=True); sys.exit(0)
        print(f"GLM_INVALID attempt {attempt}: {len(out)} chars", flush=True)
    print("GLM_FAIL after 3 tries", flush=True); sys.exit(3)

if __name__ == "__main__":
    main()
