/* Entry of the sweet-virt boot stub (see stub.c). Position independent. */
	.section .text.start, "ax"
	.globl _start
_start:
	b	1f
	.balign 8
	.globl params
params:			/* struct params, patched by tools/mkvirtbundle.py */
	.ascii	"SWVSTUB1"
	.quad	0, 0, 0x9c000000
	.word	1080, 2400, 4320, 0
1:
	msr	daifset, #0xf
	adr	x9, stack_top
	mov	sp, x9
	bl	stub_main
2:	wfe
	b	2b

	.section .bss
	.balign 16
	.space	0x2000
stack_top:
