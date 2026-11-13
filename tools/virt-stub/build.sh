#!/bin/sh
# Build the sweet-virt boot stub (needs clang + ld.lld) -> tools/virt-stub/stub.bin.
# The flat binary is linked for STUB_PA (tools/mkvirtbundle.py) and committed,
# so the bundle tool works without a cross toolchain.
set -eu
cd "$(dirname "$0")"
CFLAGS="--target=aarch64-none-elf -ffreestanding -fno-stack-protector -mgeneral-regs-only -Os -Wall -Werror"
clang $CFLAGS -c start.S -o start.o
clang $CFLAGS -c stub.c -o stub.o
ld.lld -T stub.ld start.o stub.o -o stub.elf
llvm-objcopy -O binary stub.elf stub.bin
rm -f start.o stub.o stub.elf
ls -l stub.bin
