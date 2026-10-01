package id.my.sir.mpdclock

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Provider minimal pengganti FileProvider (proyek ini tanpa androidx):
 * menyajikan file APK hasil download ke installer sistem via content:// URI.
 * Tidak diekspor; akses hanya lewat grant URI saat install.
 */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        // Hanya nama file .apk, cegah path traversal
        val name = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.endsWith(".apk") }
            ?: return null
        val file = File(context!!.getExternalFilesDir(null), "updates/$name")
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
