package com.lagradost.cloudstream3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.NewPipe

class DownloaderTestImplTest {

    @Test
    fun `getInstance returns singleton DownloaderTestImpl`() {
        val instance1 = DownloaderTestImpl.getInstance()
        val instance2 = DownloaderTestImpl.getInstance()

        assertNotNull(instance1)
        assertSame(instance1, instance2)
    }

    @Test
    fun `NewPipe init binds DownloaderTestImpl singleton`() {
        val downloader = DownloaderTestImpl.getInstance()
        assertNotNull(downloader)

        NewPipe.init(downloader)
        assertEquals(downloader, NewPipe.getDownloader())
    }
}
