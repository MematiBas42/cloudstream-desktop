// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/subtitles/SubtitlesFragment.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.subtitles

import androidx.media3.ui.CaptionStyleCompat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.DataStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SUBTITLE_KEY = "subtitle_settings"
const val SUBTITLE_AUTO_SELECT_KEY = "subs_auto_select"
const val SUBTITLE_DOWNLOAD_KEY = "subs_auto_download"
const val DEF_SUBS_ELEVATION = 20

@Serializable
enum class SubtitleFont(
    val resource: Int,
    val label: String,
    val desktopFontFamily: String,
) {
    @JsonProperty("Trebuchet")
    @SerialName("Trebuchet")
    Trebuchet(0, "Trebuchet MS", "Trebuchet MS, DejaVu Sans, Arial, sans-serif"),

    @JsonProperty("Netflix")
    @SerialName("Netflix")
    Netflix(0, "Netflix Sans", "Netflix Sans, Graphik, Helvetica Neue, DejaVu Sans, sans-serif"),

    @JsonProperty("Google")
    @SerialName("Google")
    Google(0, "Google Sans", "Google Sans, Product Sans, Roboto, DejaVu Sans, sans-serif"),

    @JsonProperty("Open")
    @SerialName("Open")
    Open(0, "Open Sans", "Open Sans, DejaVu Sans, Liberation Sans, sans-serif"),

    @JsonProperty("Futura")
    @SerialName("Futura")
    Futura(0, "Futura", "Futura, Century Gothic, DejaVu Sans, sans-serif"),

    @JsonProperty("Consola")
    @SerialName("Consola")
    Consola(0, "Consola", "Consolas, Inconsolata, DejaVu Sans Mono, monospace"),

    @JsonProperty("Gotham")
    @SerialName("Gotham")
    Gotham(0, "Gotham", "Gotham, Montserrat, DejaVu Sans, sans-serif"),

    @JsonProperty("Lucida")
    @SerialName("Lucida")
    Lucida(0, "Lucida Grande", "Lucida Grande, Lucida Sans Unicode, DejaVu Sans, sans-serif"),

    @JsonProperty("STIX")
    @SerialName("STIX")
    STIX(0, "STIX General", "STIX General, STIXGeneral, DejaVu Serif, serif"),

    @JsonProperty("TimesNewRoman")
    @SerialName("TimesNewRoman")
    TimesNewRoman(0, "Times New Roman", "Times New Roman, Liberation Serif, Times, serif"),

    @JsonProperty("Verdana")
    @SerialName("Verdana")
    Verdana(0, "Verdana", "Verdana, DejaVu Sans, sans-serif"),

    @JsonProperty("Ubuntu")
    @SerialName("Ubuntu")
    Ubuntu(0, "Ubuntu", "Ubuntu, DejaVu Sans, sans-serif"),

    @JsonProperty("Comic")
    @SerialName("Comic")
    Comic(0, "Comic Sans", "Comic Sans MS, Comic Sans, sans-serif"),

    @JsonProperty("Poppins")
    @SerialName("Poppins")
    Poppins(0, "Poppins", "Poppins, DejaVu Sans, sans-serif"),

    @JsonProperty("Custom")
    @SerialName("Custom")
    Custom(0, "Custom", "sans-serif");

    val fontName: String get() = label

    fun getDesktopFont(customPath: String? = null): String {
        if (!customPath.isNullOrBlank()) return customPath
        return desktopFontFamily
    }

    companion object {
        fun fromLabel(label: String?): SubtitleFont? {
            if (label == null) return null
            return entries.firstOrNull {
                it.label.equals(label, ignoreCase = true) || it.name.equals(label, ignoreCase = true)
            }
        }
    }
}

@Serializable
data class SaveCaptionStyle(
    @JsonProperty("foregroundColor") @SerialName("foregroundColor") val foregroundColor: Int = -1,
    @JsonProperty("backgroundColor") @SerialName("backgroundColor") val backgroundColor: Int = 0,
    @JsonProperty("windowColor") @SerialName("windowColor") val windowColor: Int = 0,
    @JsonProperty("edgeType") @SerialName("edgeType") val edgeType: Int = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    @JsonProperty("edgeColor") @SerialName("edgeColor") val edgeColor: Int = -16777216,
    @JsonProperty("font") @SerialName("font") val font: SubtitleFont? = null,
    @JsonProperty("typefaceFilePath") @SerialName("typefaceFilePath") val typefaceFilePath: String? = null,
    @JsonProperty("elevation") @SerialName("elevation") val elevation: Int = DEF_SUBS_ELEVATION, // in dp
    @JsonProperty("fixedTextSize") @SerialName("fixedTextSize") val fixedTextSize: Float? = null, // in sp
    @JsonProperty("edgeSize") @SerialName("edgeSize") val edgeSize: Float? = null,
    @JsonProperty("removeCaptions") @SerialName("removeCaptions") val removeCaptions: Boolean = false,
    @JsonProperty("removeBloat") @SerialName("removeBloat") val removeBloat: Boolean = true,
    @JsonProperty("upperCase") @SerialName("upperCase") val upperCase: Boolean = false,
    @JsonProperty("bold") @SerialName("bold") val bold: Boolean = false,
    @JsonProperty("italic") @SerialName("italic") val italic: Boolean = false,
    @JsonProperty("backgroundRadius") @SerialName("backgroundRadius") val backgroundRadius: Float? = null,
    @JsonProperty("alignment") @SerialName("alignment") val alignment: Int? = null,
) {
    @get:JsonIgnore
    val typeface: String?
        get() = typefaceFilePath ?: font?.label

    constructor(
        foregroundColor: Int = -1,
        backgroundColor: Int = 0,
        windowColor: Int = 0,
        edgeType: Int = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        edgeColor: Int = -16777216,
        typeface: String?,
        elevation: Int = DEF_SUBS_ELEVATION,
        fixedTextSize: Float? = null,
    ) : this(
        foregroundColor = foregroundColor,
        backgroundColor = backgroundColor,
        windowColor = windowColor,
        edgeType = edgeType,
        edgeColor = edgeColor,
        font = SubtitleFont.fromLabel(typeface),
        typefaceFilePath = typeface,
        elevation = elevation,
        fixedTextSize = fixedTextSize,
    )

    fun toJson(): String {
        return DataStore.mapper.writeValueAsString(this)
    }

    companion object {
        fun fromJson(json: String): SaveCaptionStyle? {
            return runCatching {
                DataStore.mapper.readValue(json, SaveCaptionStyle::class.java)
            }.getOrNull()
        }
    }
}
