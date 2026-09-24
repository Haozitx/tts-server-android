package com.github.jing332.tts_server_android.lyric

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 空 ContentProvider，只用来在 App 进程启动时把悬浮歌词条挂上总线。
 * 这样不必改动 Application 的初始化代码。
 */
class LyricInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { LyricOverlay.init(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
