#!/bin/sh
# Kernel -> boot bundle of the Sweet16 Android app (sweet-virt machine).
#
#   tools/mksweet16.sh KERNEL OUT_DIR [OUT.zip]
#
# KERNEL is kernel.development.sweet from the xnu workflow (with patch 0082).
# If research/ipsw-x/d22ap.adt exists (see README, "device tree"), its
# /product node goes into the ADT, as on the phone; MobileGestalt reads it.
# The zip (bundle.json, ram.bin, stub.bin) is what the app's
# "Import boot files" takes. Env: RAM_MB (default 4096).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
kernel=$1
out=$2
zip=${3:-}
ram=${RAM_MB:-4096}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

python3 "$here/mkadt.py" "$tmp/virt.adt" --virt --ram-mb "$ram"
python3 "$here/mkpayload.py" "$kernel" "$tmp/virt.adt" "$tmp/payload.bin" \
    --cmdline 'debug=0xc serial=3 cpus=4 keepsyms=1 -enable_kprintf_spam -noprogress rd=ufsroot0 unentitled_ios_sim_launch=1 ignition_level=1 wdt_test=no_user_checkin ufsrw=1 rootrw=1 launchd_unsecure_cache=1 wdt=-1 sweetfb_hz=30'
python3 "$here/mkvirtbundle.py" "$tmp/payload.bin" "$out" --ram-mb "$ram"
if [ -n "$zip" ]; then
    rm -f "$zip"
    (cd "$out" && zip -j -0 - bundle.json ram.bin stub.bin) > "$zip"
    echo "$zip" >&2
fi
