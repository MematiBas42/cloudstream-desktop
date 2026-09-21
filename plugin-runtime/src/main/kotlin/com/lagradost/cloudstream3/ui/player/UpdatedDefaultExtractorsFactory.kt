// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/UpdatedDefaultExtractorsFactory.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * ExoPlayer Extractors Factory Patch
 *
 * Quarantined because libmpv and FFmpeg automatically detect and select format demuxers natively.
 * The Java-based ExoPlayer extractor factory patches are not applicable to the desktop platform.
 */
@PlatformQuarantine(
    reason = "libmpv/FFmpeg tüm demuxing işlemini native C katmanında yapar; ExoPlayer Java demuxer yamaları masaüstünde işlevsizdir.",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/UpdatedDefaultExtractorsFactory.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class UpdatedDefaultExtractorsFactory
