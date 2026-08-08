package android.net

import android.os.Parcel

/**
 * 仅供 JVM 单元测试使用的轻量 Uri 实现，避免调用 Android framework 的 Uri.parse。
 */
internal class TestUri(
    private val rawValue: String,
) : Uri() {
    override fun buildUpon(): Builder = throw UnsupportedOperationException("not used in JVM test")

    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getEncodedPath(): String? = rawValue
    override fun getEncodedQuery(): String? = null
    override fun getEncodedSchemeSpecificPart(): String = rawValue
    override fun getEncodedUserInfo(): String? = null
    override fun getFragment(): String? = null
    override fun getHost(): String? = null
    override fun getLastPathSegment(): String? = rawValue.substringAfterLast('/').ifEmpty { null }
    override fun getPath(): String = rawValue
    override fun getPathSegments(): List<String> = emptyList()
    override fun getPort(): Int = -1
    override fun getQuery(): String? = null
    override fun getScheme(): String? = "test"
    override fun getUserInfo(): String? = null
    override fun getSchemeSpecificPart(): String = rawValue
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun toString(): String = rawValue
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
}
