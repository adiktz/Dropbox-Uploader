package ravi.gaurav.dropboxuploader.utils

object Utils {
    fun getOSInfo() {
        val os = System.getProperty("os.name")
        val osbitVersion = System.getProperty("os.arch")
        val jvmbitVersion = System.getProperty("sun.arch.data.model")
        println("$os : $osbitVersion : $jvmbitVersion")
    }

    fun getUsersHomeDir() = System.getProperty("user.home").replace("\\", "/") // to support all platforms.

    fun getUsersDir() = System.getProperty("user.dir").replace("\\", "/")
}