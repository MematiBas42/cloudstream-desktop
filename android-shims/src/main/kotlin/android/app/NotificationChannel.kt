package android.app

class NotificationChannel(
    val id: String,
    var name: CharSequence,
    var importance: Int = NotificationManager.IMPORTANCE_DEFAULT
) {
    var description: String? = null
    var enableVibration: Boolean = false
    var enableLights: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotificationChannel) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "NotificationChannel(id='$id', name='$name', importance=$importance)"
}
