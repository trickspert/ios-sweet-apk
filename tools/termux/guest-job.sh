#!/bin/bash
# Runs as root inside the Ubuntu VM that tools/termux/mkiosroot-termux.sh
# starts (cloud-init runcmd). The job disk (FAT, label CIDATA) holds this
# script, the repo tools and job.env; the IPSW and the output image are
# virtio disks:
#   /dev/disk/by-id/virtio-ipsw     the .ipsw file, read-only
#   /dev/disk/by-id/virtio-rootfs   the output image (rootfs.img on the phone)
# Progress lines start with "SWEET16:"; the result goes to the job disk as
# result.txt ("OK" or "FAILED: ..."), then the VM powers off.
set -euo pipefail
JOB=/mnt/job
IPSW=/dev/disk/by-id/virtio-ipsw
OUT=/dev/disk/by-id/virtio-rootfs
APFS_FUSE_COMMIT=66b86bd525e8cb90f9012543be89b1f092b75cf3
say() { echo "SWEET16: $*"; }
finish() {
	local rc=$?
	if [[ $rc == 0 ]]; then echo OK > "$JOB/result.txt"; say "DONE: rootfs image written"
	else echo "FAILED: exit $rc at guest-job.sh line ${fail_line:-?}" > "$JOB/result.txt"; say "FAILED (exit $rc at line ${fail_line:-?}), see the log above"
	fi
	sync
	umount /mnt/root 2>/dev/null || true
	umount "$JOB" 2>/dev/null || true
	sync
	[[ ${SWEET16_NO_POWEROFF:-0} == 1 ]] || poweroff
}
fail_line=""
trap 'fail_line=$LINENO' ERR
trap finish EXIT

# shellcheck disable=SC1091
[[ -f $JOB/job.env ]] && . "$JOB/job.env"
if [[ -n ${SWEET16_PROXY:-} ]]; then
	# Only when the host needs one (set by the host script; not on a phone).
	export http_proxy=$SWEET16_PROXY https_proxy=$SWEET16_PROXY
	echo "Acquire::http::Proxy \"$SWEET16_PROXY\"; Acquire::https::Proxy \"$SWEET16_PROXY\";" > /etc/apt/apt.conf.d/99sweet16-proxy
	# Some proxies only tunnel HTTPS (CONNECT): use the mirror over https.
	sed -i 's#http://ports.ubuntu.com#https://ports.ubuntu.com#g' /etc/apt/sources.list.d/*.sources /etc/apt/sources.list 2>/dev/null || true
fi
if [[ -f $JOB/extra-ca.crt ]]; then
	cp "$JOB/extra-ca.crt" /usr/local/share/ca-certificates/sweet16-extra.crt
	update-ca-certificates >/dev/null
fi

say "VM up: $(nproc) CPUs, $(free -m | awk '/Mem:/{print $2}') MB RAM, kernel $(uname -r)"
for d in "$IPSW" "$OUT"; do
	[[ -b $d ]] || { say "missing disk $d"; exit 1; }
done
say "IPSW disk $(($(blockdev --getsize64 "$IPSW") >> 20)) MiB, output disk $(($(blockdev --getsize64 "$OUT") >> 30)) GiB"

say "installing tools (apt)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends \
	hfsprogs rsync python3 lzfse fuse3 libfuse3-dev zlib1g-dev libbz2-dev libattr1-dev \
	build-essential cmake git ca-certificates "linux-modules-extra-$(uname -r)"
modprobe hfsplus

if ! command -v apfs-fuse >/dev/null; then
	say "building apfs-fuse ($APFS_FUSE_COMMIT)"
	rm -rf /opt/apfs-fuse
	git init -q /opt/apfs-fuse
	git -C /opt/apfs-fuse fetch -q --depth 1 https://github.com/sgan81/apfs-fuse "$APFS_FUSE_COMMIT"
	git -C /opt/apfs-fuse checkout -q FETCH_HEAD
	git -C /opt/apfs-fuse submodule update -q --init --depth 1
	cmake -S /opt/apfs-fuse -B /opt/apfs-fuse/build -DCMAKE_BUILD_TYPE=Release >/dev/null
	make -C /opt/apfs-fuse/build -j"$(nproc)" apfs-fuse >/dev/null
	install -m 0755 /opt/apfs-fuse/build/apfs-fuse /usr/local/bin/apfs-fuse
fi

say "extracting the root images from the IPSW"
mkdir -p /root/ipsw
python3 "$JOB/ipsw-extract.py" "$IPSW" /root/ipsw d22ap

say "building the HFS+ root (tools/mkiosroot.sh)"
bash "$JOB/mkiosroot.sh" /root/ipsw/os.dmg /root/ipsw/sysos.dmg /root/ipsw/appos.dmg "$OUT"

say "applying the bring-up changes (tools/sweet-root-tweaks.py)"
fsck.hfsplus -f -y "$OUT" >/dev/null || true
mkdir -p /mnt/root
mount -t hfsplus -o force "$OUT" /mnt/root
python3 "$JOB/sweet-root-tweaks.py" /mnt/root
df -h /mnt/root
umount /mnt/root
fsck.hfsplus -f -y "$OUT" | grep -v 'Illegal name' || true
fsck.hfsplus -f -n "$OUT"

# The raw device tree for Sweet16's Developer boot (/product for MobileGestalt).
if [[ -f /root/ipsw/DeviceTree.d22ap.im4p ]]; then
	if python3 "$JOB/adt.py" unwrap /root/ipsw/DeviceTree.d22ap.im4p "$JOB/DT-d22ap.bin" 2>/dev/null; then
		say "device tree: DT-d22ap.bin ($(stat -c %s "$JOB/DT-d22ap.bin") bytes)"
	else
		say "device tree: could not unwrap (optional, skipped)"
	fi
fi
