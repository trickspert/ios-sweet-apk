package com.iossweet.sweet16

/**
 * "Performance check": the kernel's CPU monitor lines (SWEET TOP, every 10 s,
 * bsd/dev/sweet_virtio.c) averaged over the last few samples: which iOS
 * processes use the emulated CPUs. No Android dependencies.
 */
object PerfCheck {
    private val entry = Regex("""(\S*) (\d+)%""")

    fun report(console: String, cores: Int, samples: Int = 6): String {
        val lines = console.lineSequence().filter { it.contains("SWEET TOP ") }.toList().takeLast(samples)
        if (lines.isEmpty()) return "No CPU samples yet: iOS must run for 20 seconds with this kernel."
        val sum = HashMap<String, Int>()
        var total = 0
        for (l in lines) {
            val body = l.substringAfter("s:").substringBefore(" total ")
            for (m in entry.findAll(body)) sum.merge(m.groupValues[1].ifEmpty { "kernel" }, m.groupValues[2].toInt(), Int::plus)
            total += l.substringAfter(" total ").trim().removeSuffix("%").toIntOrNull() ?: 0
        }
        val n = lines.size
        return buildString {
            append("CPU use, average of the last ${n * 10} s (100% = one of the $cores emulated CPUs):\n\n")
            for ((name, v) in sum.entries.sortedByDescending { it.value }.take(12)) {
                append("%4d%%  %s\n".format(v / n, name))
            }
            append("\nAll processes: ${total / n}% of ${cores * 100}%\n")
        }
    }
}
