package com.mit.reader.data

import be.stef.rar.Unrar5j
import java.io.File
import java.nio.charset.Charset
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 漫画压缩包解析（CBZ = ZIP / CBR = RAR4/RAR5，以及普通 zip/rar）：
 * 把包里所有图片按**文件名自然顺序**抽成页（1.jpg < 2.jpg < 10.jpg，不会排成 1,10,2）。
 * 整条路径参与自然排序，多章节目录也能排好；输出统一压平成 pages/page-0001.ext。
 *
 * 合集包拆分（extractMulti）：顶层每个文件夹 = 一本书（夹内图片自然序），
 * 根目录散图单独成一本；单本包（图片都在根目录 / 只有一个包裹文件夹）= 1 本。
 *
 * RAR 用纯 Java 的 unrar5j（junrar 不支持 RAR5，会抛 UnsupportedRarV5Exception）。
 * 它只提供「整包解到目录」的接口，所以先解到临时目录再挑图；其 unpackedFiles 实测为空，不能依赖。
 */
object ArchiveParser {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "avif")

    /** 按文件头嗅探是否是 ZIP（PK\x03\x04）。 */
    fun isZip(f: File): Boolean = head(f, 4)?.let {
        it[0] == 0x50.toByte() && it[1] == 0x4B.toByte() && it[2] == 0x03.toByte() && it[3] == 0x04.toByte()
    } ?: false

    /** 按文件头嗅探是否是 RAR（Rar!\x1a\x07，RAR4/RAR5 通用）。 */
    fun isRar(f: File): Boolean = head(f, 7)?.let {
        it[0] == 'R'.code.toByte() && it[1] == 'a'.code.toByte() && it[2] == 'r'.code.toByte() &&
            it[3] == '!'.code.toByte() && it[4] == 0x1A.toByte() && it[5] == 0x07.toByte()
    } ?: false

    /**
     * 合集压缩包拆分导入：
     * - 顶层有文件夹 → 每个顶层文件夹一本书（夹内图片自然序），根目录散图单独一本排最前；
     * - 图片全在根目录 / 只有一个包裹文件夹 → 1 本（和普通单本包行为一致）。
     * 每本书的页写到 outRoot/vol-<序号>/pages/page-0001.ext，标题 = 文件夹名（根散图 = 包名）。
     */
    fun extractMulti(pack: File, outRoot: File): List<ParsedBook> {
        val books = mutableListOf<ParsedBook>()
        val budget = ExtractBudget()   // 05-中3：单次解压预算（累计字节 ≤ 2GB、图片条目 ≤ 20000）+ 失败页计数
        if (isRar(pack)) {
            val tmp = File(outRoot, "rar_tmp").apply { mkdirs() }
            try {
                extractRar(pack, tmp)
                val rels = tmp.walkTopDown()
                    .filter { it.isFile && isImage(it.name) }
                    .map { rel(tmp, it).replace('\\', '/') }
                    .sortedWith { a, b -> naturalCompare(a, b) }
                    .toList()
                require(rels.isNotEmpty()) { "压缩包里没找到图片（支持 jpg/png/webp/gif/bmp/avif）" }
                requireExtractBudget(rels.size)
                groupsOf(rels, pack.nameWithoutExtension).forEachIndexed { gi, group ->
                    books += writeGroup(outRoot, gi, group) { relPath, dest ->
                        val src = File(tmp, relPath)
                        val ok = moveInto(src, dest) != null
                        if (ok) {
                            budget.addBytes(dest.length())   // RAR 已被 native 库整包解出，至少把入库量纳入预算
                            budget.addEntry()
                        } else {
                            budget.failed++
                        }
                        ok
                    }
                }
            } finally {
                tmp.deleteRecursively()   // 图片是被 move 走的，剩下的残渣连 tmp 一起清
            }
        } else {
            // 修复（05-低8）：条目名先按 UTF-8 解码扫一遍（现状行为）；一个图片条目都没有时
            // 再用 GBK 重试一遍（Windows 压缩工具常以 GBK 打包且不标 UTF-8 位），取图片条目
            // 非空的那一遍结果。两轮都只读中央目录，数据仍按需解压，预算逻辑不变。
            var zip = ZipFile(pack)
            var byRel = zipImageEntries(zip)
            if (byRel.isEmpty()) {
                zip.close()
                zip = ZipFile(pack, Charset.forName("GBK"))
                byRel = zipImageEntries(zip)
            }
            zip.use { zf ->
                // 有的打包工具用反斜杠存路径（BookA\001.jpg），归一化成 / 才能正确分组；
                // 取流时仍要用原始条目名，所以留一张 归一化名 -> 条目 的映射（见 zipImageEntries）
                val rels = byRel.keys.sortedWith { a, b -> naturalCompare(a, b) }.toList()
                require(rels.isNotEmpty()) { "压缩包里没找到图片（支持 jpg/png/webp/gif/bmp/avif）" }
                requireExtractBudget(rels.size)
                groupsOf(rels, pack.nameWithoutExtension).forEachIndexed { gi, group ->
                    books += writeGroup(outRoot, gi, group) { relPath, dest ->
                        val e = byRel[relPath] ?: return@writeGroup false
                        writeStream(zf.getInputStream(e), dest, budget) != null
                    }
                }
            }
        }
        // 05-中3：有解压失败的页就整包失败并报数，不再静默缺页入库
        if (budget.failed > 0) {
            throw IllegalStateException("${budget.failed} 页解压失败已跳过")
        }
        return books
    }

    /** 图片条目数预检（05-中3）：超过上限直接中止，不再逐条解压。 */
    private fun requireExtractBudget(count: Int) {
        if (count > ExtractBudget.MAX_ENTRIES) {
            throw ArchiveExtractLimitException("压缩包图片条目数超过 ${ExtractBudget.MAX_ENTRIES} 上限，已中止导入")
        }
    }

    private data class Group(val title: String, val paths: List<String>)

    /**
     * 分组（递归按「单层图片文件夹 = 一本书」拆）：
     * - 某层直接有图片、且没有含图的子文件夹 → 这一层就是一本书；
     * - 某层还有含图的子文件夹 → 本层不是一本书，往下拆（外面套几层包裹文件夹都能穿透）；
     * - 本层的散图（和子文件夹并存时）单独成一本；根目录散图标题用包名，其余用文件夹名。
     * 这样「3 个文件夹的合集」和「套了一层合集名的 3 文件夹」都是 3 本。
     */
    private fun groupsOf(rels: List<String>, rootTitle: String): List<Group> {
        val groups = mutableListOf<Group>()
        fun walk(prefix: String) {
            val p = if (prefix.isEmpty()) "" else "$prefix/"
            val under = rels.filter { it.startsWith(p) }
            val direct = under.filter { !it.substring(p.length).contains('/') }
            val childDirs = under.asSequence()
                .filter { it.substring(p.length).contains('/') }
                .map { it.substring(p.length).substringBefore('/') }
                .distinct()
                .sortedWith { a, b -> naturalCompare(a, b) }
                .toList()
            if (direct.isNotEmpty()) {
                val title = if (prefix.isEmpty()) rootTitle else prefix.substringAfterLast('/')
                groups += Group(title, direct)
            }
            childDirs.forEach { walk(if (prefix.isEmpty()) it else "$prefix/$it") }
        }
        walk("")
        return groups
    }

    /** 把一组图片按序写进 outRoot/vol-<i>/pages，返回一本书。 */
    private fun writeGroup(
        outRoot: File,
        index: Int,
        group: Group,
        write: (relPath: String, dest: File) -> Boolean,
    ): ParsedBook {
        val pagesDir = File(File(outRoot, "vol-$index"), "pages").apply { mkdirs() }
        val pages = mutableListOf<File>()
        group.paths.forEachIndexed { i, relPath ->
            val dest = pageFile(pagesDir, i + 1, relPath.substringAfterLast('/'))
            if (write(relPath, dest)) pages += dest else dest.delete()
        }
        require(pages.isNotEmpty()) { "「${group.title}」里的图片都解不出来（可能已损坏或加密）" }
        return ParsedBook(group.title, pages)
    }

    // ---------------------------------------------------------------- RAR

    private val rarLock = Any()

    /** unrar5j 会往 stdout 打进度条，解压期间临时静音（用锁串行，避免并发互相抢 System.out）。 */
    private fun extractRar(pack: File, tmpDir: File) {
        synchronized(rarLock) {
            val realOut = System.out
            val realErr = System.err
            System.setOut(PrintStream(NullSink))
            System.setErr(PrintStream(NullSink))
            try {
                Unrar5j.extract(pack.absolutePath, tmpDir.absolutePath, null)
            } finally {
                System.setOut(realOut)
                System.setErr(realErr)
            }
            // 修复（05-低4 防线）：unrar5j 封装对含 "../" 条目的解包行为无法离线核实，解完后
            // 按结果断言——任何产物 canonicalPath 跳出 tmpDir（软链接/别名解析后仍在界内才放行）
            // 就整包删除并中止导入，不让越界文件存活
            val base = tmpDir.canonicalPath + File.separator
            tmpDir.walkTopDown().forEach { f ->
                val cp = f.canonicalPath
                if (cp != tmpDir.canonicalPath && !cp.startsWith(base)) {
                    tmpDir.deleteRecursively()
                    throw IllegalStateException("RAR 解包越界，已中止")
                }
            }
        }
    }

    private object NullSink : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    // ---------------------------------------------------------------- 内部

    /** 收集 ZIP 图片条目（05-低8 抽出）：归一化名（\→/）→ 条目，供排序分组与按需取流。 */
    private fun zipImageEntries(zip: ZipFile): LinkedHashMap<String, ZipEntry> {
        val byRel = LinkedHashMap<String, ZipEntry>()
        zip.entries().asSequence()
            .filter { !it.isDirectory && isImage(it.name) }
            .forEach { byRel[it.name.replace('\\', '/')] = it }
        return byRel
    }

    private fun rel(base: File, f: File): String = f.relativeTo(base).path.replace('\\', '/')

    private fun pageFileInternal(pagesDir: File, index: Int, srcName: String): File {
        val ext = srcName.substringAfterLast('.', "jpg").lowercase()
            .let { if (it in IMAGE_EXT) it else "jpg" }
        return File(pagesDir, "page-${index.toString().padStart(4, '0')}.$ext")
    }

    /** 流式写入（ZIP 用）：写到 pagesDir/page-<index>.ext，失败返回 null 并清掉半截文件。 */
    private fun writeStream(ins: InputStream, pagesDir: File, index: Int, srcName: String): File? {
        val dest = pageFile(pagesDir, index, srcName)
        return try {
            ins.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest
        } catch (e: Exception) {
            dest.delete()   // 半截图片删掉，不污染书页
            null
        }
    }

    /** 流式写入到指定目标文件（合集拆分用）；传 budget 时边写边计入解压预算，超限中止整包导入。 */
    private fun writeStream(ins: InputStream, dest: File, budget: ExtractBudget? = null): File? {
        return try {
            ins.use { input ->
                dest.outputStream().use { output ->
                    val counted = if (budget != null) BudgetedInputStream(input, budget) else input
                    counted.copyTo(output)
                }
            }
            budget?.addEntry()
            dest
        } catch (e: ArchiveExtractLimitException) {
            dest.delete()
            throw e   // 超限必须中止整包导入，不能当单页失败吞掉
        } catch (e: Exception) {
            dest.delete()
            budget?.let { it.failed++ }
            null
        }
    }

    /** 搬移到指定目标文件（合集拆分 / RAR 共用）。 */
    private fun moveInto(src: File, dest: File): File? = try {
        if (src.renameTo(dest)) dest
        else {
            src.copyTo(dest, overwrite = true)
            src.delete()
            dest
        }
    } catch (e: Exception) {
        dest.delete()
        null
    }

    /** 页文件名规则对外暴露：文件夹导入要和压缩包导入产出同样的 pages 布局。 */
    fun pageFile(pagesDir: File, index: Int, srcName: String): File = pageFileInternal(pagesDir, index, srcName)

    /** 是否支持的图片扩展名（文件夹导入筛图用）。 */
    fun isImageName(name: String): Boolean = isImage(name)

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    private fun head(f: File, n: Int): ByteArray? = runCatching {
        f.inputStream().use { ins ->
            val buf = ByteArray(n)
            if (ins.read(buf) >= n) buf else null
        }
    }.getOrNull()

    /** 自然序比较：连续数字按数值比，其余按字符（忽略大小写）比。 */
    internal fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var x = i
                while (x < a.length && a[x].isDigit()) x++
                var y = j
                while (y < b.length && b[y].isDigit()) y++
                val na = a.substring(i, x).trimStart('0')
                val nb = b.substring(j, y).trimStart('0')
                val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (c != 0) return c
                i = x
                j = y
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** 解压超限（字节/条目）专用异常：与「个别页损坏跳过」区分，超限必须中止整包导入。 */
class ArchiveExtractLimitException(message: String) : IllegalStateException(message)

/** 单次解压资源预算（05-中3，防 zip bomb）：累计字节 ≤ 2GB、图片条目 ≤ 20000；failed = 解压失败被跳过的页数。 */
internal class ExtractBudget {
    var bytes = 0L
        private set
    var entries = 0
        private set
    var failed = 0

    /** 累计解压字节，超限抛 [ArchiveExtractLimitException]。 */
    fun addBytes(n: Long) {
        if (n <= 0L) return
        bytes += n
        if (bytes > MAX_BYTES) throw ArchiveExtractLimitException("解压数据累计超过 2GB 上限，已中止导入")
    }

    /** 累计成功解压的条目数，超限抛 [ArchiveExtractLimitException]。 */
    fun addEntry() {
        entries += 1
        if (entries > MAX_ENTRIES) throw ArchiveExtractLimitException("解压条目数超过 ${MAX_ENTRIES} 上限，已中止导入")
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_ENTRIES = 20000
    }
}

/** 计数输入流：边解压边累计字节，超限立即中止（避免单条目 zip bomb 写满磁盘后才被发现）。 */
internal class BudgetedInputStream(
    private val src: InputStream,
    private val budget: ExtractBudget,
) : InputStream() {
    override fun read(): Int {
        val r = src.read()
        if (r >= 0) budget.addBytes(1L)
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = src.read(b, off, len)
        if (n > 0) budget.addBytes(n.toLong())
        return n
    }

    override fun available(): Int = src.available()
    override fun close() = src.close()
}
