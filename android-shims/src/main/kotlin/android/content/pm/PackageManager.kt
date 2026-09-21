package android.content.pm

open class PackageManager {
    class NameNotFoundException(name: String? = null) : Exception(name)

    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val FEATURE_PICTURE_IN_PICTURE = "android.software.picture_in_picture"
        @JvmField
        val INSTANCE = PackageManager()
    }

    open fun hasSystemFeature(name: String): Boolean = true

    open fun canRequestPackageInstalls(): Boolean = true

    open fun getPackageInfo(packageName: String, flags: Int = 0): PackageInfo {
        if (packageName == "com.lagradost.cloudstream3.prerelease") {
            throw NameNotFoundException(packageName)
        }
        return PackageInfo().apply { this.packageName = packageName }
    }
}

