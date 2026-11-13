#!/usr/bin/env python3
"""Turn a SWEETXNU payload into the boot bundle of the sweet-virt machine
(QEMU virt; the Android app in android/, or tools/qemu-sweet-virt.sh on a PC).

  mkvirtbundle.py PAYLOAD OUTDIR [--name "iOS 16"] [--ram-mb 4096]

The payload must be built with an ADT from `mkadt.py --virt --ram-mb N` and
the same N here. OUTDIR gets:

  ram.bin       kernel segments + ADT + boot_args, loaded at phys_base
  stub.bin      tools/virt-stub (ramfb setup, jump to the kernel), at STUB_PA
  bundle.json   addresses, RAM size and where the boot-args line is in ram.bin

The app passes these to QEMU as -device loader entries and may rewrite the
boot-args line (cpus=N etc.) at `cmdline_offset` before starting QEMU.
"""
import argparse, importlib.util, json, os, struct, sys

HERE = os.path.dirname(os.path.abspath(__file__))
STUB_PA = 0x40200000
STUB_MAGIC = b'SWVSTUB1'
BA_CMDLINE_OFFSET = 108         # offsetof(boot_args, CommandLine)
BOOT_LINE_LENGTH = 608
# Boot framebuffer of the virtual machine: the presented 1125x2436 display at
# half size (kernel boot-arg sweetfb_scale=2), within QEMU VNC's 2160 rows.
FB_W, FB_H, FB_SCALE = 560, 1218, 2

spec = importlib.util.spec_from_file_location('qemu_sweet', os.path.join(HERE, 'qemu-sweet.py'))
qemu_sweet = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qemu_sweet)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('payload')
    ap.add_argument('outdir')
    ap.add_argument('--name', default='iOS 16')
    ap.add_argument('--version', default='16.7')
    ap.add_argument('--ram-mb', type=int, default=4096)
    ap.add_argument('--stub', default=os.path.join(HERE, 'virt-stub', 'stub.bin'))
    a = ap.parse_args()

    payload = open(a.payload, 'rb').read()
    fb = (qemu_sweet.FB_BASE, FB_W, FB_H, FB_W * 4)
    phys_base, img, ba_pa, entry_pa = qemu_sweet.build_image(payload, fb)
    mem_size = struct.unpack_from('<Q', payload, 0x40)[0]
    ram_end = 0x40000000 + (a.ram_mb << 20)
    if phys_base + mem_size + 0x100000 != ram_end:
        sys.exit(f'payload RAM {phys_base:#x}+{mem_size:#x} does not match --ram-mb {a.ram_mb} '
                 f'(build the ADT with mkadt.py --virt --ram-mb {a.ram_mb})')
    cmd_off = ba_pa - phys_base + BA_CMDLINE_OFFSET
    cmdline = img[cmd_off:cmd_off + BOOT_LINE_LENGTH].split(b'\0')[0].decode()
    # The half-size framebuffer needs the kernel's downsampling.
    words = [w for w in cmdline.split() if not w.startswith('sweetfb_scale=')] + [f'sweetfb_scale={FB_SCALE}']
    cmdline = ' '.join(words)
    if len(cmdline) >= BOOT_LINE_LENGTH:
        sys.exit('boot-args too long')
    img = bytearray(img)
    img[cmd_off:cmd_off + BOOT_LINE_LENGTH] = cmdline.encode().ljust(BOOT_LINE_LENGTH, b'\0')
    img = bytes(img)

    stub = bytearray(open(a.stub, 'rb').read())
    at = stub.find(STUB_MAGIC)
    if at < 0:
        sys.exit(f'{a.stub}: no params block')
    struct.pack_into('<QQQIII', stub, at + 8, ba_pa, entry_pa, fb[0], fb[1], fb[2], fb[3])

    os.makedirs(a.outdir, exist_ok=True)
    open(os.path.join(a.outdir, 'ram.bin'), 'wb').write(img)
    open(os.path.join(a.outdir, 'stub.bin'), 'wb').write(stub)
    meta = {
        'format': 1,
        'name': a.name,
        'version': a.version,
        'ram_mb': a.ram_mb,
        'phys_base': phys_base,
        'stub_pa': STUB_PA,
        'boot_args_pa': ba_pa,
        'entry_pa': entry_pa,
        'cmdline_offset': cmd_off,
        'cmdline_max': BOOT_LINE_LENGTH,
        'cmdline': cmdline,
        'framebuffer': {'base': fb[0], 'width': fb[1], 'height': fb[2], 'stride': fb[3],
                        'scale': FB_SCALE},
    }
    json.dump(meta, open(os.path.join(a.outdir, 'bundle.json'), 'w'), indent=2)
    print(f'{a.outdir}: ram.bin {len(img) >> 20} MiB at {phys_base:#x}, entry {entry_pa:#x}, '
          f'boot_args {ba_pa:#x}', file=sys.stderr)


if __name__ == '__main__':
    main()
