package ravi.gaurav.dropboxuploader

import ravi.gaurav.dropboxuploader.utils.Utils
import java.io.File

object Constants {
    val propsFilename = Utils.getUsersHomeDir() + File.separator +
            ".aiUploader" + File.separator + "aiuploader.properties"
    val authFile = Utils.getUsersHomeDir() + File.separator +
            ".aiUploader" + File.separator + "auth.prop"
}