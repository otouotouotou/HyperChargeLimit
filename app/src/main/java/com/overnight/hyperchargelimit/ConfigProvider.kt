package com.overnight.hyperchargelimit

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * フック側プロセス (セキュリティセンターのプロセス) から ConfigStore
 * の上限%を読むための読み取り専用プロバイダ。
 *
 * URI: content://com.overnight.hyperchargelimit.config/limit
 * 戻り値: "value" 列だけを持つ 1 行のカーソル (未設定はデフォルト 90)。
 *
 * 書き込みはモジュールアプリ内から ConfigStore 経由で行うため、
 * insert / update / delete は何もしない。
 * 値 (20〜100 の数値) は機密ではないため exported で公開する。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        if (uri.pathSegments != listOf("limit")) return null
        val value = ConfigStore.getPercent(ctx)
        return MatrixCursor(arrayOf("value")).apply {
            addRow(arrayOf<Any>(value))
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
