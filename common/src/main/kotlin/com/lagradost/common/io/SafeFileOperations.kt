package com.lagradost.common.io

import com.lagradost.common.logging.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Endüstriyel seviye çapraz platform dosya operasyonları motoru.
 *
 * Windows NTFS zorunlu dosya kilitleri, virüs tarayıcı gecikmeleri ve
 * sürücüler arası (C: -> D:) dosya taşımada ortaya çıkan
 * AtomicMoveNotSupportedException hatalarını tolere eder.
 */
object SafeFileOperations {
    private const val TAG = "SafeFileOperations"
    private const val DEFAULT_BUFFER_SIZE = 64 * 1024 * 1024L // 64 MiB yüksek verimli transfer bloğu
    private const val MAX_LOCK_RETRIES = 5
    private const val INITIAL_BACKOFF_MS = 50L

    /**
     * Kaynak dosyayı hedef konuma veri kaybı riski olmadan güvenle taşır.
     *
     * @param source Taşınacak kaynak dosya yolu.
     * @param target Nihai hedef dosya yolu.
     * @param verifyIntegrity Küçük veya kritik dosyalarda boyut kontrolüne ek olarak başlık doğrulaması yapar.
     * @return Taşıma başarılı ise true döner.
     */
    @Throws(IOException::class)
    fun safeMove(
        source: Path,
        target: Path,
        verifyIntegrity: Boolean = false
    ): Boolean {
        val sourceFile = source.toFile()
        if (!sourceFile.exists()) {
            throw NoSuchFileException(source.toString(), null, "Kaynak dosya mevcut değil")
        }

        val absSource = source.toAbsolutePath().normalize()
        val absTarget = target.toAbsolutePath().normalize()

        // Kaynak ve hedef aynı ise hiçbir işlem yapma
        if (absSource == absTarget) {
            return true
        }

        // Hedef üst dizinini garantiye al
        val targetParent = absTarget.parent
        if (targetParent != null && !Files.exists(targetParent)) {
            Files.createDirectories(targetParent)
        }

        // -----------------------------------------------------------------
        // SEVİYE 1: Fast Path (Aynı Bölüm İçi Atomik Taşıma)
        // -----------------------------------------------------------------
        try {
            Files.move(
                absSource,
                absTarget,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            AppLogger.d(TAG, "Seviye 1: Atomik taşıma başarılı: $absSource -> $absTarget")
            return true
        } catch (_: AtomicMoveNotSupportedException) {
            AppLogger.d(TAG, "Seviye 1 başarısız (Farklı sürücü veya dosya sistemi). Seviye 2'ye geçiliyor.")
        } catch (lockEx: FileSystemException) {
            // Windows üzerinde hedef kilitliyse kısa bir bekleme ile tekrar dene
            AppLogger.w(TAG, "Seviye 1 kilit engeline takıldı (${lockEx.message}). Yeniden denenecek.")
            if (retryAtomicMove(absSource, absTarget)) {
                return true
            }
        }

        // -----------------------------------------------------------------
        // SEVİYE 2: Standard Move + Retry Backoff (Windows Kilit Toleransı)
        // -----------------------------------------------------------------
        try {
            if (performMoveWithRetry(absSource, absTarget)) {
                AppLogger.d(TAG, "Seviye 2: Standart taşıma başarılı: $absSource -> $absTarget")
                return true
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Seviye 2 standart taşıma başarısız oldu (${e.message}). Seviye 3'e düşülüyor.")
        }

        // -----------------------------------------------------------------
        // SEVİYE 3: Defensive Staged Copy-Verify-Delete (Sürücüler Arası Kesin Güvence)
        // -----------------------------------------------------------------
        return performStagedCopyVerifyDelete(absSource, absTarget, verifyIntegrity)
    }

    /**
     * Windows dosya kilidi çözülene kadar üstel geri çekilme ile atomik taşımayı yineler.
     */
    private fun retryAtomicMove(source: Path, target: Path): Boolean {
        var delayMs = INITIAL_BACKOFF_MS
        for (attempt in 1..MAX_LOCK_RETRIES) {
            try {
                Thread.sleep(delayMs)
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                return true
            } catch (_: AtomicMoveNotSupportedException) {
                return false // Sürücü farklıysa beklemenin anlamı yok, Seviye 2/3'e geç
            } catch (_: FileSystemException) {
                delayMs *= 2
            }
        }
        return false
    }

    /**
     * Standart Files.move işlemini Windows kilit toleransıyla üstel geri çekilme ile dener.
     */
    private fun performMoveWithRetry(source: Path, target: Path): Boolean {
        var delayMs = INITIAL_BACKOFF_MS
        var lastException: Exception? = null

        for (attempt in 1..MAX_LOCK_RETRIES) {
            try {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
                )
                return true
            } catch (e: FileSystemException) {
                lastException = e
                AppLogger.d(TAG, "Taşıma denemesi $attempt/$MAX_LOCK_RETRIES kilit nedeniyle beklenecek (${e.message})")
                Thread.sleep(delayMs)
                delayMs *= 2
            } catch (e: IOException) {
                lastException = e
                break // Genel I/O hatalarında (örn: disk dolu) beklemeye gerek yok
            }
        }
        if (lastException != null) {
            AppLogger.w(TAG, "Standart taşıma $MAX_LOCK_RETRIES deneme sonrasında tamamlanamadı: ${lastException.message}")
        }
        return false
    }

    /**
     * Farklı sürücüler arasında 64 MiB NIO kanalları ile staging dosyasına kopyalar,
     * boyut doğrulaması yapar ve kaynağı temizler.
     */
    private fun performStagedCopyVerifyDelete(
        source: Path,
        target: Path,
        verifyIntegrity: Boolean
    ): Boolean {
        val sourceSize = Files.size(source)
        val stagingFileName = "${target.fileName}.crswap.${UUID.randomUUID()}"
        val stagingPath = target.resolveSibling(stagingFileName)

        AppLogger.i(TAG, "Seviye 3 başlatılıyor: $source ($sourceSize bayt) -> Staging: $stagingPath")

        try {
            // 1. NIO FileChannel ile yüksek hızlı akış transferi
            FileInputStream(source.toFile()).channel.use { srcChannel ->
                FileOutputStream(stagingPath.toFile()).channel.use { dstChannel ->
                    var transferred = 0L
                    while (transferred < sourceSize) {
                        val count = srcChannel.transferTo(
                            transferred,
                            DEFAULT_BUFFER_SIZE,
                            dstChannel
                        )
                        if (count <= 0) break
                        transferred += count
                    }
                    dstChannel.force(true) // fsync: Veriyi ve meta veriyi fiziksel depolamaya yaz
                }
            }

            // 2. Boyut ve Bütünlük Doğrulaması
            val stagingSize = Files.size(stagingPath)
            if (stagingSize != sourceSize) {
                throw IOException("Kopyalama bütünlük hatası: Kaynak boyutu ($sourceSize) ile hedef boyutu ($stagingSize) uyuşmuyor!")
            }

            if (verifyIntegrity && sourceSize > 0) {
                // 50 MB altı için içerik bayt doğrulaması
                if (sourceSize <= 50 * 1024 * 1024L) {
                    FileInputStream(source.toFile()).use { srcStream ->
                        FileInputStream(stagingPath.toFile()).use { dstStream ->
                            val buf1 = ByteArray(8192)
                            val buf2 = ByteArray(8192)
                            var read1: Int
                            while (srcStream.read(buf1).also { read1 = it } != -1) {
                                val read2 = dstStream.read(buf2)
                                if (read1 != read2 || !buf1.sliceArray(0 until read1).contentEquals(buf2.sliceArray(0 until read2))) {
                                    throw IOException("Bütünlük hatası: İçerik uyuşmuyor!")
                                }
                            }
                        }
                    }
                }
            }

            // 3. Staging dosyasını nihai hedefe taşı (Aynı disk bölümünde olduğu için kesinlikle atomiktir)
            try {
                Files.move(
                    stagingPath,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                // Windows'ta hedef dosya kilitliyse retry ile ez
                if (!performMoveWithRetry(stagingPath, target)) {
                    throw IOException("Staging dosyasını nihai hedefe taşıma başarısız: $stagingPath -> $target")
                }
            }

            AppLogger.i(TAG, "Seviye 3 kopyalama ve kesinleştirme tamamlandı: $target")

            // 4. Kaynak dosyayı güvenle temizle
            safeDelete(source)

            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Seviye 3 transfer sırasında kritik hata oluştu, geri alınıyor.", e)
            // Geri Alma (Rollback): Yarım kalan staging dosyasını sil, kaynak dosyayı koru
            try {
                Files.deleteIfExists(stagingPath)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Staging temizliği başarısız: ${t.message}")
            }
            throw e
        }
    }

    /**
     * Windows kilitlerini tolere ederek tekil bir dosyayı siler.
     */
    fun safeDelete(path: Path): Boolean {
        val file = path.toFile()
        if (!file.exists()) return true

        // Salt okunur bayrağı varsa temizle
        if (!file.canWrite()) {
            file.setWritable(true)
        }

        var delayMs = INITIAL_BACKOFF_MS
        for (attempt in 1..MAX_LOCK_RETRIES) {
            try {
                Files.deleteIfExists(path)
                return true
            } catch (_: FileSystemException) {
                Thread.sleep(delayMs)
                delayMs *= 2
            } catch (e: IOException) {
                AppLogger.w(TAG, "Dosya silinemedi: $path (${e.message})")
                break
            }
        }

        // Son çare: JVM kapanırken silinmesi için kaydet
        file.deleteOnExit()
        AppLogger.w(TAG, "Dosya anında silinemedi, deleteOnExit olarak işaretlendi: $path")
        return false
    }

    /**
     * Dizinleri içindeki dosyalarla birlikte Windows kilitlerine toleranslı olarak siler.
     */
    fun safeDeleteRecursively(root: File): Boolean {
        if (!root.exists()) return true

        var allClean = true
        root.walkBottomUp().forEach { file ->
            if (!file.canWrite()) {
                file.setWritable(true)
            }
            var deleted = false
            var delayMs = 20L
            for (attempt in 1..3) {
                if (file.delete()) {
                    deleted = true
                    break
                }
                Thread.sleep(delayMs)
                delayMs *= 2
            }
            if (!deleted) {
                file.deleteOnExit()
                allClean = false
            }
        }
        return allClean
    }
}
