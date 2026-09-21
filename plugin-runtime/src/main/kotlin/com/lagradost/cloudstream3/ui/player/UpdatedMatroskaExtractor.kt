// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * ExoPlayer Matroska Extractor Patch
 *
 * Quarantined because libmpv and FFmpeg handle all container demuxing natively in C.
 * The Java-based ExoPlayer demuxer patches are not applicable to the desktop platform.
 */
@PlatformQuarantine(
    reason = "libmpv/FFmpeg tüm demuxing işlemini native C katmanında yapar; ExoPlayer Java demuxer yamaları masaüstünde işlevsizdir.",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class UpdatedMatroskaExtractor
