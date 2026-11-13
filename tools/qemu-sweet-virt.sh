#!/bin/sh
# Run a sweet-virt boot bundle (tools/mkvirtbundle.py) on a PC with the same
# QEMU command line as the Android app (android/.../VmService.kt).
#
#   tools/qemu-sweet-virt.sh BUNDLE_DIR DISK.img [RUN_DIR]
#
# Screen and input: VNC on RUN_DIR/vnc.sock (e.g. `vncviewer RUN_DIR/vnc.sock`,
# or SWEET16_VNC_SOCKET for the app's RfbClientTest). Kernel console:
# RUN_DIR/console.log. QMP: RUN_DIR/qmp.sock. Env: CPUS (default 4).
set -eu
bundle=$(cd "$1" && pwd)
disk=$2
run=${3:-$(mktemp -d)}
mkdir -p "$run"
run=$(cd "$run" && pwd)
cpus=${CPUS:-4}

j() { python3 -c "import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$bundle/bundle.json" "$1"; }
ram_mb=$(j ram_mb)
phys=$(printf '0x%x' "$(j phys_base)")
stub=$(printf '0x%x' "$(j stub_pa)")

# Boot-args as the app sets them: the bundle's line with cpus=N.
python3 - "$bundle" "$cpus" <<'EOF'
import json, sys
b, cpus = sys.argv[1], sys.argv[2]
m = json.load(open(f'{b}/bundle.json'))
words = [w for w in m['cmdline'].split() if not w.startswith('cpus=')] + [f'cpus={cpus}']
line = ' '.join(words).encode()
assert len(line) < m['cmdline_max']
with open(f'{b}/ram.bin', 'r+b') as f:
    f.seek(m['cmdline_offset'])
    f.write(line.ljust(m['cmdline_max'], b'\0'))
EOF

rm -f "$run/vnc.sock" "$run/qmp.sock"
echo "run dir: $run" >&2
exec qemu-system-aarch64 \
    -nodefaults -no-user-config \
    -M virt,gic-version=3,highmem=on -cpu cortex-a76 -smp "$cpus" -m "$ram_mb" \
    -accel tcg,thread=multi,tb-size=256 \
    -display none -vnc "unix:$run/vnc.sock" \
    -qmp "unix:$run/qmp.sock,server=on,wait=off" \
    -serial "file:$run/console.log" \
    -device ramfb \
    -global virtio-mmio.force-legacy=false \
    -drive "if=none,id=root,file=$disk,format=raw,cache=writeback,aio=threads" \
    -device virtio-blk-device,drive=root \
    -device virtio-tablet-device -device virtio-keyboard-device \
    -device "loader,file=$bundle/ram.bin,addr=$phys,force-raw=on" \
    -device "loader,file=$bundle/stub.bin,addr=$stub,force-raw=on" \
    -device "loader,addr=$stub,cpu-num=0"
