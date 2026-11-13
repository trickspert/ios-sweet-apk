#!/usr/bin/env python3
"""Test inputs for BootBuilderTest (not a real kernel):

  mkfakekernel.py KERNEL_OUT APPLE_ADT_OUT

KERNEL_OUT: a kernel-shaped arm64 Mach-O (MH_EXECUTE): __TEXT at file offset 0
with the header, __DATA with three DYLD_CHAINED_PTR_64_OFFSET rebases,
__LINKEDIT with LC_DYLD_CHAINED_FIXUPS, LC_UNIXTHREAD pc = __TEXT + 0x1000.
APPLE_ADT_OUT: a device tree with a /product node (one placeholder prop).
"""
import os, struct, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../../../tools'))
import adt  # noqa: E402

BASE = 0xfffffff007004000
PAGE = 0x4000


def kernel():
    lc = []
    def seg(name, vmaddr, vmsize, fileoff, filesize, prot):
        return struct.pack('<II16sQQQQiiII', 0x19, 72, name.encode(), vmaddr, vmsize, fileoff, filesize, prot, prot, 0, 0)
    lc.append(seg('__TEXT', BASE, PAGE, 0, PAGE, 5))
    lc.append(seg('__DATA', BASE + PAGE, PAGE, PAGE, PAGE, 3))
    lc.append(seg('__LINKEDIT', BASE + 2 * PAGE, PAGE, 2 * PAGE, PAGE, 1))
    state = [0] * 32 + [BASE + 0x1000, 0]       # x0..x28 fp lr sp, pc, cpsr(+pad)
    lc.append(struct.pack('<IIII', 0x5, 16 + 8 * 34, 6, 68) + struct.pack('<34Q', *state))
    fix_off = 2 * PAGE
    lc.append(struct.pack('<IIII', 0x80000034, 16, fix_off, 0x100))
    cmds = b''.join(lc)
    hdr = struct.pack('<8I', 0xfeedfacf, 0x0100000c, 0, 2, len(lc), len(cmds), 1, 0)
    buf = bytearray(3 * PAGE)
    buf[0:32 + len(cmds)] = hdr + cmds
    buf[0x1000:0x1010] = struct.pack('<4I', 0xd2800020, 0x14000000, 0xd503201f, 0xd503201f)
    # fmt 6: target (36) = offset from image base | high8 << 36 | next (12) << 51
    ptr = lambda target, nxt: target | (nxt << 51)
    struct.pack_into('<3Q', buf, PAGE, ptr(0x1000, 2), ptr(PAGE, 2), ptr(0x1008, 0))
    # chained fixups: header (28 -> 32), starts_in_image, starts_in_segment for __DATA
    f = bytearray(0x100)
    struct.pack_into('<7I', f, 0, 0, 32, 0x80, 0x80, 0, 1, 0)
    struct.pack_into('<4I', f, 32, 3, 0, 16, 0)
    struct.pack_into('<IHHQIHH', f, 48, 24, PAGE, 6, PAGE, 0, 1, 0)
    buf[fix_off:fix_off + len(f)] = f
    return bytes(buf)


def apple_adt():
    n = adt.Node()
    n.props = [('name', 0, b'device-tree\0'), ('model', 0, b'iPhone10,3\0')]
    chosen = adt.Node()
    chosen.props = [('name', 0, b'chosen\0'), ('dram-base', 0, b'\0' * 8), ('dram-size', 0, b'\0' * 8)]
    mm = adt.Node()
    mm.props = [('name', 0, b'memory-map\0')]
    chosen.children = [mm]
    prod = adt.Node()
    prod.props = [('name', 0, b'product\0'), ('AAPL,phandle', 0, b'\x07\0\0\0'),
                  ('product-name', 0, b'iPhone X\0'), ('artwork-device-subtype', 0, struct.pack('<I', 2436)),
                  ('serial-placeholder', 1, b'\0' * 8)]
    n.children = [chosen, prod]
    return adt.serialize(n)


if __name__ == '__main__':
    open(sys.argv[1], 'wb').write(kernel())
    open(sys.argv[2], 'wb').write(apple_adt())
