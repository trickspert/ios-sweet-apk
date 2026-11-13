#!/data/data/com.termux/files/usr/bin/bash
# Build the iOS 16 root image for Sweet16 ON THE PHONE, without root.
#
#   tools/termux/mkiosroot-termux.sh IPSW OUT.img [SIZE]      (SIZE default 16G)
#
# tools/mkiosroot.sh needs root (apfs-fuse, mount -o loop), which an unrooted
# Android phone cannot give. So this starts a small Ubuntu arm64 VM in QEMU
# (Termux's qemu-system-aarch64, no root needed) and runs the unchanged
# tools/mkiosroot.sh and tools/sweet-root-tweaks.py as root INSIDE the VM:
#   - the IPSW is attached read-only as a disk (nothing is unpacked on the phone)
#   - OUT.img is attached as the output disk and becomes the HFS+ root
#   - the job (tools/termux/guest-job.sh) runs through cloud-init, prints
#     "SWEET16:" progress lines here, and powers the VM off when done
# Also writes DT-d22ap.bin (raw iPhone X device tree) next to OUT.img.
#
# Termux packages: qemu-system-aarch64-headless qemu-utils mtools dosfstools
# curl coreutils. Needs internet (Ubuntu image ~600 MB once, apt packages in
# the VM) and roughly 15 GB free in Termux's home for the VM's disk.
# Software emulation: expect several hours; keep the phone charging and run
# `termux-wake-lock` first so Android does not stop Termux.
#
# Env: CPUS (default: all), MEM_MB (default 3072), SWEET16_VM_DIR
# (default ~/.cache/sweet16-vm), SWEET16_PROXY / SWEET16_EXTRA_CA (only for
# networks that need a proxy), SWEET16_KEEP_VM=1 keeps the VM disk afterwards
# (a re-run then skips apt, the apfs-fuse build and the extraction).
set -euo pipefail

usage() { sed -n '2,25p' "$0"; exit 2; }
[[ $# -ge 2 ]] || usage
ipsw=$(realpath "$1")
out=$2
size=${3:-16G}
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
vm=${SWEET16_VM_DIR:-$HOME/.cache/sweet16-vm}
cpus=${CPUS:-$(nproc)}
mem=${MEM_MB:-3072}
UBUNTU_URL=https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-arm64.img
say() { echo "== $*"; }
# QEMU splits -drive options on ',': a comma in a file name (the IPSW is
# "iPhone10,3,iPhone10,6_..._Restore.ipsw") must be doubled.
qpath() { printf '%s' "${1//,/,,}"; }

[[ -f $ipsw ]] || { echo "no such IPSW: $ipsw" >&2; exit 1; }
[[ ! -e $out ]] || { echo "refusing to overwrite $out (delete it first)" >&2; exit 1; }
for t in qemu-system-aarch64 qemu-img mformat mcopy mtype curl; do
	command -v "$t" >/dev/null || {
		echo "missing $t: pkg install qemu-system-aarch64-headless qemu-utils mtools dosfstools curl" >&2
		exit 1
	}
done
prefix=$(dirname "$(dirname "$(command -v qemu-system-aarch64)")")
fw=""
for f in "$prefix/share/qemu/edk2-aarch64-code.fd" /usr/share/qemu/edk2-aarch64-code.fd /usr/share/AAVMF/AAVMF_CODE.fd; do
	[[ -f $f ]] && { fw=$f; break; }
done
[[ -n $fw ]] || { echo "UEFI firmware edk2-aarch64-code.fd not found (it comes with QEMU)" >&2; exit 1; }

mkdir -p "$vm"
if [[ ! -f $vm/ubuntu.img ]]; then
	say "downloading Ubuntu 24.04 arm64 cloud image (once)"
	curl -fL --retry 3 -o "$vm/ubuntu.img.part" "$UBUNTU_URL"
	mv "$vm/ubuntu.img.part" "$vm/ubuntu.img"
fi
if [[ ! -f $vm/work.qcow2 ]]; then
	# Copy-on-write over the cloud image; room for the extracted IPSW images.
	qemu-img create -q -f qcow2 -F qcow2 -b "$vm/ubuntu.img" "$vm/work.qcow2" 24G
fi

say "preparing the job disk"
seed=$vm/job.img
rm -f "$seed"
# Empty (sparse) files via qemu-img: Termux's truncate rejected "-s SIZE" on a
# Samsung phone ("Invalid number: <current directory>").
qemu-img create -q -f raw "$seed" 64M
mformat -i "$seed" -v CIDATA -F ::
cat > "$vm/user-data" <<'EOF'
#cloud-config
# Sweet16 rootfs job: nothing else is configured; the console shows the job.
ssh_pwauth: false
runcmd:
  - [bash, -c, "mkdir -p /mnt/job && mount -t vfat -o rw,uid=0,gid=0 LABEL=CIDATA /mnt/job && bash /mnt/job/guest-job.sh 2>&1 | tee /dev/ttyAMA0 > /mnt/job/job.log"]
EOF
printf 'instance-id: sweet16-%s\nlocal-hostname: sweet16-vm\n' "$(date +%s)" > "$vm/meta-data"
{
	[[ -n ${SWEET16_PROXY:-} ]] && echo "SWEET16_PROXY=$SWEET16_PROXY"
	true
} > "$vm/job.env"
mcopy -i "$seed" "$vm/user-data" "$vm/meta-data" "$vm/job.env" ::
mcopy -i "$seed" "$here/guest-job.sh" "$here/ipsw-extract.py" \
	"$repo/tools/mkiosroot.sh" "$repo/tools/sweet-root-tweaks.py" "$repo/tools/adt.py" ::
[[ -n ${SWEET16_EXTRA_CA:-} ]] && mcopy -i "$seed" "$SWEET16_EXTRA_CA" ::extra-ca.crt

qemu-img create -q -f raw "$out" "$size"
say "starting the VM ($cpus CPUs, $mem MB). The Ubuntu boot alone takes a few minutes in emulation."
say "progress lines start with SWEET16:   (stop: Ctrl-A then X)"
rc=0
qemu-system-aarch64 \
	-M virt -cpu max -smp "$cpus" -m "$mem" \
	-accel tcg,thread=multi,tb-size=512 \
	-bios "$(qpath "$fw")" \
	-nographic -no-reboot \
	-drive if=none,id=sys,file="$(qpath "$vm/work.qcow2")",format=qcow2,cache=writeback,discard=unmap \
	-device virtio-blk-pci,drive=sys,bootindex=0 \
	-drive if=none,id=job,file="$(qpath "$seed")",format=raw \
	-device virtio-blk-pci,drive=job,serial=job \
	-drive if=none,id=ipsw,file="$(qpath "$ipsw")",format=raw,readonly=on \
	-device virtio-blk-pci,drive=ipsw,serial=ipsw \
	-drive if=none,id=rootfs,file="$(qpath "$out")",format=raw,cache=writeback \
	-device virtio-blk-pci,drive=rootfs,serial=rootfs \
	-nic user,model=virtio-net-pci || rc=$?

result=$(mtype -i "$seed" ::result.txt 2>/dev/null | tr -d '\r' || true)
mcopy -n -i "$seed" ::job.log "$(dirname "$out")/rootfs-job.log" 2>/dev/null || true
if [[ $result == OK* ]]; then
	mcopy -n -i "$seed" ::DT-d22ap.bin "$(dirname "$out")/DT-d22ap.bin" 2>/dev/null || true
	[[ ${SWEET16_KEEP_VM:-0} == 1 ]] || rm -f "$vm/work.qcow2"
	say "done: $out"
	say "in Sweet16: tap Import and pick it (DT-d22ap.bin: Developer boot > Pick DeviceTree, optional)"
	exit 0
fi
rm -f "$out"
echo "== the job did not finish (${result:-VM stopped, qemu exit $rc}); log: $(dirname "$out")/rootfs-job.log" >&2
echo "   a re-run with SWEET16_KEEP_VM=1 reuses the VM disk" >&2
exit 1
