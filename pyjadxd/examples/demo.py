#!/usr/bin/env python3
"""
Minimal demo: load an APK → search strings → decompile a method → xrefs.

Usage:
    # Start jadxd first:  cd jadxd && ./gradlew run
    python demo.py /path/to/app.apk
"""

from __future__ import annotations

import sys

from pyjadxd import JadxdClient


def main(apk_path: str) -> None:
    with JadxdClient() as client:
        # 1. Load the artifact
        print(f"Loading {apk_path}…")
        load = client.load(apk_path)
        sid = load.session_id
        print(f"  session : {sid}")
        print(f"  hash    : {load.artifact_hash[:16]}…")
        print(f"  classes : {load.class_count}")
        print(f"  backend : {load.provenance.backend} {load.provenance.backend_version}")
        print()

        # 2. List types (first 10)
        types = client.list_types(sid)
        print(f"Types ({len(types.types)} total, showing first 10):")
        for t in types.types[:10]:
            print(f"  [{t.kind:10s}] {t.id}")
        print()

        # 3. Pick the first class with methods and decompile one
        for t in types.types:
            methods = client.list_methods(sid, t.id)
            if methods.methods:
                m = methods.methods[0]
                print(f"Decompiling {m.id}…")
                dec = client.decompile_method(sid, m.id)
                if dec.java:
                    preview = dec.java[:500]
                    print(f"  Java ({len(dec.java)} chars):")
                    for line in preview.splitlines()[:15]:
                        print(f"    {line}")
                if dec.smali:
                    print(f"  Smali ({len(dec.smali)} chars):")
                    for line in dec.smali.splitlines()[:10]:
                        print(f"    {line}")
                if dec.warnings:
                    print(f"  Warnings: {dec.warnings}")
                print()

                # 4. Xrefs
                xrefs_to = client.xrefs_to(sid, m.id)
                print(f"Callers of {m.name}: {len(xrefs_to.refs)}")
                for ref in xrefs_to.refs[:5]:
                    print(f"  ← {ref.id}")

                xrefs_from = client.xrefs_from(sid, m.id)
                print(f"Callees of {m.name}: {len(xrefs_from.refs)}")
                for ref in xrefs_from.refs[:5]:
                    print(f"  → {ref.id}")
                print()
                break

        # 5. String search
        print("Searching for 'http'…")
        strings = client.search_strings(sid, "http", limit=5)
        print(f"  Found {strings.total_count} matches:")
        for sm in strings.matches[:5]:
            print(f"    \"{sm.value[:80]}\" in {sm.locations[0].type_id}")
        print()

        # 6. Manifest (APK only)
        try:
            manifest = client.get_manifest(sid)
            print(f"Manifest ({len(manifest.text)} chars):")
            for line in manifest.text.splitlines()[:8]:
                print(f"  {line}")
        except Exception as e:
            print(f"Manifest: {e}")
        print()

        # 7. Resources
        resources = client.list_resources(sid)
        print(f"Resources ({len(resources.resources)} total, first 10):")
        for r in resources.resources[:10]:
            print(f"  [{r.type:10s}] {r.name}")

        # Cleanup
        client.close_session(sid)
        print("\nDone.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <apk_or_dex_path>")
        sys.exit(1)
    main(sys.argv[1])
