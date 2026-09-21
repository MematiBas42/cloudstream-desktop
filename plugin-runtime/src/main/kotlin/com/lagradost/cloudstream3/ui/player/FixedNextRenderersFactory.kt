// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/FixedNextRenderersFactory.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * NextLib Media3 Text Renderer Factory Patch
 *
 * Quarantined because NextLib and ExoPlayer text renderers are Android-specific.
 * Linux desktop renders subtitles via libmpv and libass natively with hardware acceleration.
 */
@PlatformQuarantine(
    reason = "libmpv/FFmpeg tüm demuxing işlemini native C katmanında yapar; ExoPlayer Java demuxer yamaları masaüstünde işlevsizdir.",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/FixedNextRenderersFactory.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class FixedNextRenderersFactory
