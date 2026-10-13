package rikka.shizuku.server

import com.google.gson.annotations.SerializedName

class ShizukuConfig {

    @SerializedName("version")
    var version: Int = LATEST_VERSION

    @SerializedName("packages")
    var packages: MutableList<PackageEntry>? = ArrayList()

    @SerializedName("nightdog_enabled")
    var nightDogEnabled: Boolean = false

    class PackageEntry(
        @SerializedName("uid") val uid: Int,
        @SerializedName("flags") var flags: Int
    ) : ConfigPackageEntry() {

        @SerializedName("packages")
        var packages: MutableList<String>? = ArrayList()

        override fun isAllowed(): Boolean = (flags and ConfigManager.FLAG_ALLOWED) != 0

        override fun isDenied(): Boolean = (flags and ConfigManager.FLAG_DENIED) != 0
    }

    constructor()

    constructor(packages: MutableList<PackageEntry>) {
        version = LATEST_VERSION
        this.packages = packages
    }

    companion object {
        const val LATEST_VERSION = 2
    }
}
