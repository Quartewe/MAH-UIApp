package com.aliothmoon.maafw.project

import android.content.Context
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * 打包进 APK 的 PI 只读包
 * 不复用 ProjectSource：解包要按字节搬运图片与模型，read(String) 的文本语义不够用
 */
interface PiPackage {
    /** 无归档时按这条逐个 [open]；生产走 [openArchive]，清单不参与解包 */
    fun manifest(): List<String>

    fun open(path: String): InputStream

    /** PI 归档；解包按 zip 顺序扫一遍。单测内存包没有这份 */
    fun openArchive(): InputStream? = null
}

/** zip-slip / 缺根文件：解包失败，由 [PiInstallCoordinator] 收成 Failed */
class PiUnpackException(message: String) : IOException(message)

/** [PiInstaller.installedDir] 在标记未提交时抛；不解包 */
class PiNotInstalledException(dir: File) : IllegalStateException("PI is not installed: ${dir.absolutePath}")

/** 路径一律相对 PI 根，与 [ProjectSource] 保持同一套相对路径语义 */
class AssetPiPackage(context: Context) : PiPackage {

    private val assets = context.applicationContext.assets

    override fun manifest(): List<String> = emptyList()

    override fun open(path: String): InputStream = throw FileNotFoundException(path)

    override fun openArchive(): InputStream? = try {
        assets.open(PI_ARCHIVE_ASSET)
    } catch (_: FileNotFoundException) {
        null
    }
}

/**
 * 逐条目的解包进度；done 从 1 起计
 * 归档扫 zip 时不知道总数，[total] 为 0；单测内存包 [total] 是清单条数
 */
typealias PiUnpackProgress = (done: Int, total: Int, path: String) -> Unit

/**
 * 把打包的 PI 解包到应用外部私有目录
 * native MaaFramework 只认文件系统路径，而 APK 内的 assets 条目不是文件；落点不能用 filesDir——
 * 特权进程是 shell 身份，进不去 0700 的 app 私有目录（docs/privileged-runtime.md §9）
 *
 * 标记文件记 versionCode，与本次运行的不符即整体重解
 * 解包只由 [PiInstallCoordinator] 发起；取路径的地方一律用 [installedDir]，别在读一个文件时
 * 顺带搬几十 MB
 */
class PiInstaller(
    private val pkg: PiPackage,
    private val versionCode: Int,
) {

    /**
     * 已解包的 PI 根目录，本身不解包
     * 就绪定义与 [ensureInstalled] 相同：目录在、标记与本次 versionCode 一致
     */
    fun installedDir(): File {
        val target = File(AppPaths.ROOT, AppFiles.PI_DIR)
        if (!isCurrentInstall(AppPaths.ROOT, target)) {
            throw PiNotInstalledException(target)
        }
        return target
    }

    /**
     * 标记与本次运行的 versionCode 一致就直接返回，否则整体重解
     * 阻塞 IO：调用方须在 IO 线程
     */
    @Synchronized
    fun ensureInstalled(onProgress: PiUnpackProgress = NO_PROGRESS): File {
        val base = AppPaths.ROOT
        val target = File(base, AppFiles.PI_DIR)
        ProjectPackageInstaller(target).recover()
        recoverBootstrap(base, target)
        if (isCurrentInstall(base, target)) return target
        return install(base, onProgress)
    }

    /** 不看标记，无条件重解；设置页的手动重来与失败重试都走这条 */
    @Synchronized
    fun reinstall(onProgress: PiUnpackProgress = NO_PROGRESS): File =
        install(AppPaths.ROOT, onProgress)

    @Synchronized
    fun installUpdate(archive: File, target: ProjectPackageTarget, version: String) =
        ProjectPackageInstaller(installedDir()).install(archive, target, version)

    private fun isCurrentInstall(base: File, target: File): Boolean {
        val marker = File(base, PI_MARKER_NAME)
        return target.isDirectory && marker.isFile &&
            (marker.readText().trim() == versionCode.toString() ||
                (File(target, ProjectPackageInstaller.STATE).isFile && File(target, INTERFACE_JSON).isFile))
    }

    private fun install(base: File, onProgress: PiUnpackProgress): File {
        val target = File(base, AppFiles.PI_DIR)
        val marker = File(base, PI_MARKER_NAME)

        val staged = File(base, ".pi-bootstrap")
        val backup = File(base, ".pi-bootstrap-old")
        recoverBootstrap(base, target)
        staged.deleteRecursively()
        staged.mkdirs()
        unpack(staged, onProgress)
        val state = File(staged, ProjectPackageInstaller.STATE)
        if (state.isFile) {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val installed = json.decodeFromString<InstalledProjectPackages>(state.readText())
            state.writeText(json.encodeToString(installed.copy(revision = java.util.UUID.randomUUID().toString())))
        }
        // Repairing the bundled resources must not erase user progress and custom combat files.
        for (name in listOf("config", "data", "debug")) {
            File(target, name).takeIf(File::isDirectory)?.copyRecursively(File(staged, name), overwrite = true)
        }
        if (target.exists()) check(target.renameTo(backup)) { "Cannot back up PI" }
        if (!staged.renameTo(target)) {
            backup.renameTo(target)
            throw PiUnpackException("Cannot activate PI")
        }
        ensureNoMedia(base)
        marker.writeText(versionCode.toString())
        backup.deleteRecursively()
        File(base, LEGACY_MARKER_NAME).delete()
        return target
    }

    private fun recoverBootstrap(base: File, target: File) {
        val backup = File(base, ".pi-bootstrap-old")
        if (backup.exists()) {
            if (!target.exists()) check(backup.renameTo(target)) else backup.deleteRecursively()
        }
    }

    private fun unpack(dest: File, onProgress: PiUnpackProgress) {
        val archive = pkg.openArchive()
        if (archive != null) {
            ZipInputStream(archive).use { zip -> unpackZip(dest, zip, onProgress) }
            return
        }

        val entries = pkg.manifest()
        if (entries.isEmpty()) return
        entries.mapNotNullTo(mutableSetOf()) { File(dest, it).parentFile }.forEach { it.mkdirs() }
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        val done = AtomicInteger()
        for (entry in entries) {
            pkg.open(entry).use { input ->
                File(dest, entry).outputStream().use { output ->
                    input.copyTo(output, buffer)
                }
            }
            onProgress(done.incrementAndGet(), entries.size, entry)
        }
        requireInterfaceJson(dest)
    }

    private fun unpackZip(dest: File, zip: ZipInputStream, onProgress: PiUnpackProgress) {
        val destCanon = dest.canonicalFile
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var done = 0
        while (true) {
            val zipEntry = zip.nextEntry ?: break
            if (zipEntry.isDirectory) {
                zip.closeEntry()
                continue
            }
            val name = normalizeZipName(zipEntry.name)
            val outFile = File(dest, name)
            val outCanon = outFile.canonicalFile
            if (outCanon != destCanon && !outCanon.path.startsWith(destCanon.path + File.separator)) {
                throw PiUnpackException("illegal PI archive entry: $name")
            }
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { output -> zip.copyTo(output, buffer) }
            zip.closeEntry()
            done++
            onProgress(done, 0, name)
        }
        if (done > 0) requireInterfaceJson(dest)
    }

    private fun requireInterfaceJson(dest: File) {
        if (!File(dest, INTERFACE_JSON).isFile) {
            throw PiUnpackException("PI archive is missing $INTERFACE_JSON")
        }
    }

    private fun normalizeZipName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || name.split('/').any { it == ".." }) {
            throw PiUnpackException("illegal PI archive entry: $raw")
        }
        return name
    }

    /** 外部私有目录会被媒体扫描，PI 里的 PNG 会整片进相册 */
    private fun ensureNoMedia(base: File) {
        val noMedia = File(base, NO_MEDIA_NAME)
        if (!noMedia.exists()) {
            runCatching { noMedia.createNewFile() }
        }
    }

    private fun InputStream.copyTo(out: OutputStream, buffer: ByteArray) {
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
    }

    companion object {
        /** 提交标记：解包全部成功后才写，内容是出这个包时的 versionCode */
        const val PI_MARKER_NAME = "pi.version"

        /** 按内容指纹判过期的旧包留下的标记 */
        private const val LEGACY_MARKER_NAME = "pi.fingerprint"

        private const val INTERFACE_JSON = "interface.json"
        private const val NO_MEDIA_NAME = ".nomedia"
        private const val COPY_BUFFER_BYTES = 128 * 1024

        private val NO_PROGRESS: PiUnpackProgress = { _, _, _ -> }
    }
}

/** 构建期 packPiArchive 落在 assets 根的 PI 归档；散装 assets 会被 AAPT 改写 */
internal const val PI_ARCHIVE_ASSET = "pi.zip"
