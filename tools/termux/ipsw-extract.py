#!/usr/bin/env python3
"""Extract what tools/mkiosroot.sh needs from an iPhone X IPSW.

  ipsw-extract.py IPSW OUTDIR [DEVICE_CLASS]      (DEVICE_CLASS default d22ap)

IPSW can be the .ipsw file or a block device holding it (the Termux VM
attaches the file read-only as a disk). It is a zip; BuildManifest.plist
names the images of each build identity:

  OUTDIR/os.dmg       Manifest "OS"                   (UDIF, APFS root)
  OUTDIR/sysos.dmg    Manifest "Cryptex1,SystemOS"    (raw APFS)
  OUTDIR/appos.dmg    Manifest "Cryptex1,AppOS"       (raw APFS)
  OUTDIR/DeviceTree.<class>.im4p                      (for Sweet16's /product)

Prints "VERSION BUILD" of the IPSW first and warns unless it is the
iOS 16.7.16 (20H392) build this project was made with.
"""
import os, plistlib, shutil, sys, time, zipfile

WANT_BUILD = '20H392'


def main():
    src, out = sys.argv[1], sys.argv[2]
    dev_class = (sys.argv[3] if len(sys.argv) > 3 else 'd22ap').lower()
    os.makedirs(out, exist_ok=True)
    z = zipfile.ZipFile(open(src, 'rb'))
    bm = plistlib.loads(z.read('BuildManifest.plist'))
    version, build = bm.get('ProductVersion', '?'), bm.get('ProductBuildVersion', '?')
    print(f'IPSW: iOS {version} ({build}), devices {", ".join(bm.get("SupportedProductTypes", []))}', flush=True)
    if build != WANT_BUILD:
        print(f'WARNING: this project was built and tested with {WANT_BUILD} (iOS 16.7.16); '
              f'{build} may not work (tools/sweet-root-tweaks.py, HID services)', flush=True)

    ids = [i for i in bm['BuildIdentities'] if i.get('Info', {}).get('DeviceClass', '').lower() == dev_class]
    if not ids:
        sys.exit(f'no build identity for device class {dev_class}')
    ids.sort(key=lambda i: i['Info'].get('RestoreBehavior') != 'Erase')
    m = ids[0]['Manifest']

    def path(key):
        try:
            return m[key]['Info']['Path']
        except KeyError:
            sys.exit(f'BuildManifest has no {key} image for {dev_class} (is this an iOS 16 IPSW?)')

    jobs = [(path('OS'), 'os.dmg'), (path('Cryptex1,SystemOS'), 'sysos.dmg'), (path('Cryptex1,AppOS'), 'appos.dmg')]
    dt = m.get('DeviceTree', {}).get('Info', {}).get('Path')
    if dt:
        jobs.append((dt, f'DeviceTree.{dev_class}.im4p'))
    for member, name in jobs:
        info = z.getinfo(member)
        dst = os.path.join(out, name)
        if os.path.exists(dst) and os.path.getsize(dst) == info.file_size:
            print(f'{name}: already extracted', flush=True)
            continue
        print(f'{name} <- {member} ({info.file_size >> 20} MiB)', flush=True)
        t0, done, last = time.time(), 0, 0
        with z.open(info) as r, open(dst + '.part', 'wb') as w:
            while True:
                b = r.read(8 << 20)
                if not b:
                    break
                w.write(b)
                done += len(b)
                if done - last >= (512 << 20):
                    last = done
                    print(f'  {done >> 20}/{info.file_size >> 20} MiB, {done / (time.time() - t0 + 1e-9) / 1e6:.1f} MB/s', flush=True)
        os.rename(dst + '.part', dst)
    print('extracted', flush=True)


if __name__ == '__main__':
    main()
