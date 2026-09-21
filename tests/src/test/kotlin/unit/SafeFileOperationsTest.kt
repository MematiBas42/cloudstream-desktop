package unit

import com.lagradost.common.io.SafeFileOperations
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.concurrent.thread

class SafeFileOperationsTest {

    @Test
    fun `testSameVolumeAtomicMove succeeds instantaneously`(@TempDir tempDir: Path) {
        val src = tempDir.resolve("source.bin")
        val dst = tempDir.resolve("target.bin")
        val content = "CloudStream Atomic Move Test Payload".toByteArray()
        Files.write(src, content)

        val success = SafeFileOperations.safeMove(src, dst)

        assertTrue(success)
        assertFalse(Files.exists(src), "Kaynak dosya silinmiş olmalıdır")
        assertTrue(Files.exists(dst), "Hedef dosya mevcut olmalıdır")
        assertArrayEquals(content, Files.readAllBytes(dst))
    }

    @Test
    fun `testCrossDirectoryFallbackWithIntegrity`(@TempDir tempDir: Path) {
        val dir1 = Files.createDirectory(tempDir.resolve("volume1"))
        val dir2 = Files.createDirectory(tempDir.resolve("volume2"))

        val src = dir1.resolve("video_test.part")
        val dst = dir2.resolve("video_test.mp4")

        // 5 MiB yapay veri bloğu oluştur
        val testData = ByteArray(5 * 1024 * 1024) { (it % 127).toByte() }
        Files.write(src, testData)

        val success = SafeFileOperations.safeMove(src, dst, verifyIntegrity = true)

        assertTrue(success)
        assertFalse(Files.exists(src), "Kaynak dosya silinmiş olmalıdır")
        assertTrue(Files.exists(dst), "Hedef dosya mevcut olmalıdır")
        assertEquals(testData.size.toLong(), Files.size(dst))
        assertArrayEquals(testData, Files.readAllBytes(dst))
    }

    @Test
    fun `testTransientLockRecovery simulates Windows Defender delay`(@TempDir tempDir: Path) {
        val src = tempDir.resolve("downloaded.part")
        val dst = tempDir.resolve("downloaded.mp4")
        val data = "Transient Lock Video Payload".toByteArray()
        Files.write(src, data)

        // Hedef dosyayı önceden açıp 120 ms boyunca kilit altında tutan simüle edilmiş antivirüs iş parçacığı
        val lockThread = thread(start = true) {
            try {
                RandomAccessFile(dst.toFile(), "rw").use { raf ->
                    val lock: FileLock? = raf.channel.tryLock()
                    Thread.sleep(120) // 120 ms kilit tut (Antivirüs taraması simülasyonu)
                    lock?.release()
                }
            } catch (_: Throwable) {}
        }

        // safeMove kilidin çözülmesini üstel geri çekilme ile beklemelidir
        val success = SafeFileOperations.safeMove(src, dst)
        lockThread.join()

        assertTrue(success, "safeMove geçici kilit serbest kaldığında taşımayı tamamlamalıdır")
        assertTrue(Files.exists(dst), "Hedef dosya mevcut olmalıdır")
        assertArrayEquals(data, Files.readAllBytes(dst))
    }

    @Test
    fun `testStagingRollbackOnFailurePreservesSource`(@TempDir tempDir: Path) {
        val src = tempDir.resolve("original.part")
        val dst = tempDir.resolve("read_only_folder/target.mp4")
        val data = "Important Data That Must Not Be Lost".toByteArray()
        Files.write(src, data)

        // Hedef üst klasörünü oluştur ve yazma iznini kaldır (hata fırlatmaya zorla)
        val targetDir = tempDir.resolve("read_only_folder")
        Files.createDirectories(targetDir)
        targetDir.toFile().setReadOnly()

        try {
            SafeFileOperations.safeMove(src, dst)
        } catch (_: Exception) {
            // Hata fırlatılması beklenir
        } finally {
            targetDir.toFile().setWritable(true) // Test temizliği için izinleri aç
        }

        // En kritik güvence: Hata olsa bile kaynak dosya ASLA silinmemeli veya bozulmamalıdır
        assertTrue(Files.exists(src), "Taşıma başarısız olduğunda kaynak dosya kesinlikle korunmalıdır")
        assertArrayEquals(data, Files.readAllBytes(src))
    }

    @Test
    fun `testSafeDeleteRecursivelyRemovesLockedAndReadOnlyFiles`(@TempDir tempDir: Path) {
        val testDir = Files.createDirectory(tempDir.resolve("torrent_tmp"))
        Files.write(testDir.resolve("chunk1.dat"), byteArrayOf(1, 2, 3))
        val readOnlyFile = Files.write(testDir.resolve("chunk2.dat"), byteArrayOf(4, 5, 6))
        readOnlyFile.toFile().setReadOnly()

        val clean = SafeFileOperations.safeDeleteRecursively(testDir.toFile())

        assertTrue(clean, "Tüm dosyalar ve salt-okunur dosyalar temizlenmelidir")
        assertFalse(Files.exists(testDir), "Dizin silinmiş olmalıdır")
    }

    @Test
    fun `testNonExistentSourceThrowsAndSameSourceIsNoOp`(@TempDir tempDir: Path) {
        val nonExistent = tempDir.resolve("non_existent.bin")
        val target = tempDir.resolve("target.bin")

        assertThrows(NoSuchFileException::class.java) {
            SafeFileOperations.safeMove(nonExistent, target)
        }

        val existing = tempDir.resolve("existing.bin")
        val content = "Same Source No Op".toByteArray()
        Files.write(existing, content)

        val noOpSuccess = SafeFileOperations.safeMove(existing, existing)
        assertTrue(noOpSuccess, "Aynı dosya yolu verildiğinde no-op olarak true dönmelidir")
        assertTrue(Files.exists(existing), "Mevcut dosya korunmalıdır")
        assertArrayEquals(content, Files.readAllBytes(existing))
    }
}
