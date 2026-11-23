#!/usr/bin/env python3
"""Writes aether-update.json for an APK: what Settings ▸ Updates reads before it downloads anything.

Usage: python3 tools/make_update_metadata.py <apk> <out.json> [--build-tools <dir>] [--expect-signer <sha256>]

Reads with the Android build tools: aapt2 (package, versionCode, versionName, minSdk) and apksigner (signing
certificates). Native ABIs are the lib/<abi>/ folders inside the APK. Fails if --expect-signer is given and differs,
so a release can't silently switch signing key.
"""
import argparse, glob, hashlib, json, os, re, subprocess, sys, zipfile

def tool(build_tools, name):
    if build_tools:
        return os.path.join(build_tools, name)
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    cands = sorted(glob.glob(os.path.join(home or "", "build-tools", "*", name)))
    if not cands:
        sys.exit(f"{name} not found: set ANDROID_HOME or pass --build-tools")
    return cands[-1]

def main():
    p = argparse.ArgumentParser()
    p.add_argument("apk"); p.add_argument("out")
    p.add_argument("--build-tools"); p.add_argument("--expect-signer")
    a = p.parse_args()
    badging = subprocess.run([tool(a.build_tools, "aapt2"), "dump", "badging", a.apk], capture_output=True, text=True, check=True).stdout
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    min_sdk = re.search(r"minSdkVersion:'(\d+)'", badging)
    certs = subprocess.run([tool(a.build_tools, "apksigner"), "verify", "--print-certs", a.apk], capture_output=True, text=True, check=True).stdout
    signers = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs)
    if not pkg or not min_sdk or not signers:
        sys.exit("Couldn't read the package, version, minSdk or signer")
    with zipfile.ZipFile(a.apk) as z:
        abis = sorted({n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")})
    data = open(a.apk, "rb").read()
    meta = {
        "applicationId": pkg.group(1), "versionCode": int(pkg.group(2)), "versionName": pkg.group(3),
        "minSdk": int(min_sdk.group(1)), "abis": abis, "apk": os.path.basename(a.apk), "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(), "signerSha256": signers,
    }
    if a.expect_signer and a.expect_signer.lower().replace(":", "") not in signers:
        sys.exit(f"Signer {signers} is not the expected {a.expect_signer}: refusing to publish metadata for a different key")
    with open(a.out, "w") as f:
        json.dump(meta, f, indent=2); f.write("\n")
    print(json.dumps(meta, indent=2))

if __name__ == "__main__":
    main()
