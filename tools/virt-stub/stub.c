/*
 * Boot stub of the sweet-virt machine (QEMU virt; Android app).
 *
 * QEMU's loader devices put the kernel image, ADT and boot_args at their
 * physical addresses (tools/mkvirtbundle.py) and start CPU 0 here, at EL1
 * with the MMU off. This stub
 *   1. points QEMU's ramfb display at the framebuffer the kernel draws into
 *      (0x9c000000, 1080x2400 XRGB8888), through the fw_cfg DMA interface,
 *   2. jumps to the kernel entry with x0 = boot_args, as shim/loader.c does
 *      on the phone.
 * The parameters are patched into `params` by mkvirtbundle.py.
 */
#include <stdint.h>

struct params {
	uint64_t magic;         /* "SWVSTUB1" */
	uint64_t boot_args;     /* physical */
	uint64_t entry;         /* physical */
	uint64_t fb_base;
	uint32_t fb_width, fb_height, fb_stride, pad;
};

extern struct params params;
void stub_main(void) __attribute__((noreturn));

#define FW_CFG_BASE     0x09020000ul    /* QEMU virt */
#define FW_CFG_DATA     (FW_CFG_BASE + 0x0)
#define FW_CFG_SEL      (FW_CFG_BASE + 0x8)
#define FW_CFG_DMA      (FW_CFG_BASE + 0x10)
#define FW_CFG_FILE_DIR 0x19

#define DMA_CTL_ERROR   0x01
#define DMA_CTL_SELECT  0x08
#define DMA_CTL_WRITE   0x10

static inline uint16_t be16(uint16_t v) { return __builtin_bswap16(v); }
static inline uint32_t be32(uint32_t v) { return __builtin_bswap32(v); }
static inline uint64_t be64(uint64_t v) { return __builtin_bswap64(v); }

static void
fw_select(uint16_t key)
{
	*(volatile uint16_t *)FW_CFG_SEL = be16(key);
}

static uint8_t
fw_byte(void)
{
	return *(volatile uint8_t *)FW_CFG_DATA;
}

static void
fw_read(void *buf, uint32_t len)
{
	uint8_t *b = buf;
	while (len--) {
		*b++ = fw_byte();
	}
}

struct ramfb_cfg {
	uint64_t addr;
	uint32_t fourcc, flags, width, height, stride;
} __attribute__((packed));

struct dma_access {
	uint32_t control, length;
	uint64_t address;
} __attribute__((aligned(16)));

static struct ramfb_cfg cfg;
static struct dma_access dma;

static int
streq(const char *a, const char *b)
{
	while (*a && *a == *b) {
		a++, b++;
	}
	return *a == *b;
}

static void
ramfb_setup(void)
{
	uint32_t count;
	uint16_t key = 0;

	fw_select(FW_CFG_FILE_DIR);
	fw_read(&count, 4);
	count = be32(count);
	for (uint32_t i = 0; i < count && i < 256; i++) {
		struct {
			uint32_t size;
			uint16_t select, reserved;
			char name[56];
		} f;
		fw_read(&f, sizeof(f));
		f.name[55] = 0;
		if (streq(f.name, "etc/ramfb")) {
			key = be16(f.select);
		}
	}
	if (!key) {
		return;         /* no -device ramfb: boot without a display */
	}
	cfg.addr = be64(params.fb_base);
	cfg.fourcc = be32(0x34325258);  /* DRM_FORMAT_XRGB8888 'XR24' */
	cfg.flags = 0;
	cfg.width = be32(params.fb_width);
	cfg.height = be32(params.fb_height);
	cfg.stride = be32(params.fb_stride);
	dma.control = be32(((uint32_t)key << 16) | DMA_CTL_SELECT | DMA_CTL_WRITE);
	dma.length = be32(sizeof(cfg));
	dma.address = be64((uint64_t)(uintptr_t)&cfg);
	__asm__ volatile("dsb sy" ::: "memory");
	/* Writing the low half of the big-endian address starts the transfer. */
	*(volatile uint32_t *)FW_CFG_DMA = be32((uint32_t)((uint64_t)(uintptr_t)&dma >> 32));
	*(volatile uint32_t *)(FW_CFG_DMA + 4) = be32((uint32_t)(uintptr_t)&dma);
	for (int spin = 0; spin < 10000000; spin++) {
		uint32_t c = be32(*(volatile uint32_t *)&dma.control);
		if ((c & ~DMA_CTL_ERROR) == 0) {
			break;
		}
	}
}

void
stub_main(void)
{
	ramfb_setup();
	register uint64_t x0 __asm__("x0") = params.boot_args;
	register uint64_t x1 __asm__("x1") = params.entry;
	__asm__ volatile("br x1" :: "r"(x0), "r"(x1));
	__builtin_unreachable();
}
