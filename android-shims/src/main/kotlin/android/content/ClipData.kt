package android.content

import android.net.Uri

/**
 * Android ClipData shim for desktop runtime.
 * Encapsulates clip items holding text or URIs for intent data transfer.
 */
class ClipData(
    val description: Any? = null
) {
    class Item(
        val text: CharSequence? = null,
        val uri: Uri? = null
    )

    private val items = mutableListOf<Item>()

    constructor(label: CharSequence?, mimeTypes: Array<String>?, item: Item) : this(null) {
        items.add(item)
    }

    constructor(item: Item) : this(null) {
        items.add(item)
    }

    val itemCount: Int
        get() = items.size

    fun getItemAt(index: Int): Item? = items.getOrNull(index)

    fun addItem(item: Item) {
        items.add(item)
    }

    companion object {
        @JvmStatic
        fun newRawUri(label: CharSequence?, uri: Uri?): ClipData {
            val cd = ClipData()
            cd.addItem(Item(uri = uri))
            return cd
        }

        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData {
            val cd = ClipData()
            cd.addItem(Item(text = text))
            return cd
        }
    }
}
