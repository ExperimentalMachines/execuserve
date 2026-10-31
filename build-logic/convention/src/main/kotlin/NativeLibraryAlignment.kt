import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Google Play's 16 KB page-size rule, applied to the native libraries an app packages.
 *
 * Play refuses an app whose `lib/arm64-v8a/` or `lib/x86_64/` holds an ELF file with a `LOAD`
 * segment aligned below 16 KB, because a phone running 16 KB pages cannot map it. Two kinds of file are in the APK:
 *
 * - Libraries Android's linker loads. Their alignment is decided when they are linked, so a 4 KB
 *   one has to be rebuilt; [requireAligned] stops the build on one rather than letting it reach
 *   Play.
 * - Qualcomm's Hexagon skeletons (`libQnnHtpV*Skel.so`). Android's linker never loads them; the
 *   DSP does, by path. Qualcomm ships them with 4 KB alignment (every `qnn-runtime` through 2.51),
 *   but each `LOAD` segment's file offset equals its address, so the file is laid out for any
 *   power-of-two alignment and only the `p_align` field says otherwise. [raiseHexagonAlignment]
 *   rewrites that field and nothing else, and refuses a file whose layout does not allow it.
 */
object NativeLibraryAlignment {
    const val PAGE = 16_384L

    private const val PT_LOAD = 1
    private const val EM_QDSP6 = 164
    private const val ELF_CLASS_32 = 1
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_LSB = 1
    private const val HEADER_BYTES = 64

    /** One `LOAD` segment: where it sits in the file and in memory, and where its `p_align` field is. */
    data class Load(val offset: Long, val vaddr: Long, val align: Long, val alignFieldAt: Long)

    /** An ELF file's machine, class and `LOAD` segments, or null when [file] is not little-endian ELF. */
    data class Elf(val machine: Int, val is64: Boolean, val loads: List<Load>)

    fun read(file: File): Elf? = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < HEADER_BYTES) return null
        val header = ByteArray(HEADER_BYTES).also { raf.readFully(it) }
        val magic = header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        if (!magic || header[5].toInt() != ELF_DATA_LSB) return null
        val is64 = when (header[4].toInt()) {
            ELF_CLASS_32 -> false
            ELF_CLASS_64 -> true
            else -> return null
        }
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val machine = h.getShort(18).toInt() and 0xffff
        val phoff = if (is64) h.getLong(32) else h.getInt(28).toLong() and 0xffffffffL
        val phentsize = (if (is64) h.getShort(54) else h.getShort(42)).toInt() and 0xffff
        val phnum = (if (is64) h.getShort(56) else h.getShort(44)).toInt() and 0xffff
        val loads = (0 until phnum).mapNotNull { i ->
            val at = phoff + i.toLong() * phentsize
            val entry = ByteArray(phentsize).also { raf.seek(at); raf.readFully(it) }
            val p = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
            if (p.getInt(0) != PT_LOAD) return@mapNotNull null
            if (is64) {
                Load(offset = p.getLong(8), vaddr = p.getLong(16), align = p.getLong(48), alignFieldAt = at + 48)
            } else {
                Load(
                    offset = p.getInt(4).toLong() and 0xffffffffL,
                    vaddr = p.getInt(8).toLong() and 0xffffffffL,
                    align = p.getInt(28).toLong() and 0xffffffffL,
                    alignFieldAt = at + 28,
                )
            }
        }
        Elf(machine, is64, loads)
    }

    /**
     * Raises the `LOAD` alignment of a Hexagon DSP library to 16 KB, in place. Returns true when
     * the file changed. Any other machine is left alone: its alignment is the linker's to set.
     */
    fun raiseHexagonAlignment(file: File): Boolean {
        val elf = read(file) ?: return false
        if (elf.machine != EM_QDSP6 || elf.is64) return false
        val low = elf.loads.filter { it.align < PAGE }
        if (low.isEmpty()) return false
        low.firstOrNull { it.offset % PAGE != it.vaddr % PAGE }?.let {
            throw GradleException(
                "${file.name}: a LOAD segment at offset 0x${it.offset.toString(16)} maps to 0x${it.vaddr.toString(16)}, " +
                    "which 16 KB alignment does not allow; this Hexagon library needs a new build from Qualcomm.",
            )
        }
        RandomAccessFile(file, "rw").use { raf ->
            val field = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(PAGE.toInt()).array()
            low.forEach { raf.seek(it.alignFieldAt); raf.write(field) }
        }
        return true
    }

    /** The [libraries] in [roots] whose `LOAD` alignment is below 16 KB, with the alignment found. */
    fun misaligned(roots: Iterable<File>, exempt: Set<String>): List<String> = libraries(roots)
        .filter { it.name !in exempt }
        .mapNotNull { lib ->
            val elf = read(lib) ?: return@mapNotNull "${lib.parentFile.name}/${lib.name} (not an ELF file)"
            val lowest = elf.loads.minOfOrNull { it.align } ?: return@mapNotNull null
            if (lowest < PAGE) "${lib.parentFile.name}/${lib.name} (0x${lowest.toString(16)})" else null
        }

    /**
     * The libraries under `lib/<abi>/` for the 64-bit ABIs, the ones the rule covers. AGP strips
     * every ABI its dependencies bring (fbjni's 32-bit copies among them) and filters to the app's
     * ABIs only when packaging, so the others are not looked at.
     */
    fun libraries(roots: Iterable<File>): List<File> = roots.filter { it.isDirectory }
        .flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "so" && it.parentFile.parentFile?.name == "lib" && it.parentFile.name in ABIS_64 }
                .toList()
        }
        .sortedBy { it.path }

    private val ABIS_64 = setOf("arm64-v8a", "x86_64")

    /**
     * After AGP's strip task, the last step before the libraries are packaged into the APK and
     * the bundle: raise the Hexagon skeletons, then stop the build if anything is still below
     * 16 KB. [exempt] names libraries a non-Play build may carry anyway.
     */
    fun enforceAfter(task: Task, exempt: Set<String>) {
        task.doLast(
            object : Action<Task> {
                override fun execute(stripped: Task) {
                    val roots = stripped.outputs.files.files
                    val raised = libraries(roots).filter(::raiseHexagonAlignment)
                    if (raised.isNotEmpty()) stripped.logger.info("16 KB: raised LOAD alignment of ${raised.joinToString { it.name }}")
                    val bad = misaligned(roots, exempt)
                    if (bad.isNotEmpty()) {
                        throw GradleException(
                            "These native libraries are aligned below 16 KB, so Google Play would refuse the app and a " +
                                "16 KB-page phone could not load them:\n  " + bad.joinToString("\n  ") +
                                "\nRelink them with -Wl,-z,max-page-size=16384 (NDK r27 and older) or NDK r28 or later.",
                        )
                    }
                }
            },
        )
    }
}
