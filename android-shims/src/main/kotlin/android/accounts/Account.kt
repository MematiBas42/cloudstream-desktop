package android.accounts

class Account(val name: String, val type: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Account) return false
        return name == other.name && type == other.type
    }

    override fun hashCode(): Int = 31 * name.hashCode() + type.hashCode()

    override fun toString(): String = "Account{name=$name, type=$type}"
}
