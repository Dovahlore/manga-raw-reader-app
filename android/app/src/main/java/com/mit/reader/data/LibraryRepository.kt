package com.mit.reader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LibraryRepository(private val context: Context) {
    private val internalRoot = File(context.filesDir, "library").apply { mkdirs() }
    private val storageBase get() = selectedLibraryRoot()
    private val root get() = File(storageBase, "library").apply { mkdirs() }
    private val indexFile = File(internalRoot, "index.json")
    private val translatedRoot get() = File(storageBase, "translated").apply { mkdirs() }
    private val internalTranslatedRoot = File(context.filesDir, "translated")
    private val cloudCovers get() = File(storageBase, "cloud_covers").apply { mkdirs() }
    private val internalCloudCovers = File(context.filesDir, "cloud_covers")
    private val progressPrefs = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    private val api = TranslationApi()

    // 串行化导入：去重（读-查-写）必须原子，否则并发导入同一文件会重复建条目
    private val importMutex = Semaphore(1)
    @Volatile private var indexCache: IndexData? = null

    private data class IndexData(val books: List<Book>, val folders: List<Folder>)

    /** 导入结果：book 为最终那本书（重复时是已存在的那本）；duplicate 表示内容已存在、未新建。
     *  sourceHash = 源文件（zip/epub…）的哈希，合集拆多本时多本共用同一个。 */
    data class ImportResult(val book: Book, val duplicate: Boolean, val sourceHash: String = "")

    data class ScanProgress(
        val processed: Int,
        val total: Int,
        val added: Int,
        val failed: Int,
        val currentFile: String? = null,
    )

    data class StorageMigrationResult(val migratedBooks: Int, val migratedTranslated: Int)

    private data class ScannedDocument(
        val documentId: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val directory: Boolean,
    )

    private data class ScannedFile(
        val uri: Uri,
        val name: String,
        val sourceName: String,
        val size: Long,
        val lastModified: Long,
    )

    suspend fun books(): List<Book> = withContext(Dispatchers.IO) { readIndexData().books }

    suspend fun folders(): List<Folder> = withContext(Dispatchers.IO) { readIndexData().folders }

    suspend fun book(id: String): Book? = withContext(Dispatchers.IO) { readIndexData().books.find { it.id == id } }

    /** 按书名查本地书（WebView 下载前查重，避免重复下载已有书）。 */
    suspend fun bookByTitle(title: String): Book? = withContext(Dispatchers.IO) {
        val t = title.trim()
        if (t.isBlank()) null else readIndexData().books.firstOrNull { it.title == t }
    }

    suspend fun import(uri: Uri): Book = importResults(uri).first().book

    /** 从 Uri 导入。合集压缩包会拆成多本，返回每本的结果（含是否重复）。 */
    suspend fun importResults(
        uri: Uri,
        knownName: String? = null,
        cloudIdByHash: Map<String, String> = emptyMap(),
    ): List<ImportResult> = withContext(Dispatchers.IO) {
        importMutex.withPermit {
            val name = knownName ?: displayNameOf(uri)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            importFromStream(input, name, "", cloudIdByHash)
        }
    }

    /** 兼容单本调用：合集包取第一本。 */
    suspend fun importResult(
        uri: Uri,
        knownName: String? = null,
        cloudIdByHash: Map<String, String> = emptyMap(),
    ): ImportResult = importResults(uri, knownName, cloudIdByHash).first()

    /** 从本地文件导入（书库默认文件夹 / WebView 下载后的文件用）。合集包返回多本。 */
    suspend fun importFiles(file: File): List<ImportResult> = withContext(Dispatchers.IO) {
        importMutex.withPermit {
            importFromStream(file.inputStream(), file.name, "")
        }
    }

    suspend fun importFile(file: File): ImportResult = importFiles(file).first()

    /**
     * 导入「单层纯图片文件夹」（SAF 树 Uri）：解压后的合集文件夹可以直接用这个导入成一本书。
     * ★ 文件夹里必须全是图片、不能有子文件夹——有子文件夹直接拒绝（合集请导 zip，或逐个子文件夹导入）。
     * hash 按页内容算，和同内容压缩包拆出来的书能互相去重。
     */
    suspend fun importFolderTree(treeUri: Uri): ImportResult = withContext(Dispatchers.IO) {
        importMutex.withPermit {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("无法读取所选文件夹")
            val children = queryChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            if (children.any { it.directory }) {
                throw IllegalStateException("该文件夹含子文件夹：仅支持单层纯图片文件夹（合集请导入 zip，或逐个导入子文件夹）")
            }
            val images = children
                .filter { !it.directory && ArchiveParser.isImageName(it.name) }
                .sortedWith { a, b -> ArchiveParser.naturalCompare(a.name, b.name) }
            if (images.isEmpty()) throw IllegalStateException("文件夹里没有图片（支持 jpg/png/webp/gif/bmp/avif）")

            val id = UUID.randomUUID().toString()
            val dir = File(root, id).apply { mkdirs() }
            val pagesDir = File(dir, "pages").apply { mkdirs() }
            var success = false
            try {
                val pages = mutableListOf<File>()
                images.forEachIndexed { i, doc ->
                    val src = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.documentId)
                    val dest = ArchiveParser.pageFile(pagesDir, i + 1, doc.name)
                    val ins = context.contentResolver.openInputStream(src)
                        ?: throw IllegalStateException("读不了 ${doc.name}")
                    ins.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    pages += dest
                }
                val hash = sha256OfPages(pages)
                readIndexData().books.firstOrNull { it.hash.isNotEmpty() && it.hash == hash }?.let {
                    success = true
                    dir.deleteRecursively()   // 内容已有：刚拷的这份是冗余，清掉
                    return@withPermit ImportResult(it, true)
                }
                val book = Book(
                    id = id,
                    title = tree.name?.takeIf { it.isNotBlank() } ?: titleFromFileName(images.first().name),
                    mode = ReadingMode.MANGA,
                    pageCount = pages.size,
                    coverFile = pages.first(),
                    pageFiles = pages,
                    hash = hash,
                    fingerprint = fingerprint(pages),
                    createdAt = System.currentTimeMillis(),
                )
                val index = readIndexData()
                writeIndex(index.books + book, index.folders)
                success = true
                ImportResult(book, false)
            } finally {
                if (!success) dir.deleteRecursively()
            }
        }
    }

    /** 导入源流：单本格式 = 1 本；合集 zip/rar = 顶层每个文件夹一本。 */
    private suspend fun importFromStream(
        input: InputStream,
        name: String?,
        precomputedHash: String,
        cloudIdByHash: Map<String, String> = emptyMap(),
    ): List<ImportResult> {
        val batchId = UUID.randomUUID().toString()
        val stagingDir = File(context.cacheDir, "imports/$batchId").apply { mkdirs() }
        val stagingFile = File(stagingDir, "book.src")
        val createdDirs = mutableListOf<File>()
        var success = false
        val results = mutableListOf<ImportResult>()
        try {
            // 源文件先放内部缓存：SD 卡导入时避免“读 SD + 写 SD 源文件”同时竞争。
            val sourceHash = input.use {
                copyToSourceAndHash(it, stagingFile, precomputedHash.isEmpty())
            }.ifEmpty { precomputedHash }
            val parsedList = when (sniffFormat(stagingFile)) {
                "mobi" -> listOf(MobiParser.extract(stagingFile, File(stagingDir, "vol-0")))
                // 合集 zip/rar：顶层每个文件夹一本书；单本包 = 1 本
                "zip", "rar" -> ArchiveParser.extractMulti(stagingFile, stagingDir)
                else -> listOf(EpubParser.extract(stagingFile, File(stagingDir, "vol-0")))
            }
            val single = parsedList.size == 1
            parsedList.forEachIndexed { i, parsed ->
                // 合集拆出的每本按「页内容」算 hash（同内容的文件夹导入能互相去重）；单本沿用源文件 hash
                val hash = if (single) sourceHash else sha256OfPages(parsed.pages)
                val existing = readIndexData().books.firstOrNull {
                    it.hash.isNotEmpty() && it.hash == hash
                }
                if (existing != null) {
                    results += ImportResult(existing, true, sourceHash)
                    return@forEachIndexed
                }
                val id = UUID.randomUUID().toString()
                val dir = File(root, id).apply { mkdirs() }
                createdDirs += dir
                if (!moveTree(File(stagingDir, "vol-$i"), dir)) {
                    throw IllegalStateException("写入书库失败：${dir.absolutePath}")
                }
                val pages = File(dir, "pages").listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
                if (pages.isEmpty()) throw IllegalStateException("「${parsed.title}」没有可用页面")
                val cloudId = cloudIdByHash[hash]
                val book = Book(
                    id = id,
                    title = if (single) titleFromFileName(name).ifBlank { parsed.title } else parsed.title,
                    mode = ReadingMode.MANGA,
                    pageCount = pages.size,
                    coverFile = pages.first(),
                    pageFiles = pages,
                    hash = hash,
                    fingerprint = fingerprint(pages),
                    cloudId = cloudId,
                    createdAt = System.currentTimeMillis(),
                )
                val index = readIndexData()
                writeIndex(index.books + book, index.folders)
                results += ImportResult(book, false, sourceHash)
            }
            success = true
        } finally {
            stagingDir.deleteRecursively()
            if (!success) createdDirs.forEach { it.deleteRecursively() }
        }
        return results
    }

    /** 一组页的内容哈希：逐页 sha256 串起来再哈希（合集拆书 / 文件夹导入的去重键）。 */
    private fun sha256OfPages(pages: List<File>): String {
        val md = MessageDigest.getInstance("SHA-256")
        pages.forEach { md.update(sha256(it).toByteArray(Charsets.UTF_8)) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 跨存储搬目录：同分区 rename，失败退回逐文件复制（校验后删源）。 */
    private fun moveTree(source: File, destination: File): Boolean {
        if (source.renameTo(destination)) return true
        return copyTreeReliable(source, destination)
    }

    private fun copyToSourceAndHash(input: InputStream, destination: File, computeHash: Boolean): String {
        val digest = if (computeHash) MessageDigest.getInstance("SHA-256") else null
        val stream = if (digest != null) DigestInputStream(input, digest) else input
        destination.outputStream().use { output ->
            stream.copyTo(output, bufferSize = 1024 * 1024)
        }
        return digest?.digest()?.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.orEmpty()
    }

    /** 默认书库文件夹（App 自己的外部存储 books 目录，安装即存在、无需授权）。 */
    fun defaultBooksDir(): File {
        val base = if (storageBase == context.filesDir) context.getExternalFilesDir(null) ?: context.filesDir else storageBase
        return File(base, "books").apply { mkdirs() }
    }

    /** 下载一个文件到默认书库文件夹（WebView 下载用，带 Cookie/Referer/UA 以便通过站点校验），返回落盘文件。 */
    suspend fun downloadToBooks(
        url: String,
        filename: String,
        cookie: String?,
        referer: String?,
        userAgent: String?,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val safeName = filename.substringAfterLast('/').ifBlank { "download_${System.currentTimeMillis()}" }
        val f = File(defaultBooksDir(), safeName)
        val headers = mutableMapOf<String, String>()
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        api.download(url, f, onProgress = onProgress, extraHeaders = headers)
        f
    }

    /** 扫描「用户选的书库文件夹」（SAF）里的 epub/mobi/漫画压缩包，导入新书（按内容 hash 去重）。
     *  默认书库文件夹（App 私有）不扫：WebView 下载后已直接导入，那里只是暂存。 */
    suspend fun scanLibraryFolder(
        onProgress: suspend (ScanProgress) -> Unit,
        onBookImported: suspend (Book) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        var imported = 0
        var failed = 0
        val treeUri = ServerConfig.libraryFolderUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: return@withContext 0
        val cloudIdByHash = runCatching { api.cloudList() }.getOrNull()
            ?.mapNotNull { cloud -> cloud.hash?.takeIf { it.isNotBlank() }?.let { it to cloud.id } }
            ?.toMap()
            ?: emptyMap()

        val pending = ArrayDeque<Pair<String, String>>()
        pending += DocumentsContract.getTreeDocumentId(treeUri) to ""
        val files = mutableListOf<ScannedFile>()
        while (pending.isNotEmpty()) {
            val (dir, parent) = pending.removeLast()
            queryChildren(treeUri, dir).forEach { item ->
                if (item.directory) {
                    val itemName = item.name
                    if (itemName.startsWith(".") || itemName == "LOST.DIR" || itemName.equals("Android", ignoreCase = true)) {
                        return@forEach
                    }
                    pending += item.documentId to if (parent.isBlank()) itemName else "$parent/$itemName"
                } else if (isSupportedName(item.name)) {
                    files += ScannedFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.documentId),
                        name = item.name,
                        sourceName = if (parent.isBlank()) item.name else "$parent/${item.name}",
                        size = item.size,
                        lastModified = item.lastModified,
                    )
                    onProgress(ScanProgress(files.size, 0, imported, failed))
                }
            }
        }

        val knownHashes = readIndexData().books.mapNotNull { it.hash.takeIf(String::isNotEmpty) }.toSet()
        files.forEachIndexed { index, file ->
            onProgress(ScanProgress(index, files.size, imported, failed, file.name))
            val stamp = scanStamp(file)
            val cached = scanCache[file.uri.toString()]
            if (cached != null && cached.startsWith(stamp)) {
                val cachedHash = cached.substringAfterLast('|')
                if (cachedHash in knownHashes || sourceBooksAlive(cachedHash, knownHashes)) {
                    onProgress(ScanProgress(index + 1, files.size, imported, failed))
                    return@forEachIndexed
                }
            }

            runCatching { importResults(file.uri, file.name, cloudIdByHash) }
                .onSuccess { results ->
                    val added = results.filterNot { it.duplicate }
                    imported += added.size
                    added.forEach { onBookImported(it.book) }
                    val srcHash = results.firstOrNull()?.sourceHash.orEmpty()
                    if (results.size == 1) {
                        recordSourceFile(results[0].book.hash, file.sourceName)
                    } else if (srcHash.isNotEmpty()) {
                        // 合集包：源 zip 被多本书共用，删某一本不能删 zip；登记「整包已导入」供扫描跳过
                        recordSourceBooks(srcHash, results.map { it.book.hash })
                    }
                    if (srcHash.isNotEmpty()) recordScanCache(file, srcHash)
                }
                .onFailure { failed++ }
            onProgress(ScanProgress(index + 1, files.size, imported, failed))
        }
        imported
    }

    private fun queryChildren(treeUri: Uri, parentDocumentId: String): List<ScannedDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: continue
                        val mimeType = cursor.getString(1) ?: ""
                        val documentId = cursor.getString(2) ?: continue
                        add(
                            ScannedDocument(
                                documentId = documentId,
                                name = name,
                                size = cursor.getLong(3),
                                lastModified = cursor.getLong(4),
                                directory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrThrow()
    }

    private fun selectedLibraryRoot(): File {
        val primaryExternal = context.getExternalFilesDir(null) ?: context.filesDir
        val configured = ServerConfig.libraryStorageDir ?: return primaryExternal
        val externalRoots = context.getExternalFilesDirs(null).orEmpty().filterNotNull()
        return externalRoots.firstOrNull { it.absolutePath == configured && it.isDirectory } ?: primaryExternal
    }

    private fun allLibraryStorageRoots(): List<File> = buildList {
        add(storageBase)
        context.getExternalFilesDirs(null).orEmpty().filterNotNull().forEach(::add)
        add(context.filesDir)
    }.distinct()

    private fun bookDir(id: String): File {
        val candidates = allLibraryStorageRoots()
            .map { File(it, "library/$id") }
            .filter { it.isDirectory }
        return candidates.maxByOrNull { dir ->
            File(dir, "pages").listFiles { file -> file.isFile }?.size ?: 0
        } ?: File(root, id)
    }

    private fun translatedDir(id: String): File {
        val active = File(translatedRoot, id)
        if (active.exists()) return active
        return allLibraryStorageRoots()
            .map { File(it, "translated/$id") }
            .firstOrNull { it.exists() } ?: active
    }

    private fun scanStamp(file: ScannedFile): String = "${file.size}|${file.lastModified}"

    private data class TreeStats(val files: Int, val bytes: Long)

    private fun treeStats(file: File): TreeStats {
        if (file.isFile) return TreeStats(1, file.length())
        var files = 0
        var bytes = 0L
        file.walkTopDown().forEach {
            if (it.isFile) {
                files++
                bytes += it.length()
            }
        }
        return TreeStats(files, bytes)
    }

    private fun copyFileFast(source: File, destination: File): Boolean = runCatching {
        destination.parentFile?.mkdirs()
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
            }
        }
    }.isSuccess

    private fun copyTreeJava(source: File, destination: File): Boolean {
        if (source.isFile) return copyFileFast(source, destination)
        if (!destination.mkdirs() && !destination.isDirectory) return false
        source.listFiles().orEmpty().forEach { child ->
            if (!copyTreeJava(child, File(destination, child.name))) return false
        }
        return true
    }

    private fun copyTreeReliable(source: File, destination: File): Boolean {
        if (treeStats(source) == treeStats(destination)) return true
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "${destination.name}.migrating")
        staging.deleteRecursively()
        if (!copyTreeJava(source, staging) || treeStats(source) != treeStats(staging)) {
            staging.deleteRecursively()
            return false
        }

        if (!destination.deleteRecursively()) {
            staging.deleteRecursively()
            return false
        }
        if (!staging.renameTo(destination)) {
            val fallbackOk = copyTreeJava(source, destination)
            staging.deleteRecursively()
            return fallbackOk && treeStats(source) == treeStats(destination)
        }
        staging.deleteRecursively()
        return treeStats(source) == treeStats(destination)
    }

    /** 把所有旧位置的媒体数据迁移到当前选定的 App 专用存储。index.json 保留内部，SD 卡移除时元数据不丢。 */
    suspend fun migrateLegacyStorage(
        targetRoot: File? = null,
        onProgress: suspend (copied: Int, total: Int) -> Unit,
    ): StorageMigrationResult = withContext(Dispatchers.IO) {
        val targetBase = targetRoot ?: storageBase
        if (!targetBase.exists() && !targetBase.mkdirs()) {
            throw IllegalStateException("无法创建目标目录：${targetBase.absolutePath}")
        }
        val legacyBases = buildList {
            add(context.filesDir)
            context.getExternalFilesDirs(null).orEmpty().filterNotNull().forEach { add(it) }
        }.distinct().filter { it.absolutePath != targetBase.absolutePath }
        val groups = legacyBases.flatMap { base ->
            listOf(
                File(base, "library") to File(targetBase, "library"),
                File(base, "translated") to File(targetBase, "translated"),
                File(base, "cloud_covers") to File(targetBase, "cloud_covers"),
            )
        }
        val pairs = groups.flatMap { (source, destination) ->
            source.listFiles().orEmpty()
                .filter { it.name != "index.json" && it.exists() }
                .map { it to File(destination, it.name) }
        }
        val requiredBytes = pairs
            .filter { (source, destination) -> treeStats(source) != treeStats(destination) }
            .sumOf { (source, _) -> treeStats(source).bytes }
        if (targetBase.usableSpace in 0 until requiredBytes) {
            throw IllegalStateException("目标空间不足：需要约 ${requiredBytes / 1024 / 1024} MB")
        }

        importMutex.withPermit {
            runCatching {
                listOf(File(targetBase, "library"), File(targetBase, "translated"), File(targetBase, "cloud_covers"))
                    .forEach { it.mkdirs() }
            }.getOrThrow()
            pairs.forEachIndexed { index, (source, destination) ->
                val ok = copyTreeReliable(source, destination)
                if (!ok) throw IllegalStateException("复制失败：${source.name}")
                withContext(Dispatchers.Main) { onProgress(index + 1, pairs.size) }
            }

            val unmatched = pairs.firstOrNull { (source, destination) ->
                !destination.exists() || treeStats(source) != treeStats(destination)
            }
            if (unmatched != null) {
                throw IllegalStateException("迁移校验失败：${unmatched.first.name}，内部数据已保留")
            }

            // 手动搬运先保留旧位置：目标完整、用户确认书库正常后，才允许另行清理。
            // 这里绝不能“复制完立刻删源”，避免目标异常/索引未刷新时把本地书库清空。
            StorageMigrationResult(
                migratedBooks = pairs.count { it.first.parentFile?.name == "library" },
                migratedTranslated = pairs.count { it.first.parentFile?.name == "translated" },
            )
        }
    }

    private val scanCachePrefs get() = context.getSharedPreferences("library_scan_cache", Context.MODE_PRIVATE)
    private val scanCache: MutableMap<String, String>
        get() = scanCachePrefs.all.entries.associate { it.key to it.value.toString() }.toMutableMap()

    private fun recordScanCache(file: ScannedFile, hash: String) {
        scanCachePrefs.edit().putString(file.uri.toString(), "${scanStamp(file)}|$hash").apply()
    }

    private fun existingByHash(hash: String): Book? {
        if (hash.isEmpty()) return null
        return readIndexData().books.firstOrNull { it.hash == hash }
    }

    private val sourcePrefs get() = context.getSharedPreferences("source_files", Context.MODE_PRIVATE)

    private fun sourceFiles(): MutableMap<String, String> {
        val raw = sourcePrefs.getString("map", null) ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k) }
            m
        }.getOrDefault(mutableMapOf())
    }

    private fun persistSourceFiles(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        sourcePrefs.edit().putString("map", o.toString()).apply()
    }

    // ---- 合集源登记：sourceHash -> 拆出的书 hash 列表。扫描时「还有活着的书」就跳过重读大包；
    //      全删光了则不再跳过，下次扫描能重新导入。 ----

    private fun sourceBooks(): MutableMap<String, MutableList<String>> {
        val raw = context.getSharedPreferences("source_books", Context.MODE_PRIVATE).getString("map", null)
            ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, MutableList<String>>()
            o.keys().forEach { k ->
                val arr = o.optJSONArray(k) ?: return@forEach
                m[k] = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
            }
            m
        }.getOrDefault(mutableMapOf())
    }

    private fun recordSourceBooks(sourceHash: String, bookHashes: List<String>) {
        val m = sourceBooks()
        m[sourceHash] = bookHashes.toMutableList()
        val o = JSONObject()
        m.forEach { (k, v) ->
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            o.put(k, arr)
        }
        context.getSharedPreferences("source_books", Context.MODE_PRIVATE)
            .edit().putString("map", o.toString()).apply()
    }

    /** 合集源拆出的书是否还有活着的（决定扫描要不要重读这个包）。 */
    private fun sourceBooksAlive(sourceHash: String, knownHashes: Set<String>): Boolean =
        sourceBooks()[sourceHash]?.any { it in knownHashes } == true

    private fun recordSourceFile(hash: String, name: String) {
        val m = sourceFiles()
        m[hash] = name
        persistSourceFiles(m)
    }

    private fun removeSourceFile(hash: String) {
        val m = sourceFiles()
        m.remove(hash)
        persistSourceFiles(m)
    }

    private fun sourceFileName(hash: String): String? = sourceFiles()[hash]

    /** 取 ContentResolver 里的原始文件名（例：[Kmoe][尼古喵喵]卷01.epub）。 */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 文件名 → 书名：只去掉扩展名，其余（含 [epub]/[Kmoe] 这类方括号）原样保留，不做任何消除。 */
    fun titleFromFileName(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.substringBeforeLast('.').trim()
    }

    /** 支持导入的文件名：epub / mobi / azw3 / 漫画压缩包(zip,rar,cbz,cbr)。 */
    fun isSupportedName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".epub") || n.endsWith(".mobi") || n.endsWith(".azw") || n.endsWith(".azw3") ||
            n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".cbz") || n.endsWith(".cbr")
    }

    /**
     * 按文件头嗅探格式，返回 "mobi" / "epub" / "zip" / "rar"：
     * - MOBI：PDB 头 60..68 字节是 "BOOK"+"MOBI"
     * - ZIP（PK\x03\x04）：里面第一个条目是 mimetype=application/epub+zip → EPUB，否则当漫画压缩包(CBZ)
     * - RAR：Rar!\x1a\x07
     */
    private fun sniffFormat(f: File): String {
        f.inputStream().use { ins ->
            val head = ByteArray(68)
            val n = ins.read(head)
            if (n >= 68) {
                if (String(head, 60, 4, Charsets.US_ASCII) == "BOOK" &&
                    String(head, 64, 4, Charsets.US_ASCII) == "MOBI"
                ) return "mobi"
            }
            if (n >= 6 && head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() &&
                head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte() &&
                head[4] == 0x1A.toByte() && head[5] == 0x07.toByte()
            ) return "rar"
            if (n >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            ) return if (isEpubZip(f)) "epub" else "zip"
        }
        // 退路：按扩展名
        return when (f.extension.lowercase()) {
            "mobi", "azw", "azw3", "prc" -> "mobi"
            "cbz" -> "zip"
            "cbr", "rar" -> "rar"
            "zip" -> "zip"
            else -> "epub"
        }
    }

    /** ZIP 里 mimetype 条目内容为 application/epub+zip → 是 EPUB；没有/不是 → 当漫画压缩包。 */
    private fun isEpubZip(f: File): Boolean = runCatching {
        ZipFile(f).use { zip ->
            val e = zip.getEntry("mimetype") ?: return@use false
            zip.getInputStream(e).bufferedReader(Charsets.UTF_8).readText().trim() == "application/epub+zip"
        }
    }.getOrDefault(false)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        allLibraryStorageRoots().forEach { base ->
            File(base, "library/$id").deleteRecursively()
            File(base, "translated/$id").deleteRecursively()
        }
        val d = readIndexData()
        writeIndex(d.books.filterNot { it.id == id }, d.folders)
    }

    /** 启动清理：删掉导入后冗余的 book.src、失败同步/下载遗留的 zip、以及已删书的孤儿目录。
     *  幂等，可每次启动跑一遍（之后就是 no-op）。 */
    suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
        File(context.cacheDir, "imports").deleteRecursively()
        val ids = readIndexData().books.map { it.id }.toSet()
        // 索引缺失/暂时读不到时绝不能按“全部都是孤儿”清理，否则会把本地书全删。
        if (ids.isEmpty()) return@withContext

        // 1) 删每本书的 book.src（内容已解压到 pages/、hash 已存 index，源文件冗余 → 省一半空间）
        ids.forEach { id ->
            bookDir(id).resolve("book.src").takeIf { it.exists() }?.delete()
        }

        // 2) 删已删书的孤儿目录。只检查 library/translated 的子目录；
        // 存储根下的 library、translated、cloud_covers 本身不是书 id，绝不能删除。
        allLibraryStorageRoots().forEach { storageRoot ->
            File(storageRoot, "library").listFiles { f -> f.isDirectory }?.forEach { dir ->
                if (dir.name !in ids) dir.deleteRecursively()
            }

            // 3) 删已删书的译文缓存孤儿目录
            File(storageRoot, "translated").listFiles { f -> f.isDirectory }?.forEach { dir ->
                if (dir.name !in ids) dir.deleteRecursively()
            }
        }

        // 4) 删失败同步/下载遗留的 zip（正常流程结束会删，这里兜底历史遗留）
        context.cacheDir.listFiles { f -> f.isFile && (f.name.startsWith("sync-") || f.name.startsWith("dl-")) }
            ?.forEach { it.delete() }
    }

    /** 删书时同步删除源文件（默认书库文件夹 + SAF 文件夹都查，按内容 hash 定位文件名）。 */
    suspend fun deleteSourceFile(hash: String): Boolean = withContext(Dispatchers.IO) {
        if (hash.isBlank()) return@withContext false
        val targetName = sourceFileName(hash) ?: return@withContext false

        // 1) 默认书库文件夹
        val f1 = File(defaultBooksDir(), targetName)
        if (f1.exists()) {
            val ok = f1.delete()
            if (ok) removeSourceFile(hash)
            return@withContext ok
        }

        // 2) SAF 文件夹
        val uriStr = ServerConfig.libraryFolderUri
        if (!uriStr.isNullOrBlank()) {
            val folder = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) }.getOrNull()
            if (folder != null) {
                for (f in folder.listFiles()) {
                    if (f.isFile && f.name == targetName) {
                        val ok = runCatching { f.delete() }.getOrDefault(false)
                        if (ok) removeSourceFile(hash)
                        return@withContext ok
                    }
                }
            }
        }
        false
    }

    suspend fun setMode(id: String, mode: ReadingMode) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == id) it.copy(mode = mode) else it }, d.folders)
    }

    // ---------------------------------------------------------------- 收藏夹

    /** 按名字取本地收藏夹，没有就新建。返回 (folderId, 更新后的 folders)；name 空 = 未分类。 */
    private fun resolveFolder(folders: List<Folder>, name: String?): Pair<String?, List<Folder>> {
        val n = name?.trim()
        if (n.isNullOrEmpty()) return null to folders
        folders.find { it.name == n }?.let { return it.id to folders }
        val f = Folder(UUID.randomUUID().toString(), n)
        return f.id to (folders + f)
    }

    suspend fun createFolder(name: String): Folder = withContext(Dispatchers.IO) {
        val d = readIndexData()
        val f = Folder(UUID.randomUUID().toString(), name.trim().ifBlank { "未命名" })
        writeIndex(d.books, d.folders + f)
        // 收藏夹列表也上云：失败（离线）不报错，下次 syncFoldersWithCloud 会补建
        runCatching { api.cloudFolderCreate(f.name) }
        f
    }

    suspend fun renameFolder(id: String, name: String) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        val newName = name.trim()
        val oldName = d.folders.find { it.id == id }?.name
        writeIndex(d.books, d.folders.map { if (it.id == id) it.copy(name = newName) else it })
        // 已同步书：该夹下所有同步书的云端 folder 名跟着改（失败=离线，进待同步队列，下次在线补）
        for (b in d.books) {
            if (b.folderId == id && b.cloudId != null) {
                val ok = runCatching { api.cloudUpdateFolder(b.cloudId, newName) }.getOrDefault(false)
                if (!ok) recordFolderSync(b.cloudId, newName)
            }
        }
        // 云端收藏夹改名 = 建新名 + 删旧名（书已改挂新名，删旧名不会把书弄丢）
        if (newName.isNotBlank() && oldName != null && oldName.isNotBlank() && oldName != newName) {
            runCatching { api.cloudFolderCreate(newName) }
            val ok = runCatching { deleteCloudFolderByName(oldName) }.getOrDefault(false)
            if (!ok) recordFolderDelete(oldName)
        }
    }

    suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        val folderName = d.folders.find { it.id == id }?.name
        writeIndex(
            d.books.map { if (it.folderId == id) it.copy(folderId = null) else it },
            d.folders.filterNot { it.id == id },
        )
        // 已同步书：该夹下同步书移出未分类（失败=离线，进待同步队列）
        for (b in d.books) {
            if (b.folderId == id && b.cloudId != null) {
                val ok = runCatching { api.cloudUpdateFolder(b.cloudId, null) }.getOrDefault(false)
                if (!ok) recordFolderSync(b.cloudId, "")
            }
        }
        // 云端同名收藏夹一并删掉（离线则进补删队列，下次在线补），否则 syncFoldersWithCloud 会把它拉回本地
        if (!folderName.isNullOrBlank()) {
            val ok = runCatching { deleteCloudFolderByName(folderName) }.getOrDefault(false)
            if (!ok) recordFolderDelete(folderName)
        }
    }

    /** 把书移进/移出收藏夹。folderId 传 null 表示移到「未分类」。 */
    suspend fun moveBook(bookId: String, folderId: String?) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == bookId) it.copy(folderId = folderId) else it }, d.folders)
        // 已同步的书：收藏夹变化同步到云端（失败=离线，进待同步队列）
        val book = d.books.find { it.id == bookId } ?: return@withContext
        if (book.cloudId != null) {
            val name = folderId?.let { fid -> d.folders.find { it.id == fid }?.name }
            val ok = runCatching { api.cloudUpdateFolder(book.cloudId, name) }.getOrDefault(false)
            if (!ok) recordFolderSync(book.cloudId, name ?: "")
        }
    }

    /** 云端书（未下载到本地）改收藏夹：离线/失败进待同步队列，下次在线补。返回本次是否直接成功。 */
    suspend fun moveCloudBook(cloudId: String, folderName: String?): Boolean = withContext(Dispatchers.IO) {
        val name = folderName?.trim().orEmpty()
        val ok = runCatching { api.cloudUpdateFolder(cloudId, name.takeIf { it.isNotBlank() }) }.getOrDefault(false)
        if (!ok) recordFolderSync(cloudId, name)
        ok
    }

    /** 本地与云端脱钩（不动云端数据）：清 cloudId，书变回「仅本地」。
     *  离线取消同步用——本地先解除关系，云端删除另走补删队列。 */
    suspend fun detachCloud(book: Book): Book = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == book.id) it.copy(cloudId = null) else it }, d.folders)
        readIndexData().books.find { it.id == book.id } ?: book.copy(cloudId = null)
    }

    /** 本地书按内容 hash 挂到已有云端书（离线导入的书联网后匹配上，之后能拉云端译文）。 */
    suspend fun attachCloudId(bookId: String, cloudId: String) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        if (d.books.none { it.id == bookId }) return@withContext
        writeIndex(d.books.map { if (it.id == bookId) it.copy(cloudId = cloudId) else it }, d.folders)
    }

    /** 云端书被删（或已排队删除）后，把本地挂着同一 cloudId 的书脱钩，并清掉封面缓存。 */
    suspend fun detachCloudByCloudId(cloudId: String) = withContext(Dispatchers.IO) {
        cloudCoverFile(cloudId).delete()
        val d = readIndexData()
        if (d.books.none { it.cloudId == cloudId }) return@withContext
        writeIndex(d.books.map { if (it.cloudId == cloudId) it.copy(cloudId = null) else it }, d.folders)
    }

    /** 重命名书名（空白则忽略）。 */
    suspend fun renameBook(bookId: String, title: String) = withContext(Dispatchers.IO) {
        val t = title.trim()
        if (t.isBlank()) return@withContext
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == bookId) it.copy(title = t) else it }, d.folders)
    }

    // ---- 离线收藏夹补同步：移动/重命名/删除收藏夹时，对已同步书的云端 folder 变更先试一次，
    //      失败（离线）记进 pending，下次在线由 drainFolderSyncs 补发，避免云端 folder 与本地不一致 ----

    private val folderSyncPrefs get() = context.getSharedPreferences("folder_sync", Context.MODE_PRIVATE)

    private fun pendingFolderSyncs(): MutableMap<String, String> {
        val raw = folderSyncPrefs.getString("pending", null) ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k) }
            m
        }.getOrDefault(mutableMapOf())
    }

    private fun persistFolderSyncs(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        folderSyncPrefs.edit().putString("pending", o.toString()).apply()
    }

    private fun recordFolderSync(cloudId: String, folderName: String) {
        val m = pendingFolderSyncs()
        m[cloudId] = folderName
        persistFolderSyncs(m)
    }

    /** 补同步收藏夹：对每条 pending（cloudId -> folder名，空串=未分类）调 cloudUpdateFolder，成功移除、失败保留。 */
    suspend fun drainFolderSyncs() = withContext(Dispatchers.IO) {
        val m = pendingFolderSyncs()
        if (m.isEmpty()) return@withContext
        val remaining = mutableMapOf<String, String>()
        for ((cloudId, name) in m) {
            val folder = name.takeIf { it.isNotBlank() }
            val ok = runCatching { api.cloudUpdateFolder(cloudId, folder) }.getOrDefault(false)
            if (!ok) remaining[cloudId] = name
        }
        persistFolderSyncs(remaining)
    }

    // ---- 云端收藏夹补删：本地删夹/改名时云端删除失败（离线），记名字，下次在线按名字找 id 删掉 ----

    private fun pendingFolderDeletes(): MutableSet<String> {
        val raw = folderSyncPrefs.getString("pending_folder_deletes", null) ?: return mutableSetOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
        }.getOrDefault(mutableSetOf())
    }

    private fun persistFolderDeletes(names: Set<String>) {
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        folderSyncPrefs.edit().putString("pending_folder_deletes", arr.toString()).apply()
    }

    private fun recordFolderDelete(name: String) {
        val names = pendingFolderDeletes()
        names += name
        persistFolderDeletes(names)
    }

    /** 按名字删云端收藏夹；云端本来就没有这个夹 = 算成功（幂等）。离线会抛，由调用方 runCatching。 */
    private suspend fun deleteCloudFolderByName(name: String): Boolean {
        val target = api.cloudFolders().firstOrNull { it.name == name } ?: return true
        return api.cloudFolderDelete(target.id)
    }

    /** 补删云端收藏夹（本地删夹/改名后离线没删掉的）。 */
    suspend fun drainFolderDeletes() = withContext(Dispatchers.IO) {
        val names = pendingFolderDeletes()
        if (names.isEmpty()) return@withContext
        val remaining = mutableSetOf<String>()
        var offline = false
        for (n in names) {
            // 第一条就失败基本等于离线：剩下的原样留着，别一条条干等连接超时（这一步占着 storageGate）
            val ok = !offline && runCatching { deleteCloudFolderByName(n) }.getOrDefault(false)
            if (!ok) {
                offline = true
                remaining += n
            }
        }
        persistFolderDeletes(remaining)
    }

    /**
     * 收藏夹与云端对齐（并集，只补建不删）：
     *  - 本地有、云端没有 → 在云端建（多设备/离线新建的夹补上去）；
     *  - 云端有、本地没有 → 在本地建（这样「下载到本地」的云端书能落进对应收藏夹）。
     * 离线（拉不到云端列表）直接跳过，不做任何本地改动。返回本地索引是否有变化。
     * 删除不走这里：删夹由 deleteFolder/renameFolder 记入补删队列，避免把别的设备新建的夹误删。
     */
    suspend fun syncFoldersWithCloud(): Boolean = withContext(Dispatchers.IO) {
        val cloud = runCatching { api.cloudFolders() }.getOrNull() ?: return@withContext false
        val cloudNames = cloud.map { it.name.trim() }.filter { it.isNotBlank() }.toSet()
        val d = readIndexData()
        val localNames = d.folders.map { it.name.trim() }.filter { it.isNotBlank() }.toSet()

        for (name in localNames - cloudNames) {
            if (!runCatching { api.cloudFolderCreate(name) }.getOrDefault(false)) break
        }
        val missing = cloudNames - localNames
        if (missing.isEmpty()) return@withContext false
        writeIndex(d.books, d.folders + missing.sorted().map { Folder(UUID.randomUUID().toString(), it) })
        true
    }

    // ---------------------------------------------------------------- 云同步

    /** 同步一本本地书：打包 zip → 上传 → 记录 cloudId。返回更新后的书。
     *  onProgress(阶段文案, 0..1 进度；null=不确定)。 */
    suspend fun sync(book: Book, onProgress: ((String, Float?) -> Unit)? = null): Book = withContext(Dispatchers.IO) {
        onProgress?.invoke("打包中…", null)
        val zipFile = File(context.cacheDir, "sync-${book.id}.zip")
        if (zipFile.exists()) zipFile.delete()
        try {
            val folderName = book.folderId?.let { fid -> readIndexData().folders.find { it.id == fid }?.name }
            packBook(book, zipFile, folderName)
            val resp = api.cloudUpload(
                zip = zipFile,
                title = book.title,
                folder = folderName,
                mode = if (book.mode == ReadingMode.NORMAL) "normal" else "manga",
                hash = book.hash.ifBlank { sha256(epubFile(book.id)) },
                fingerprint = book.fingerprint,
                pageCount = book.pageCount,
                oldBookId = book.id,   // 先翻译后同步：把本地 UUID 下的旧译文迁到 cloudId
                onProgress = { sent, total -> onProgress?.invoke("上传中…", if (total > 0) sent.toFloat() / total else null) },
            )
            // 缓存封面：删本地后云端 tab 仍能显示封面
            runCatching { book.coverFile.copyTo(cloudCoverFile(resp.bookId), overwrite = true) }
            val d = readIndexData()
            writeIndex(d.books.map { if (it.id == book.id) it.copy(cloudId = resp.bookId) else it }, d.folders)
            readIndexData().books.find { it.id == book.id } ?: book
        } finally {
            zipFile.delete()   // 成功/失败/取消都删，避免遗留大 zip 占空间
        }
    }

    /** 取消同步：删云端（含翻译结果），本地保留，清空 cloudId。
     *  云端删除失败（离线/网络）会抛异常，由调用方处理——调用方改用 detachCloud 本地脱钩 + 补删队列，
     *  保证「离线也能取消同步 / 删本地书」。 */
    suspend fun cancelSync(book: Book): Book = withContext(Dispatchers.IO) {
        val cloudId = book.cloudId ?: return@withContext book
        api.cloudDelete(cloudId)   // 失败抛异常，不吞掉
        cloudCoverFile(cloudId).delete()
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == book.id) it.copy(cloudId = null) else it }, d.folders)
        readIndexData().books.find { it.id == book.id } ?: book
    }

    /** 从云端下载一本书并还原到本地（页面 + 译文缓存 + 归属文件夹），cloudId 保持云端 id。
     *  onProgress(阶段文案, 0..1 进度；null=不确定)。 */
    suspend fun downloadCloud(cloud: CloudBook, onProgress: ((String, Float?) -> Unit)? = null): Book = withContext(Dispatchers.IO) {
        // 去重：同一云端书 / 同一内容，重复点「下载」只复用已有本地书，不再造第二本（否则会「下载两次出现两本书」）
        val cloudHash = cloud.hash ?: ""
        val existing = readIndexData().books.firstOrNull {
            it.cloudId == cloud.id || (cloudHash.isNotBlank() && it.hash.isNotEmpty() && it.hash == cloudHash)
        }
        if (existing != null) {
            // 命中同内容但还没挂 cloudId 的（比如先导入了压缩包、后点云端下载）：补挂 cloudId 复用，不新建。
            // 本地还没归夹时按云端收藏夹归位；本地已手动归夹的尊重本地，不覆盖。
            val d0 = readIndexData()
            val (fid, folders0) = if (existing.folderId == null) {
                resolveFolder(d0.folders, cloud.folder)
            } else {
                existing.folderId to d0.folders
            }
            if (existing.cloudId != cloud.id || fid != existing.folderId) {
                writeIndex(
                    d0.books.map { if (it.id == existing.id) it.copy(cloudId = cloud.id, folderId = fid) else it },
                    folders0,
                )
            }
            return@withContext readIndexData().books.find { it.id == existing.id }
                ?: existing.copy(cloudId = cloud.id, folderId = fid)
        }

        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val zipFile = File(context.cacheDir, "dl-${cloud.id}.zip")
        if (zipFile.exists()) zipFile.delete()
        val pagesDir = File(dir, "pages").apply { mkdirs() }
        val translatedDir = File(translatedRoot, id).apply { mkdirs() }
        var title = cloud.title
        var mode = ReadingMode.MANGA
        var folderName: String? = cloud.folder
        try {
            api.cloudDownload(cloud.id, zipFile) { got, total ->
                onProgress?.invoke("下载中…", if (total > 0) got.toFloat() / total else null)
            }
            onProgress?.invoke("还原中…", null)

            ZipFile(zipFile).use { zip ->
                zip.getEntry("manifest.json")?.let { e ->
                    val m = JSONObject(zip.getInputStream(e).bufferedReader(Charsets.UTF_8).readText())
                    title = m.optString("title").takeIf { it.isNotBlank() } ?: title
                    mode = if (m.optString("mode") == "normal") ReadingMode.NORMAL else ReadingMode.MANGA
                    folderName = m.optString("folder").takeIf { it.isNotBlank() } ?: folderName
                }
                for (e in zip.entries()) {
                    if (e.isDirectory) continue
                    val name = e.name
                    when {
                        name == "book.src" ->
                            zip.getInputStream(e).use { it.copyTo(File(dir, "book.src").outputStream()) }
                        name.startsWith("pages/") ->
                            zip.getInputStream(e).use { it.copyTo(File(pagesDir, name.substringAfterLast('/')).outputStream()) }
                        name.startsWith("translated/") ->
                            zip.getInputStream(e).use { it.copyTo(File(translatedDir, name.substringAfterLast('/')).outputStream()) }
                    }
                }
            }
        } finally {
            zipFile.delete()   // 成功/失败/取消都删，避免遗留大 zip 占空间
        }

        val pages = pagesDir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
        if (pages.isEmpty()) throw IllegalStateException("云端书 zip 里没有页面")

        val srcFile = File(dir, "book.src")
        val hash = if (srcFile.exists()) sha256(srcFile) else (cloud.hash ?: "")
        srcFile.delete()   // book.src 冗余（图都在 pages/），删掉省一半空间
        val fp = if (pages.isNotEmpty()) fingerprint(pages) else ""

        val d = readIndexData()
        // 局部 val：folderName 是 var 且在闭包里被改过，先固化避免 smart cast 失败
        val fname = folderName
        val (folderId, folders) = resolveFolder(d.folders, fname)
        val book = Book(
            id = id,
            title = title ?: "未命名",
            mode = mode,
            pageCount = pages.size,
            coverFile = pages.first(),
            pageFiles = pages,
            folderId = folderId,
            cloudId = cloud.id,
            hash = hash,
            fingerprint = fp,
        )

        // 译文不打进 zip：直接从服务端拉最新结果（云端书永久保留，bookPages 永远查得到）。
        // 这样别的设备新翻/重翻的页，下载到本机时拿到的就是最新的译文。
        runCatching { api.bookPages(cloud.id) }.getOrNull()?.forEach { p ->
            if (p.status == "done" && p.pageIndex in pages.indices) {
                val f = translatedCacheFile(id, p.pageIndex)
                f.parentFile?.mkdirs()
                runCatching { api.download(api.translatedUrl(p.id), f) }
            }
        }

        // 缓存封面
        runCatching { pages.first().copyTo(cloudCoverFile(cloud.id), overwrite = true) }

        writeIndex(d.books + book, folders)
        book
    }

    /** 从服务端拉该书已翻好的页到本地译文缓存（只补差异，不全量）。
     *  overwrite=true 强制覆盖本地（别的设备重翻后同步）；false 只下载「本地缺失」或「服务端指纹变了」的页。
     *  priority=优先下载的页（阅读器传当前页）：按距离排序，让「正在看的那几页」先就绪，翻页更顺。
     *  返回本次已就绪的页索引。 */
    suspend fun refreshTranslations(book: Book, overwrite: Boolean, priority: Int? = null): Set<Int> = withContext(Dispatchers.IO) {
        val done = mutableSetOf<Int>()
        val pages = runCatching { api.bookPages(book.serverId) }.getOrNull() ?: return@withContext done
        // 阅读器打开时：先拉当前页附近的页（当前页 → ±1 → ±2 → …），再补其余
        val ordered = if (priority != null) pages.sortedBy { kotlin.math.abs(it.pageIndex - priority) } else pages
        val metaFile = syncMetaFile(book.id)
        val meta = readSyncMeta(metaFile)
        var metaChanged = false
        for (p in ordered) {
            if (p.status != "done" || p.pageIndex !in book.pageFiles.indices) continue
            val f = translatedCacheFile(book.id, p.pageIndex)
            val fp = "${p.configHash}|${p.updatedAt}"
            // 只补差异：本地没有 / 空文件 / 服务端指纹变了（多设备重翻导致）
            val need = overwrite || !f.exists() || f.length() == 0L || meta[p.pageIndex] != fp
            if (!need) { done += p.pageIndex; continue }
            runCatching { downloadTranslatedPage(book.id, p.id, p.pageIndex) }.onSuccess {
                done += p.pageIndex
                meta[p.pageIndex] = fp
                metaChanged = true
            }
        }
        if (metaChanged) writeSyncMeta(metaFile, meta)
        done
    }

    /** 每本书的译文同步指纹（pageIndex → config_hash|updated_at），判断该页要不要重新拉。 */
    private fun syncMetaFile(bookId: String): File = File(translatedDir(bookId), "sync_meta.json")

    private fun readSyncMeta(f: File): MutableMap<Int, String> {
        val m = mutableMapOf<Int, String>()
        if (!f.exists()) return m
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { k -> k.toIntOrNull()?.let { m[it] = o.optString(k) } }
        }
        return m
    }

    private fun writeSyncMeta(f: File, meta: Map<Int, String>) {
        runCatching {
            val o = JSONObject()
            meta.forEach { (k, v) -> o.put(k.toString(), v) }
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
        }
    }

    /** 打包：pages 目录 + manifest.json。
     *  ★ 不打 book.src：源文件（epub/mobi/rar）里的图就是 pages 那批图，再放一遍等于存两份。
     *     服务端 translate-from-zip / 封面 / 下载还原都只用 pages 里的图，book.src 完全用不上。
     *     去掉后云端体积直接减半。 */
    private fun packBook(book: Book, zipFile: File, folderName: String?) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            fun add(name: String, file: File) {
                if (!file.exists()) return
                zos.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            val manifest = JSONObject().apply {
                put("title", book.title)
                put("mode", if (book.mode == ReadingMode.NORMAL) "normal" else "manga")
                put("page_count", book.pageCount)
                put("hash", book.hash)
                put("fingerprint", book.fingerprint)
                folderName?.takeIf { it.isNotBlank() }?.let { put("folder", it) }
            }
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            for (f in book.pageFiles) add("pages/${f.name}", f)
        }
    }

    private fun sha256(f: File): String {
        if (!f.exists()) return ""
        return f.inputStream().use(::sha256)
    }

    private fun sha256(uri: Uri): String {
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return ""
        return input.use(::sha256)
    }

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        input.use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun fingerprint(pages: List<File>): String {
        val cover = pages.firstOrNull() ?: return ""
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pages.size.toString().toByteArray(Charsets.UTF_8))
        cover.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            val n = ins.read(buf)
            if (n > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ---------------------------------------------------------------- 阅读进度

    /** 记录某本书读到的页（0-based）。走 SharedPreferences，翻页即存、很快。 */
    fun setReadingProgress(bookId: String, page: Int) {
        progressPrefs.edit()
            .putInt("page_$bookId", page.coerceAtLeast(0))
            .putLong("time_$bookId", System.currentTimeMillis())
            .apply()
    }

    /** 带指定时间戳记录进度（对传/云端同步用，避免覆盖成「现在」）。 */
    fun setReadingProgressAt(bookId: String, page: Int, lastReadAt: Long) {
        progressPrefs.edit()
            .putInt("page_$bookId", page.coerceAtLeast(0))
            .putLong("time_$bookId", lastReadAt)
            .apply()
    }

    fun readingProgress(bookId: String): ReadingProgress? {
        val t = progressPrefs.getLong("time_$bookId", 0L)
        if (t == 0L) return null
        return ReadingProgress(progressPrefs.getInt("page_$bookId", 0), t)
    }

    /** 最近读的那本书及其进度（书库页「继续阅读」用）。 */
    fun lastRead(books: List<Book>): Pair<Book, ReadingProgress>? {
        var best: Pair<Book, ReadingProgress>? = null
        for (b in books) {
            val p = readingProgress(b.id) ?: continue
            if (best == null || p.lastReadAt > best.second.lastReadAt) best = b to p
        }
        return best
    }

    /** 某本书某页的译文缓存文件（本地缓存，服务端 14 天会删，这里留着）。服务端现发 WebP 无损。 */
    fun translatedCacheFile(bookId: String, pageIndex: Int): File =
        File(translatedDir(bookId), pageIndex.toString().padStart(3, '0') + ".webp")

    /** 原子下载译文页到本地缓存：先写临时文件再改名，避免「后台全书翻译」和「打开阅读器补拉」并发写坏同一文件。 */
    suspend fun downloadTranslatedPage(bookId: String, pageId: Int, pageIndex: Int): File {
        val f = translatedCacheFile(bookId, pageIndex)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.${UUID.randomUUID()}.tmp")
        try {
            api.download(api.translatedUrl(pageId), tmp)
            if (!tmp.renameTo(f)) tmp.copyTo(f, overwrite = true)
        } finally {
            tmp.delete()
        }
        return f
    }

    fun epubFile(bookId: String): File = File(bookDir(bookId), "book.src")

    /** 云端书封面缓存文件（删本地后云端 tab 仍能显示封面）。 */
    fun cloudCoverFile(cloudId: String): File {
        val active = File(cloudCovers, "$cloudId.jpg")
        if (active.exists()) return active
        return allLibraryStorageRoots()
            .map { File(it, "cloud_covers/$cloudId.jpg") }
            .firstOrNull { it.exists() } ?: active
    }

    /** 确保云端书封面已缓存（没有就从服务端拉）。 */
    suspend fun ensureCloudCover(cloudId: String) = withContext(Dispatchers.IO) {
        importMutex.withPermit {
        val f = cloudCoverFile(cloudId)
        if (f.exists() && f.length() > 0L) return@withContext
        f.parentFile?.mkdirs()
        runCatching { api.cloudDownloadCover(cloudId, f) }.onFailure { f.delete() }
        }
    }

    // ---------------------------------------------------------------- 设备对传

    /** 按内容 hash 找本地书（对传去重用；供接收端 HTTP 线程同步调用）。 */
    fun findByHash(hash: String): Book? {
        if (hash.isBlank()) return null
        return readIndexData().books.firstOrNull { it.hash.isNotEmpty() && it.hash == hash }
    }

    /** 本地是否已有某本书某页的译文缓存。 */
    fun hasTranslatedLocal(bookId: String, pageIndex: Int): Boolean {
        val f = translatedCacheFile(bookId, pageIndex)
        return f.exists() && f.length() > 0L
    }

    /** 打包一本书用于对传：manifest + 文件清单（原页 + 已翻好的译文图 + sync_meta）。 */
    suspend fun transferPackage(book: Book): TransferPackage = withContext(Dispatchers.IO) {
        val files = mutableListOf<TransferFileEntry>()
        val pagesArr = JSONArray()
        book.pageFiles.forEach { f ->
            val sha = sha256(f)
            pagesArr.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("sha256", sha)
            })
            files += TransferFileEntry("pages/${f.name}", f, f.length(), sha)
        }
        val translatedArr = JSONArray()
        val syncMetaObj = JSONObject()
        val syncMeta = readSyncMeta(syncMetaFile(book.id))
        for (i in book.pageFiles.indices) {
            val tf = translatedCacheFile(book.id, i)
            if (tf.exists() && tf.length() > 0L) {
                val sha = sha256(tf)
                val name = i.toString().padStart(3, '0') + ".webp"
                translatedArr.put(JSONObject().apply {
                    put("pageIndex", i)
                    put("name", name)
                    put("size", tf.length())
                    put("sha256", sha)
                })
                files += TransferFileEntry("translated/$name", tf, tf.length(), sha)
            }
            syncMeta[i]?.let { syncMetaObj.put(i.toString(), it) }
        }
        val index = readIndexData()
        val folderName = book.folderId?.let { fid -> index.folders.find { it.id == fid }?.name }
        val progress = readingProgress(book.id)
        val manifest = JSONObject().apply {
            put("v", 1)
            put("title", book.title)
            put("mode", if (book.mode == ReadingMode.MANGA) "manga" else "normal")
            put("pageCount", book.pageCount)
            put("hash", book.hash)
            put("fingerprint", book.fingerprint)
            put("orderDir", if (book.mode == ReadingMode.MANGA) "rtl" else "ltr")
            folderName?.takeIf { it.isNotBlank() }?.let { put("folder", it) }
            progress?.let { put("readingProgress", JSONObject().apply { put("page", it.page); put("lastReadAt", it.lastReadAt) }) }
            put("pages", pagesArr)
            put("translated", translatedArr)
            put("syncMeta", syncMetaObj)
        }
        TransferPackage(manifest.toString(), files)
    }

    /** 接收端：把 staging 目录里的书校验后原子导入（按 hash 去重，已有则合并缺失译文页）。 */
    suspend fun importTransferredBook(stagingDir: File, manifestJson: String): TransferImportResult =
        withContext(Dispatchers.IO) {
            importMutex.withPermit {
                val m = JSONObject(manifestJson)
                val title = m.optString("title").ifBlank { "未命名" }
                val mode = if (m.optString("mode") == "normal") ReadingMode.NORMAL else ReadingMode.MANGA
                val hash = m.optString("hash")
                val pagesArr = m.optJSONArray("pages") ?: JSONArray()
                val translatedArr = m.optJSONArray("translated") ?: JSONArray()

                val pageNames = (0 until pagesArr.length()).map { pagesArr.getJSONObject(it).getString("name") }
                if (pageNames.isEmpty()) throw IllegalStateException("传输包没有页面")
                if (pageNames.any { safeTransferName(it).not() }) throw IllegalStateException("非法页文件名")

                val existing = if (hash.isNotEmpty()) existingByHash(hash) else null
                if (existing != null) {
                    // 合并：只把缺失/为空的译文页补进已有书
                    var added = 0
                    val tdir = translatedDir(existing.id).apply { mkdirs() }
                    for (i in 0 until translatedArr.length()) {
                        val o = translatedArr.getJSONObject(i)
                        val name = o.getString("name")
                        if (!safeTransferName(name)) continue
                        val src = File(stagingDir, "translated/$name")
                        val dst = File(tdir, name)
                        if (src.exists() && (!dst.exists() || dst.length() == 0L)) {
                            src.copyTo(dst, overwrite = true)
                            added++
                        }
                    }
                    applyIncomingProgress(existing.id, m)
                    return@withPermit TransferImportResult(existing, true, added, translatedArr.length() - added)
                }

                val id = UUID.randomUUID().toString()
                val dir = File(root, id).apply { mkdirs() }
                val pagesDir = File(dir, "pages").apply { mkdirs() }
                var success = false
                try {
                    pageNames.forEach { name ->
                        val src = File(stagingDir, "pages/$name")
                        if (!src.exists()) throw IllegalStateException("缺少页文件 $name")
                        src.copyTo(File(pagesDir, name))
                    }
                    val pages = pagesDir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
                    if (pages.isEmpty()) throw IllegalStateException("没有可用页面")

                    val tdir = translatedDir(id).apply { mkdirs() }
                    var translatedCount = 0
                    for (i in 0 until translatedArr.length()) {
                        val o = translatedArr.getJSONObject(i)
                        val name = o.getString("name")
                        if (!safeTransferName(name)) continue
                        val src = File(stagingDir, "translated/$name")
                        if (src.exists()) {
                            src.copyTo(File(tdir, name))
                            translatedCount++
                        }
                    }
                    val sm = m.optJSONObject("syncMeta")
                    if (sm != null && sm.length() > 0) {
                        val map = mutableMapOf<Int, String>()
                        sm.keys().forEach { k -> k.toIntOrNull()?.let { map[it] = sm.optString(k) } }
                        if (map.isNotEmpty()) writeSyncMeta(File(tdir, "sync_meta.json"), map)
                    }

                    val d = readIndexData()
                    val (folderId, folders) = resolveFolder(d.folders, m.optString("folder").takeIf { it.isNotBlank() })
                    val book = Book(
                        id = id,
                        title = title,
                        mode = mode,
                        pageCount = pages.size,
                        coverFile = pages.first(),
                        pageFiles = pages,
                        folderId = folderId,
                        cloudId = null,
                        hash = hash.ifBlank { sha256OfPages(pages) },
                        fingerprint = m.optString("fingerprint").ifBlank { fingerprint(pages) },
                        createdAt = System.currentTimeMillis(),
                    )
                    writeIndex(d.books + book, folders)
                    applyIncomingProgress(id, m)
                    success = true
                    TransferImportResult(book, false, translatedCount, 0)
                } finally {
                    if (!success) dir.deleteRecursively()
                }
            }
        }

    private fun safeTransferName(name: String): Boolean =
        name.isNotBlank() && !name.contains("/") && !name.contains("\\") && !name.contains("..")

    /** 应用对传/同步来的阅读进度（LWW：只接受更新的）。 */
    private fun applyIncomingProgress(bookId: String, m: JSONObject) {
        val p = m.optJSONObject("readingProgress") ?: return
        val page = p.optInt("page", 0)
        val at = p.optLong("lastReadAt", 0L)
        if (at <= 0L) return
        val local = readingProgress(bookId)
        if (local == null || at > local.lastReadAt) {
            setReadingProgressAt(bookId, page, at)
        }
    }

    // ---------------------------------------------------------------- index 持久化

    private fun readIndexData(): IndexData {
        indexCache?.let { return it }
        if (!indexFile.exists()) return IndexData(emptyList(), emptyList())
        val text = indexFile.readText()
        // 老版本是纯数组（只有 books），迁移成新格式
        runCatching { JSONArray(text) }.getOrNull()?.let { return IndexData(parseBooks(it), emptyList()) }
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return IndexData(emptyList(), emptyList())
        val books = obj.optJSONArray("books")?.let { parseBooks(it) } ?: emptyList()
        val folders = obj.optJSONArray("folders")?.let { fa ->
            (0 until fa.length()).map { i ->
                val o = fa.getJSONObject(i)
                Folder(o.getString("id"), o.optString("name", "未命名"))
            }
        } ?: emptyList()
        return IndexData(books, folders).also { indexCache = it }
    }

    private fun parseBooks(arr: JSONArray): List<Book> {
        val out = mutableListOf<Book>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val pagesDir = File(bookDir(id), "pages")
            val pages = pagesDir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
            if (pages.isEmpty()) continue
            out += Book(
                id = id,
                title = o.optString("title", "未命名"),
                mode = if (o.optString("mode") == "normal") ReadingMode.NORMAL else ReadingMode.MANGA,
                pageCount = pages.size,
                coverFile = pages.first(),
                pageFiles = pages,
                folderId = if (o.isNull("folder_id")) null else o.optString("folder_id").takeIf { it.isNotBlank() },
                cloudId = if (o.isNull("cloud_id")) null else o.optString("cloud_id").takeIf { it.isNotBlank() },
                hash = o.optString("hash", ""),
                fingerprint = o.optString("fingerprint", ""),
                createdAt = o.optLong("created_at", 0L),
            )
        }
        return out
    }

    private fun writeIndex(books: List<Book>, folders: List<Folder>) {
        val obj = JSONObject()
        val barr = JSONArray()
        books.forEach { b ->
            barr.put(JSONObject().apply {
                put("id", b.id)
                put("title", b.title)
                put("mode", if (b.mode == ReadingMode.NORMAL) "normal" else "manga")
                b.folderId?.let { put("folder_id", it) }
                b.cloudId?.let { put("cloud_id", it) }
                if (b.hash.isNotEmpty()) put("hash", b.hash)
                if (b.fingerprint.isNotEmpty()) put("fingerprint", b.fingerprint)
                put("created_at", b.createdAt)
            })
        }
        obj.put("books", barr)
        val farr = JSONArray()
        folders.forEach { f ->
            farr.put(JSONObject().apply { put("id", f.id); put("name", f.name) })
        }
        obj.put("folders", farr)
        val data = IndexData(books, folders)
        val tempFile = File(internalRoot, "index.json.tmp")
        tempFile.writeText(obj.toString())
        val renamed = tempFile.renameTo(indexFile)
        if (!renamed) {
            indexFile.delete()
            if (tempFile.renameTo(indexFile)) {
                indexCache = data
            } else {
                tempFile.delete()
                throw IllegalStateException("无法保存书库索引")
            }
        } else {
            indexCache = data
        }
    }
}
