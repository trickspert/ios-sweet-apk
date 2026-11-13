ENTRY(_start)
SECTIONS
{
	. = 0x40200000;		/* STUB_PA in tools/mkvirtbundle.py */
	.text : { *(.text.start) *(.text*) }
	.rodata : { *(.rodata*) }
	.data : { *(.data*) }
	. = ALIGN(16);
	.bss : { *(.bss*) *(COMMON) }
	/DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
