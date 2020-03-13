package ravi.gaurav.dropboxuploader

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val filePath: String?
    var dropboxPath: String? = null
    var fileName: String? = null
    if (args.isEmpty()) {
        println("Usage: COMMAND <file-to-be-uploaded> [dropbox-folder-path]")
        exitProcess(1)
    }

    filePath = args[0]

    if (args.size >= 2) dropboxPath = args[1]
    if (args.size >= 3) fileName = args[2]
    Uploader(filePath, dropboxPath, fileName).upload()
}

