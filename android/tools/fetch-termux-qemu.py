#!/usr/bin/env python3
"""Fetch Termux's prebuilt qemu-system-aarch64 (Android arm64) and repackage it
as APK native libraries.

  fetch-termux-qemu.py OUT_JNILIBS_DIR OUT_ASSETS_DIR [--cache DIR]

Android only lets an app execute files from its nativeLibraryDir, and the APK
packager only ships files named lib*.so there. So:

  qemu-system-aarch64   -> OUT_JNILIBS_DIR/libqemu.so          (the executable)
  every shared library  -> OUT_JNILIBS_DIR/<package-safe name> (DT_NEEDED closure)
  share/qemu/keymaps    -> OUT_ASSETS_DIR/qemu/keymaps          (VNC needs en-us)
  OUT_ASSETS_DIR/qemu/libmap.txt   "<soname> <file name in the APK>" per library

The ELF files are copied BYTE FOR BYTE. Do not patchelf or strip them:
patchelf --set-soname/--replace-needed rewrote the program headers with a
misaligned extra PT_LOAD (p_offset % p_align != p_vaddr % p_align), which the
kernel refuses at exec: the APK's QEMU died with SIGSEGV (exit 139) on a
Samsung phone. A soname that is not a valid APK name (libz.so.1,
libglib-2.0.so.0) is shipped as e.g. libz_so_1.so; the app recreates the
original names as symlinks (VmService.kt) and puts that directory first in
LD_LIBRARY_PATH. Libraries Android itself provides (bionic, liblog,
libandroid, ...) are not copied.

QEMU is GPL-2.0; the package versions and source URLs are written to
OUT_ASSETS_DIR/qemu/SOURCES.txt.
"""
import argparse, hashlib, io, lzma, os, re, shutil, subprocess, sys, tarfile, urllib.request

REPO = 'https://packages.termux.dev/apt/termux-main'
INDEX = f'{REPO}/dists/stable/main/binary-aarch64/Packages'
ROOT_PKGS = ['qemu-system-aarch64-headless']
PREFIX = 'data/data/com.termux/files/usr/'
SYSTEM_LIBS = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so',
    'libEGL.so', 'libGLESv2.so', 'libGLESv3.so', 'libvulkan.so', 'libOpenSLES.so',
    'libaaudio.so', 'libmediandk.so', 'libnativewindow.so', 'libjnigraphics.so',
    'libsync.so', 'libcamera2ndk.so', 'libstdc++.so',
}


def fetch(url, cache):
    name = os.path.join(cache, re.sub(r'[^A-Za-z0-9._-]', '_', url.split('://', 1)[1]))
    if not os.path.exists(name):
        print('fetch', url, file=sys.stderr)
        with urllib.request.urlopen(url) as r, open(name + '.part', 'wb') as f:
            shutil.copyfileobj(r, f)
        os.rename(name + '.part', name)
    return name


def parse_index(path):
    pkgs, cur = {}, {}
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line:
            if cur:
                pkgs[cur['Package']] = cur
            cur = {}
        elif not line.startswith(' '):
            k, _, v = line.partition(': ')
            cur[k] = v
    if cur:
        pkgs[cur['Package']] = cur
    provides = {}
    for p in pkgs.values():
        for prov in p.get('Provides', '').split(','):
            prov = prov.strip().split(' ')[0]
            if prov:
                provides.setdefault(prov, p['Package'])
    return pkgs, provides


def closure(pkgs, provides, roots):
    seen, todo = [], list(roots)
    while todo:
        n = todo.pop()
        n = n if n in pkgs else provides.get(n, n)
        if n in seen or n not in pkgs:
            continue
        seen.append(n)
        for dep in pkgs[n].get('Depends', '').split(','):
            alt = dep.split('|')[0].strip().split(' ')[0]
            if alt:
                todo.append(alt)
    return seen


def deb_data(path):
    """The data.tar.* member of a .deb (an ar archive) as a TarFile."""
    blob = open(path, 'rb').read()
    assert blob[:8] == b'!<arch>\n', path
    off = 8
    while off < len(blob):
        name = blob[off:off + 16].decode().strip().rstrip('/')
        size = int(blob[off + 48:off + 58])
        data = blob[off + 60:off + 60 + size]
        off += 60 + size + (size & 1)
        if name.startswith('data.tar'):
            if name.endswith('.xz'):
                data = lzma.decompress(data)
                return tarfile.open(fileobj=io.BytesIO(data))
            if name.endswith('.zst'):
                data = subprocess.run(['zstd', '-dc'], input=data, stdout=subprocess.PIPE, check=True).stdout
                return tarfile.open(fileobj=io.BytesIO(data))
            return tarfile.open(fileobj=io.BytesIO(data), mode='r:*')
    raise SystemExit(f'{path}: no data.tar')


def needed(path):
    out = subprocess.run(['readelf', '-d', path], stdout=subprocess.PIPE, text=True, check=True).stdout
    return re.findall(r'\(NEEDED\)\s+Shared library: \[([^\]]+)\]', out)


def apk_name(soname):
    """A file name the APK packager keeps: lib<...>.so with no other dots.
    libc++_shared.so stays; libz.so.1 -> libz_so_1.so; libglib-2.0.so.0 -> libglib-2_0_so_0.so."""
    if re.fullmatch(r'lib[A-Za-z0-9_+-]+\.so', soname):
        return soname
    base = re.sub(r'[^A-Za-z0-9_+-]', '_', soname)
    if not base.startswith('lib'):
        base = 'lib' + base
    return base + '.so'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('jnilibs')
    ap.add_argument('assets')
    ap.add_argument('--cache', default=os.path.expanduser('~/.cache/ios-sweet-termux'))
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)

    pkgs, provides = parse_index(fetch(INDEX, a.cache))
    names = closure(pkgs, provides, ROOT_PKGS)
    stage = os.path.join(a.cache, 'stage')
    shutil.rmtree(stage, ignore_errors=True)
    os.makedirs(stage)
    sources = []
    for n in names:
        p = pkgs[n]
        deb = fetch(f"{REPO}/{p['Filename']}", a.cache)
        if hashlib.sha256(open(deb, 'rb').read()).hexdigest() != p['SHA256']:
            raise SystemExit(f'{deb}: SHA256 mismatch')
        sources.append(f"{n} {p['Version']} {p.get('Homepage', '')}")
        with deb_data(deb) as t:
            for m in t.getmembers():
                name = m.name.lstrip('./')
                if not name.startswith(PREFIX):
                    continue
                rel = name[len(PREFIX):]
                if rel.startswith('lib/') or rel == 'bin/qemu-system-aarch64' or rel.startswith('share/qemu/keymaps/'):
                    m.name = rel
                    if m.issym() or m.isfile() or m.isdir():
                        if hasattr(tarfile, 'data_filter'):
                            t.extract(m, stage, filter='tar')
                        else:
                            t.extract(m, stage)

    libdir = os.path.join(stage, 'lib')
    exe = os.path.join(stage, 'bin/qemu-system-aarch64')
    if not os.path.exists(exe):
        raise SystemExit('qemu-system-aarch64 missing from the packages')

    # DT_NEEDED closure from the executable.
    want, todo, missing = {}, [exe], set()
    while todo:
        f = todo.pop()
        for so in needed(f):
            if so in SYSTEM_LIBS or so in want:
                continue
            src = os.path.join(libdir, so)
            if not os.path.exists(src):
                missing.add(so)
                continue
            want[so] = os.path.realpath(src)
            todo.append(want[so])
    if missing:
        print('note: not in Termux packages (expected from Android):', ', '.join(sorted(missing)), file=sys.stderr)

    rename = {so: apk_name(so) for so in want}
    if len(set(rename.values())) != len(rename) or 'libqemu.so' in rename.values():
        raise SystemExit(f'library name collision after renaming: {rename}')
    shutil.rmtree(a.jnilibs, ignore_errors=True)
    os.makedirs(a.jnilibs)
    outputs = [(exe, os.path.join(a.jnilibs, 'libqemu.so'))]
    outputs += [(src, os.path.join(a.jnilibs, rename[so])) for so, src in want.items()]
    for src, dst in outputs:
        shutil.copyfile(src, dst)   # unmodified ELF (see the module docstring)
        os.chmod(dst, 0o755)
    os.makedirs(os.path.join(a.assets, 'qemu'), exist_ok=True)
    with open(os.path.join(a.assets, 'qemu', 'libmap.txt'), 'w') as f:
        for so in sorted(rename):
            f.write(f'{so} {rename[so]}\n')

    km_src = os.path.join(stage, 'share/qemu/keymaps')
    km_dst = os.path.join(a.assets, 'qemu', 'keymaps')
    shutil.rmtree(km_dst, ignore_errors=True)
    os.makedirs(km_dst)
    for k in ('en-us', 'common', 'modifiers'):
        if os.path.exists(os.path.join(km_src, k)):
            shutil.copyfile(os.path.join(km_src, k), os.path.join(km_dst, k))
    with open(os.path.join(a.assets, 'qemu', 'SOURCES.txt'), 'w') as f:
        f.write('QEMU and its libraries are prebuilt Termux packages (https://github.com/termux/termux-packages),\n'
                'redistributed unmodified (byte for byte; some library file names changed). Package versions:\n\n')
        f.write('\n'.join(sorted(sources)) + '\n')
    total = sum(os.path.getsize(d) for _, d in outputs)
    print(f'{len(outputs)} files, {total >> 20} MiB -> {a.jnilibs}', file=sys.stderr)


if __name__ == '__main__':
    main()
