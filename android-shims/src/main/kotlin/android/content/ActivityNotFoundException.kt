package android.content

open class ActivityNotFoundException : RuntimeException {
    constructor() : super()
    constructor(name: String?) : super(name)
}
