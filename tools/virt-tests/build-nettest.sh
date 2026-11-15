#!/bin/sh
# Build nettest.c into a static arm64 Mach-O PID 1 (Developer boot RAM disk).
#   tools/virt-tests/build-nettest.sh OUT
# The C code is linked as one flat, position-dependent blob at mkinit.py's
# code address (__TEXT 0x100000000 + 0x400) and wrapped by mkinit.py. x18 is
# reserved on Darwin (the kernel clears it): -ffixed-x18.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out=$1
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/flat.ld" <<'LD'
ENTRY(_start)
SECTIONS {
  . = 0x100000400;
  .text : { *(.text._start) *(.text*) *(.rodata*) *(.srodata*) }
  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
  .data : { *(.data*) *(.bss*) *(COMMON) }
}
LD
clang --target=aarch64-linux-gnu -ffreestanding -fno-builtin -nostdlib -fno-pic -fno-pie \
	-ffixed-x18 -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables -mgeneral-regs-only \
	-O2 -c "$here/nettest.c" -o "$tmp/nettest.o"
ld.lld -static -no-pie -T "$tmp/flat.ld" "$tmp/nettest.o" -o "$tmp/nettest.elf"
if llvm-objdump -h "$tmp/nettest.elf" | grep -q ' \.data '; then
	echo "nettest: writable data is not supported (keep state on the stack)" >&2
	exit 1
fi
llvm-objcopy -O binary --only-section=.text "$tmp/nettest.elf" "$tmp/nettest.bin"
start=$(llvm-nm "$tmp/nettest.elf" | awk '$3 == "_start" { print $1 }')
python3 - "$here/../mkinit.py" "$tmp/nettest.bin" "$start" "$out" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location('mkinit', sys.argv[1])
mk = importlib.util.module_from_spec(spec); spec.loader.exec_module(mk)
blob = open(sys.argv[2], 'rb').read()
start = int(sys.argv[3], 16) - (mk.BASE + mk.CODE_OFF)
mk.text_of = lambda obj: (blob, start)
open(sys.argv[4], 'wb').write(mk.build(b''))
print(f'{sys.argv[4]}: {len(blob)} bytes of code, _start at +{start:#x}')
PY
