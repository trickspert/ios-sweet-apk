/*
 * nettest: a PID 1 for Developer boot that checks the sweet-virt clock and
 * network without iOS (no libc: raw XNU syscalls, everything on the stack).
 *
 *   1. gettimeofday -> the date (PL031 RTC, SweetPlatformExpert.cpp)
 *   2. DNS query for example.com to QEMU's resolver 10.0.2.3 (UDP)
 *   3. HTTP GET from the host, 10.0.2.2:8080 (TCP through QEMU's NAT)
 *
 * Boot with sweetnet=2 (the kernel gives en0 10.0.2.15/24) and QEMU
 * -netdev user,id=n0 -device virtio-net-device,netdev=n0.
 * Build: tools/virt-tests/build-nettest.sh OUT
 */
typedef unsigned long size_t;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;

#define SYS_exit 1
#define SYS_write 4
#define SYS_open 5
#define SYS_recvfrom 29
#define SYS_select 93
#define SYS_socket 97
#define SYS_connect 98
#define SYS_setsockopt 105
#define SYS_gettimeofday 116
#define SYS_sendto 133
#define SYS_read 3
#define SYS_dup2 90

static long
sc(long n, long a, long b, long c, long d, long e, long f)
{
	register long x0 __asm__("x0") = a;
	register long x1 __asm__("x1") = b;
	register long x2 __asm__("x2") = c;
	register long x3 __asm__("x3") = d;
	register long x4 __asm__("x4") = e;
	register long x5 __asm__("x5") = f;
	register long x16 __asm__("x16") = n;
	/* Like a call: the kernel may change any caller-saved register. */
	__asm__ volatile ("svc #0x80\n\tb.cc 1f\n\tneg x0, x0\n1:"
	    : "+r"(x0), "+r"(x1), "+r"(x2), "+r"(x3), "+r"(x4), "+r"(x5), "+r"(x16)
	    : : "memory", "cc", "x6", "x7", "x8", "x9", "x10", "x11", "x12", "x13", "x14", "x15", "x17");
	return x0;
}

void *
memset(void *d, int c, size_t n)
{
	u8 *p = d;
	while (n--) {
		*p++ = (u8)c;
	}
	return d;
}

void *
memcpy(void *d, const void *s, size_t n)
{
	u8 *p = d;
	const u8 *q = s;
	while (n--) {
		*p++ = *q++;
	}
	return d;
}

#define con 1           /* /dev/console is dup2()ed to fd 1 */

static size_t
slen(const char *s)
{
	size_t n = 0;
	while (s[n]) {
		n++;
	}
	return n;
}

static void
put(const char *s)
{
	sc(SYS_write, con, (long)s, (long)slen(s), 0, 0, 0);
}

static void
putn(unsigned long v, int width)
{
	char b[24];
	int i = 23;
	b[i] = 0;
	do {
		b[--i] = (char)('0' + v % 10);
		v /= 10;
		width--;
	} while (v || width > 0);
	put(b + i);
}

struct sockaddr_in {
	u8 len, family;
	u16 port;
	u32 addr;
	u8 zero[8];
};

static u16
be16(u16 v)
{
	return (u16)((v >> 8) | (v << 8));
}

static struct sockaddr_in
sa(u8 a, u8 b, u8 c, u8 d, u16 port)
{
	struct sockaddr_in s;
	memset(&s, 0, sizeof(s));
	s.len = sizeof(s);
	s.family = 2;
	s.port = be16(port);
	s.addr = (u32)a | ((u32)b << 8) | ((u32)c << 16) | ((u32)d << 24);
	return s;
}

static void
timeout(long fd, long secs)
{
	long tv[2] = { secs, 0 };       /* struct timeval: tv_sec, tv_usec (int + pad) */
	sc(SYS_setsockopt, fd, 0xffff, 0x1006, (long)tv, 16, 0);       /* SOL_SOCKET, SO_RCVTIMEO */
	sc(SYS_setsockopt, fd, 0xffff, 0x1005, (long)tv, 16, 0);       /* SO_SNDTIMEO */
}

/* days since 1970-01-01 -> y/m/d (civil_from_days, H. Hinnant) */
static void
date(long z, long *y, unsigned *m, unsigned *d)
{
	z += 719468;
	long era = (z >= 0 ? z : z - 146096) / 146097;
	unsigned long doe = (unsigned long)(z - era * 146097);
	unsigned long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
	long yy = (long)yoe + era * 400;
	unsigned long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
	unsigned long mp = (5 * doy + 2) / 153;
	*d = (unsigned)(doy - (153 * mp + 2) / 5 + 1);
	*m = (unsigned)(mp < 10 ? mp + 3 : mp - 9);
	*y = yy + (*m <= 2);
}

static void
test_time(void)
{
	long tv[2] = { 0, 0 };
	long r = sc(SYS_gettimeofday, (long)tv, 0, 0, 0, 0, 0);
	long secs = tv[0];
	long y;
	unsigned m, d;
	date(secs / 86400, &y, &m, &d);
	put("nettest: time ");
	putn((unsigned long)secs, 1);
	put(" = ");
	putn((unsigned long)y, 4); put("-"); putn(m, 2); put("-"); putn(d, 2); put(" ");
	putn((unsigned long)(secs % 86400) / 3600, 2); put(":");
	putn((unsigned long)(secs % 3600) / 60, 2); put(":");
	putn((unsigned long)secs % 60, 2);
	put(r < 0 ? " UTC (gettimeofday failed)\n" : " UTC\n");
}

static void
test_dns(void)
{
	u8 q[64], a[512];
	int n = 0;
	static const char name[] = "\7example\3com";
	memset(q, 0, sizeof(q));
	q[0] = 0x53; q[1] = 0x77;               /* id */
	q[2] = 0x01;                            /* RD */
	q[5] = 1;                               /* QDCOUNT */
	n = 12;
	memcpy(q + n, name, sizeof(name));      /* includes the root label 0 */
	n += sizeof(name);
	q[n++] = 0; q[n++] = 1;                 /* QTYPE A */
	q[n++] = 0; q[n++] = 1;                 /* QCLASS IN */

	long fd = sc(SYS_socket, 2, 2, 0, 0, 0, 0);     /* AF_INET, SOCK_DGRAM */
	if (fd < 0) {
		put("nettest: dns: socket failed\n");
		return;
	}
	timeout(fd, 5);
	struct sockaddr_in to = sa(10, 0, 2, 3, 53);
	long r = sc(SYS_sendto, fd, (long)q, n, 0, (long)&to, sizeof(to));
	if (r < 0) {
		put("nettest: dns: sendto error ");
		putn((unsigned long)-r, 1);
		put("\n");
		return;
	}
	r = sc(SYS_recvfrom, fd, (long)a, sizeof(a), 0, 0, 0);
	if (r < 12) {
		put("nettest: dns: no answer (error ");
		putn((unsigned long)(r < 0 ? -r : 0), 1);
		put(")\n");
		return;
	}
	unsigned an = ((unsigned)a[6] << 8) | a[7];
	put("nettest: dns: ");
	putn((unsigned long)r, 1);
	put(" bytes, rcode ");
	putn(a[3] & 15, 1);
	put(", answers ");
	putn(an, 1);
	/* skip the question, then print the first A record */
	long p = 12;
	while (p < r && a[p]) {
		p += a[p] + 1;
	}
	p += 5;
	for (unsigned i = 0; i < an && p + 12 <= r; i++) {
		if ((a[p] & 0xc0) == 0xc0) {
			p += 2;
		} else {
			while (p < r && a[p]) {
				p += a[p] + 1;
			}
			p++;
		}
		unsigned type = ((unsigned)a[p] << 8) | a[p + 1];
		unsigned rdlen = ((unsigned)a[p + 8] << 8) | a[p + 9];
		p += 10;
		if (type == 1 && rdlen == 4 && p + 4 <= r) {
			put(", example.com = ");
			putn(a[p], 1); put("."); putn(a[p + 1], 1); put(".");
			putn(a[p + 2], 1); put("."); putn(a[p + 3], 1);
			break;
		}
		p += rdlen;
	}
	put("\n");
}

static void
test_http(void)
{
	char buf[256];
	long fd = sc(SYS_socket, 2, 1, 0, 0, 0, 0);     /* SOCK_STREAM */
	if (fd < 0) {
		put("nettest: http: socket failed\n");
		return;
	}
	timeout(fd, 10);
	struct sockaddr_in to = sa(10, 0, 2, 2, 8080);
	long r = sc(SYS_connect, fd, (long)&to, sizeof(to), 0, 0, 0);
	if (r < 0) {
		put("nettest: http: connect error ");
		putn((unsigned long)-r, 1);
		put("\n");
		return;
	}
	static const char req[] = "GET /sweet16-nettest HTTP/1.0\r\nHost: 10.0.2.2\r\n\r\n";
	sc(SYS_write, fd, (long)req, sizeof(req) - 1, 0, 0, 0);
	long total = 0, n;
	int shown = 0;
	while ((n = sc(SYS_read, fd, (long)buf, sizeof(buf) - 1, 0, 0, 0)) > 0) {
		if (!shown) {
			buf[n] = 0;
			for (long i = 0; i < n; i++) {
				if (buf[i] == '\r' || buf[i] == '\n') {
					buf[i] = 0;
					break;
				}
			}
			put("nettest: http: ");
			put(buf);
			shown = 1;
		}
		total += n;
	}
	put(shown ? ", " : "nettest: http: no data, ");
	putn((unsigned long)total, 1);
	put(" bytes\n");
}

void _start(void) __attribute__((noreturn));

void
_start(void)
{
	static const char dev[] = "/dev/console";
	long fd = sc(SYS_open, (long)dev, 2, 0, 0, 0, 0);
	if (fd >= 0 && fd != 1) {
		sc(SYS_dup2, fd, 1, 0, 0, 0, 0);
	}
	put("\n*** nettest: sweet-virt clock and network check ***\n");
	test_time();
	test_dns();
	test_http();
	put("nettest: done\n");
	for (;;) {
		long tv[2] = { 3600, 0 };
		sc(SYS_select, 0, 0, 0, 0, (long)tv, 0);
	}
}
