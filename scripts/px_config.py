#!/usr/bin/env python3
"""Write the Pixel's Hablock DataStore: entertainment block + step checkpoints.

Kept as a script so the same block can be re-applied after a reinstall. Payment and
banking apps are never listed; the checkpoints (4,000 by 12:00, 10,000 by 17:00) are
constants in the app, so a single steps condition of 10,000 activates both.
"""
import argparse
import json
import uuid

ENTERTAINMENT = {
    "com.google.android.youtube": "YouTube",
    "app.revanced.android.youtube": "YouTube ReVanced",
    "com.google.android.apps.youtube.music": "YT Music",
    "com.instagram.android": "Instagram",
    "com.facebook.katana": "Facebook",
    "com.netflix.mediaclient": "Netflix",
    "com.zhiliaoapp.musically": "TikTok",
    "com.google.android.apps.photos": "Photos",
    "com.whatsapp": "WhatsApp",
}

PROTECTED = {"com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger"}


def wv(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def enc_value(value):
    """PreferencesProto.Value: oneof {bool=1, float=2, int32=3, int64=4, string=5, ...}."""
    if isinstance(value, bool):
        return wv((1 << 3) | 0) + wv(1 if value else 0)
    raw = value.encode()
    return wv((5 << 3) | 2) + wv(len(raw)) + raw


def enc_pairs(pairs):
    """PreferencesProto.PreferenceMap: entries live inside field 1, each {key=1, value=2{string=5}}."""
    out = bytearray()
    for key, value in pairs:
        entry = bytearray()
        entry += wv((1 << 3) | 2) + wv(len(key)) + key.encode()
        inner = enc_value(value)
        entry += wv((2 << 3) | 2) + wv(len(inner)) + inner
        out += wv((1 << 3) | 2) + wv(len(entry)) + entry
    return bytes(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--packages", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--with-whatsapp", action="store_true")
    args = ap.parse_args()

    installed = {line.strip() for line in open(args.packages) if line.strip()}
    # WhatsApp is blocked when asked for; the business line stays exempt either way.
    protected = PROTECTED if not args.with_whatsapp else {"com.whatsapp.w4b"}
    blocked = {
        pkg: label
        for pkg, label in ENTERTAINMENT.items()
        if pkg in installed and pkg not in protected
    }

    block = {
        "id": str(uuid.uuid4()),
        "name": "Everything",
        "blockedPackages": sorted(blocked),
        "blockedLabels": blocked,
        "conditions": [{"type": "steps", "id": str(uuid.uuid4()), "goal": 10000.0}],
        "thresholdN": 1,
        "incrementPct": 0.2,
        "enabled": True,
    }
    payload = enc_pairs(
        [
            ("blocks", json.dumps([block], separators=(",", ":"))),
            # Boolean, so a reinstall does not replay onboarding over a configured phone.
            ("onboarding_done", True),
        ],
    )
    with open(args.out, "wb") as fh:
        fh.write(payload)
    print(f"blocked {len(blocked)}: {', '.join(sorted(blocked.values()))}")


if __name__ == "__main__":
    main()
