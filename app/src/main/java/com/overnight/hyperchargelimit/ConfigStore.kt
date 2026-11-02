package com.overnight.hyperchargelimit

import android.content.Context
import android.net.Uri

/**
 * 上限% の保存先 (v1.3.2〜)。
 *
 * v1.1〜v1.3.1 は Settings.System に書いていたが、targetSdk 31 以降の
 * SettingsProvider の制限で、通常アプリの uid からはカスタムキーを
 * 書き込めず "You cannot keep your settings in the secure settings."
 * になる (WRITE_SETTINGS を許可しても同じ)。実機では、初期値の 90% の
 * 間はフック側のデフォルト値と一致して問題が見えず、値を変えた瞬間に
 * 例外が飛んでアプリが落ちた。
 *
 * そのため設定はモジュール自身の SharedPreferences に保存し、
 * フック側プロセスからは ConfigProvider 経由で読む方式に変更した。
 * 書き込み許可 (WRITE_SETTINGS) は不要になった。
 *
 * 注意: この object は libxposed のクラスを継承しないこと。
 * 設定アプリ (通常プロセス) からもフックからも使う共有コードのため、
 * MainHook (XposedModule 継承) の中に置いてはいけない。
 */
object ConfigStore {
    const val AUTHORITY = "com.overnight.hyperchargelimit.config"
    private const val PREFS_NAME = "hcl_config"
    private const val KEY_PERCENT = "percent"

    const val DEFAULT_PERCENT = 90
    const val MIN_PERCENT = 20
    const val MAX_PERCENT = 100

    /** フック側プロセスが上限%を読むための URI */
    fun uri(): Uri = Uri.parse("content://$AUTHORITY/limit")

    fun getPercent(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PERCENT, DEFAULT_PERCENT)
            .coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun putPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PERCENT, percent.coerceIn(MIN_PERCENT, MAX_PERCENT))
            .apply()
    }
}
