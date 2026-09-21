// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/serializers/UriSerializer.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils.serializers

import android.net.Uri
import com.lagradost.cloudstream3.InternalAPI
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom KSerializer for Android's [Uri] type.
 */
@InternalAPI
object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uri) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri {
        return Uri.parse(decoder.decodeString())
    }
}
