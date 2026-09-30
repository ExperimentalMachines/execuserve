package org.experimentalmachines.execuserve.catalog

import java.io.File

/** [FileSystemView] over `java.io.File`, for Android and the desktop JVM alike. */
object JavaFileSystem : FileSystemView {
    override fun list(directory: String): List<String> = File(directory).list()?.toList().orEmpty()
    override fun isDirectory(path: String): Boolean = File(path).isDirectory
    override fun isFile(path: String): Boolean = File(path).isFile
    override fun size(path: String): Long = File(path).length()
    override fun modifiedMs(path: String): Long = File(path).lastModified()
    override fun readText(path: String): String = File(path).readText()
}
