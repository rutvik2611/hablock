#!/usr/bin/env python3
"""Write Hablock's DataStore for Ruchira's phone: entertainment block + gym places.

Kept as a script rather than hand-edited protobuf so the same config can be rebuilt
after a reinstall. Messaging and payment apps are deliberately absent: she asked for
WhatsApp to keep working, and an outreach app is not entertainment.
"""
import argparse
import json
import uuid

# Apps that exist to be watched, scrolled or played. Matched against what is installed.
ENTERTAINMENT = {
    "com.google.android.youtube": "YouTube",
    "app.revanced.android.youtube": "YouTube ReVanced",
    "com.google.android.apps.youtube.music": "YT Music",
    "app.revanced.android.apps.youtube.music": "YT Music ReVanced",
    "com.google.android.apps.youtube.kids": "YouTube Kids",
    "com.instagram.android": "Instagram",
    "com.instagram.barcelona": "Threads",
    "com.facebook.katana": "Facebook",
    "com.facebook.orca": "Messenger",
    "com.facebook.aura": "Muse",
    "com.snapchat.android": "Snapchat",
    "com.twitter.android": "X",
    "com.reddit.frontpage": "Reddit",
    "com.pinterest": "Pinterest",
    "com.netflix.mediaclient": "Netflix",
    "com.disney.disneyplus": "Disney+",
    "com.hulu.plus": "Hulu",
    "com.amazon.avod.thirdpartyclient": "Prime Video",
    "com.spotify.music": "Spotify",
    "com.twitch.android.app": "Twitch",
    "com.dreamgames.royalmatch": "Royal Match",
    "com.supercell.clashroyale": "Clash Royale",
}

# Never blocked, whatever else happens.
PROTECTED = {
    "com.whatsapp",
    "com.whatsapp.w4b",
    "org.telegram.messenger",
}

HOME = {"name": "Home", "lat": 25.18928, "lon": 55.26410, "radius": 120.0, "excluded": True}
GYM1 = {"name": "Fitness First (Gym 1)", "lat": 25.18157, "lon": 55.27151, "radius": 150.0, "excluded": False}


def wv(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def enc_pairs(pairs):
    """PreferencesProto.PreferenceMap: entries live inside field 1, each {key=1, value=2{string=5}}."""
    out = bytearray()
    for key, value in pairs:
        entry = bytearray()
        entry += wv((1 << 3) | 2) + wv(len(key)) + key.encode()
        inner = wv((5 << 3) | 2) + wv(len(value)) + value.encode()
        entry += wv((2 << 3) | 2) + wv(len(inner)) + inner
        out += wv((1 << 3) | 2) + wv(len(entry)) + entry
    return bytes(out)


def place(spec):
    return {
        "id": str(uuid.uuid4()),
        "name": spec["name"],
        "latitude": spec["lat"],
        "longitude": spec["lon"],
        "radiusMeters": spec["radius"],
        "excluded": spec["excluded"],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--packages", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--required-minutes", type=int, default=30)
    ap.add_argument("--deadline-hour", type=int, default=20)
    args = ap.parse_args()

    installed = {line.strip() for line in open(args.packages) if line.strip()}
    blocked = {p: label for p, label in ENTERTAINMENT.items() if p in installed and p not in PROTECTED}

    home = place(HOME)
    gym = place(GYM1)
    block = {
        "id": str(uuid.uuid4()),
        "name": "Everything",
        "blockedPackages": sorted(blocked),
        "blockedLabels": blocked,
        # Steps only: the gym requirement lives in the location rule, so one block does not
        # quietly change what the other one means.
        "conditions": [{"type": "steps", "id": str(uuid.uuid4()), "goal": 10000.0}],
        "thresholdN": 1,
        "incrementPct": 0.2,
        "enabled": True,
    }
    location = {
        "enabled": True,
        "requiredMinutes": args.required_minutes,
        "deadlineHour": args.deadline_hour,
        "places": [home, gym],
        "defaultPlaceId": gym["id"],
    }

    payload = enc_pairs(
        [
            ("blocks", json.dumps([block], separators=(",", ":"))),
            ("location_config", json.dumps(location, separators=(",", ":"))),
        ]
    )
    with open(args.out, "wb") as fh:
        fh.write(payload)

    print(f"blocked {len(blocked)} apps: {', '.join(sorted(blocked.values()))}")
    print(f"places: {home['name']} (excluded), {gym['name']}")
    print(f"rule: {args.required_minutes} min by {args.deadline_hour}:00, steps 10,000")


if __name__ == "__main__":
    main()
