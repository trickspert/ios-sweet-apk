/*
 * sweet-sh: a tiny interactive PID 1 for verifying real XNU execution
 * (QEMU or phone), built like sweet-init.S: tools/mkinit.py wraps the
 * assembled __text into a static, ad-hoc signed Mach-O and the RAM disk
 * (mockfs, rd=md0) serves it as /sbin/launchd.
 *
 * Everything it prints about the system comes from the running kernel
 * (sysctl), nothing is canned:
 *   uname   kern.version   (the kernel's build string)
 *   hw      hw.machine, hw.model, hw.ncpu
 *   pid     getpid()
 *   help
 * Console in canonical mode: read() returns one line, the tty echoes.
 *
 * Position independent, no relocations, no writable data: every reference
 * is adr, buffers are on the stack. Raw syscalls: x16, svc #0x80, carry on
 * error.
 *
 * Stack frame (x29 = base): +0 line[256], +256 out[512], +768 len (u64),
 * +776 int value.
 */
#define SYS_read		3
#define SYS_write		4
#define SYS_open		5
#define SYS_getpid		20
#define SYS_sysctlbyname	274
#define O_RDWR			2

	.text
	.globl	_start
	.p2align 2
_start:
	mov	x9, sp			/* align like crt0 (see sweet-init.S) */
	and	x9, x9, #~15
	sub	x9, x9, #1024
	mov	sp, x9
	mov	x29, sp

	adr	x0, s_console
	mov	x1, #O_RDWR
	mov	x2, #0
	mov	x16, #SYS_open
	svc	#0x80
	b.cs	hang
	mov	x19, x0			/* console fd */

	adr	x1, s_banner
	bl	puts_z
	mov	x16, #SYS_getpid
	svc	#0x80
	bl	put_dec
	adr	x1, s_banner2
	bl	puts_z
	bl	cmd_uname
	adr	x1, s_hint
	bl	puts_z

prompt:
	adr	x1, s_prompt
	bl	puts_z
	mov	x0, x19
	mov	x1, x29			/* line */
	mov	x2, #255
	mov	x16, #SYS_read
	svc	#0x80
	b.cs	prompt
	cbz	x0, prompt
	/* NUL-terminate, strip the newline */
	add	x1, x29, x0
	strb	wzr, [x1]
	sub	x1, x1, #1
	ldrb	w2, [x1]
	cmp	w2, #'\n'
	b.ne	1f
	strb	wzr, [x1]
1:	ldrb	w2, [x29]
	cbz	w2, prompt

	adr	x1, s_uname
	bl	streq_line
	cbnz	x0, 2f
	bl	cmd_uname
	b	prompt
2:	adr	x1, s_hw
	bl	streq_line
	cbnz	x0, 3f
	bl	cmd_hw
	b	prompt
3:	adr	x1, s_pid
	bl	streq_line
	cbnz	x0, 4f
	mov	x16, #SYS_getpid
	svc	#0x80
	bl	put_dec
	adr	x1, s_nl
	bl	puts_z
	b	prompt
4:	adr	x1, s_help
	bl	streq_line
	cbnz	x0, 5f
	adr	x1, s_helptext
	bl	puts_z
	b	prompt
5:	adr	x1, s_unknown
	bl	puts_z
	mov	x1, x29
	bl	puts_z
	adr	x1, s_unknown2
	bl	puts_z
	b	prompt

/* uname: kern.version */
cmd_uname:
	stp	x30, xzr, [sp, #-16]!
	adr	x0, s_kern_version
	bl	sysctl_str
	ldp	x30, xzr, [sp], #16
	ret

/* hw: hw.machine, hw.model, hw.ncpu */
cmd_hw:
	stp	x30, xzr, [sp, #-16]!
	adr	x1, s_lbl_machine
	bl	puts_z
	adr	x0, s_hw_machine
	bl	sysctl_str
	adr	x1, s_lbl_model
	bl	puts_z
	adr	x0, s_hw_model
	bl	sysctl_str
	adr	x1, s_lbl_ncpu
	bl	puts_z
	adr	x0, s_hw_ncpu
	add	x1, x29, #776
	mov	x9, #4
	str	x9, [x29, #768]
	add	x2, x29, #768
	mov	x3, #0
	mov	x4, #0
	mov	x16, #SYS_sysctlbyname
	svc	#0x80
	b.cs	1f
	ldr	w0, [x29, #776]
	bl	put_dec
1:	adr	x1, s_nl
	bl	puts_z
	ldp	x30, xzr, [sp], #16
	ret

/* Print the string sysctl named by x0, then a newline. */
sysctl_str:
	stp	x30, xzr, [sp, #-16]!
	add	x1, x29, #256		/* out */
	mov	x9, #511
	str	x9, [x29, #768]
	add	x2, x29, #768
	mov	x3, #0
	mov	x4, #0
	mov	x16, #SYS_sysctlbyname
	svc	#0x80
	b.cs	2f
	ldr	x9, [x29, #768]
	add	x10, x29, #256
	strb	wzr, [x10, x9]		/* terminate (len includes the NUL anyway) */
	mov	x1, x10
	bl	puts_z
	adr	x1, s_nl
	bl	puts_z
	b	3f
2:	adr	x1, s_err
	bl	puts_z
3:	ldp	x30, xzr, [sp], #16
	ret

/* x0 = 0 if the line equals the NUL-terminated string at x1 */
streq_line:
	mov	x2, x29
1:	ldrb	w3, [x1], #1
	ldrb	w4, [x2], #1
	cmp	w3, w4
	b.ne	2f
	cbnz	w3, 1b
	mov	x0, #0
	ret
2:	mov	x0, #1
	ret

/* write the NUL-terminated string at x1 */
puts_z:
	mov	x2, x1
1:	ldrb	w3, [x2]
	cbz	w3, 2f
	add	x2, x2, #1
	b	1b
2:	sub	x2, x2, x1
	cbz	x2, 3f
	mov	x0, x19
	mov	x16, #SYS_write
	svc	#0x80
3:	ret

/* write w0 in decimal (uses out[] at x29+256) */
put_dec:
	stp	x30, xzr, [sp, #-16]!
	mov	w0, w0
	add	x1, x29, #256 + 32
	strb	wzr, [x1]
	mov	x3, #10
1:	udiv	x4, x0, x3
	msub	x5, x4, x3, x0
	add	w5, w5, #'0'
	strb	w5, [x1, #-1]!
	mov	x0, x4
	cbnz	x0, 1b
	bl	puts_z
	ldp	x30, xzr, [sp], #16
	ret

hang:
	b	hang

s_console:	.asciz	"/dev/console"
s_banner:	.asciz	"\n*** SWEET userspace: sweet-sh running as PID "
s_banner2:	.asciz	" ***\nkernel: "
s_hint:		.asciz	"commands: uname hw pid help\n"
s_prompt:	.asciz	"sweet# "
s_uname:	.asciz	"uname"
s_hw:		.asciz	"hw"
s_pid:		.asciz	"pid"
s_help:		.asciz	"help"
s_helptext:	.asciz	"uname  kern.version of the running kernel\nhw     hw.machine, hw.model, hw.ncpu\npid    process id\n"
s_unknown:	.asciz	"sweet-sh: "
s_unknown2:	.asciz	": unknown command (try help)\n"
s_kern_version:	.asciz	"kern.version"
s_hw_machine:	.asciz	"hw.machine"
s_hw_model:	.asciz	"hw.model"
s_hw_ncpu:	.asciz	"hw.ncpu"
s_lbl_machine:	.asciz	"hw.machine: "
s_lbl_model:	.asciz	"hw.model:   "
s_lbl_ncpu:	.asciz	"hw.ncpu:    "
s_err:		.asciz	"(sysctl failed)\n"
s_nl:		.asciz	"\n"
