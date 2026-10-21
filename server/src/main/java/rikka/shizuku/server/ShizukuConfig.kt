package rikka.shizuku.server

import com.google.gson.annotations.SerializedName

class ShizukuConfig {

    @SerializedName("version")
    var version: Int = LATEST_VERSION

    @SerializedName("packages")
    var packages: MutableList<PackageEntry>? = ArrayList()

    @SerializedName("nightDogEnabled")
    var nightDogEnabled: Boolean = false

    @SerializedName("accessPaused")
    var accessPaused: Boolean = false

    class PackageEntry(
        @SerializedName("uid") val uid: Int,
        @SerializedName("flags") var flags: Int
    ) : ConfigPackageEntry() {

        @SerializedName("packages")
        var packages: MutableList<String>? = ArrayList()

        /**
         * SHA-256 digests of the signing certificates of the package installation this decision
         * was recorded for. Backfilled automatically on load/attach if absent.
         */
        @SerializedName("signers")
        var signers: MutableList<String>? = null

        /**
         * Epoch timestamp (ms) when this UID last attached to the server.
         */
        @SerializedName("lastConnectedAt")
        var lastConnectedAt: Long = 0L

        override fun isAllowed(): Boolean = (flags and ConfigManager.FLAG_ALLOWED) != 0

        override fun isDenied(): Boolean = (flags and ConfigManager.FLAG_DENIED) != 0
    }

    constructor()

    constructor(packages: MutableList<PackageEntry>) {
        version = LATEST_VERSION
        this.packages = packages
    }

    companion object {
        const val LATEST_VERSION = 3
    }
}
