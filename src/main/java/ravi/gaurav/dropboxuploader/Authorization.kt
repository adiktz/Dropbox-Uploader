package ravi.gaurav.dropboxuploader

import com.dropbox.core.DbxAppInfo
import com.dropbox.core.DbxAuthInfo
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.DbxWebAuth
import org.apache.commons.io.FileUtils
import java.io.*
import java.util.*
import kotlin.system.exitProcess

class Authorization {
    private val propFile = File(Constants.propsFilename)
    private val authFile = File(Constants.authFile)

    private fun checkForAuthFile(): DbxAuthInfo? {
        var dxbAuthInfo: DbxAuthInfo? = null
        try {
            dxbAuthInfo = DbxAuthInfo.Reader.readFromFile(authFile)
        } catch (e: Exception){
            println("Unable to load Auth file. Need re-auth")
        }
        return dxbAuthInfo
    }

    private fun getProperties(): Properties {
        if (!propFile.exists()) {
            println(propFile.path + " Does not exist. Need to create it")
            return storeProperties(propFile)
        } else {
            val properties = Properties()
            FileInputStream(propFile).use { fis ->
                properties.load(fis)
                properties["key"] ?: run {
                    return storeProperties(propFile)
                }
                properties["secret"] ?: run {
                    return storeProperties(propFile)
                }
            }
            return properties
        }
    }

    private fun storeProperties(propFile: File): Properties {
        if (propFile.exists().not()) FileUtils.touch(propFile)
        val properties = Properties()
        FileOutputStream(propFile).use { fos ->
            getKeysFromUser().entries.forEach { properties[it.key] = it.value }
            properties.store(fos, "")
        }
        return readProperties(propFile)
    }

    private fun getKeysFromUser(): Map<String, String?> {
        println("  Get an API app key by registering with Dropbox:");
        println("    https://dropbox.com/developers/apps");
        val map = LinkedHashMap<String, String?>()
        print("Please enter App Key :: ")
        val key = readLine()
        map["key"] = key
        print("Please Enter App Secret :: ")
        val secret = readLine()
        map["secret"] = secret
        return map
    }

    @Throws
    private fun readProperties(propFile: File): Properties {
        if (propFile.exists().not()) {
            throw RuntimeException("File nai hai bhai... kya kar raha hai... dhang se kar")
        }
        val properties = Properties()
        FileInputStream(propFile).use { fis ->
            properties.load(fis)
        }
        return properties
    }

    fun authorize(): DbxAuthInfo {
        //Auth exists. Return it
        checkForAuthFile()?.let { return it }

        val properties = getProperties()
        val appInfo = DbxAppInfo(properties["key"] as String, properties["secret"] as String)

        // Run through Dropbox API authorization process
        val requestConfig = DbxRequestConfig("aiUploader")
        val webAuth = DbxWebAuth(requestConfig, appInfo)
        val webAuthRequest = DbxWebAuth.newRequestBuilder()
                .withNoRedirect()
                .build()

        val authorizeUrl = webAuth.authorize(webAuthRequest)
        println("1. Go to $authorizeUrl")
        println("2. Click \"Allow\" (you might have to log in first).")
        println("3. Copy the authorization code.")
        print("Enter the authorization code here: ")

        var code: String = BufferedReader(InputStreamReader(System.`in`)).readLine()
                ?: exitProcess(1)
        code = code.trim { it <= ' ' }

        val authFinish = webAuth.finishFromCode(code)
        println("Authorization complete.");
        println("- User ID: " + authFinish.userId);
        println("- Account ID: " + authFinish.accountId);
        println("- Access Token: " + authFinish.accessToken);

        // Save auth information to output file.
        val authInfo = DbxAuthInfo(authFinish.accessToken, appInfo.host)
        val output = File(Constants.authFile)
        try {
            DbxAuthInfo.Writer.writeToFile(authInfo, output)
            println("Saved authorization information to \"" + output.canonicalPath.toString() + "\".")
        } catch (ex: IOException) {
            System.err.println("Error saving to <auth-file-out>: " + ex.message)
            System.err.println("Dumping to stderr instead:")
            DbxAuthInfo.Writer.writeToStream(authInfo, System.err)
            exitProcess(1)
        }
        return authInfo
    }
}