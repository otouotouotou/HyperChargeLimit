package com.overnight.hyperchargelimit

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 充電上限%を設定する画面。
 *
 * 設定値はモジュール内蔵の保存領域 (ConfigStore) に保存する。
 * フック側 (セキュリティセンターのプロセス) は ConfigProvider 経由で
 * 読み取って反映する。読めない場合はフック側はデフォルト 90% で動作する。
 *
 * (v1.0 で使った libxposed service ライブラリは compileSdk 37 以上を
 *  要求してきたため v1.1 で Settings.System 方式に切り替えたが、
 *  その書き込みは targetSdk 31 以降の SettingsProvider の制限で
 *  通常アプリからは拒否されることが判明したため、v1.3.2 で
 *  ContentProvider 方式に切り替えた)
 *
 * v1.3.3: UI がシステムのライト/ダークに追従する
 * (背景・文字・スライダーの色を切り替え、ダーク時は AMOLED 向け真っ黒)
 */
class MainActivity : Activity() {

    private lateinit var percentLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // システムのダークモードに追従する (ダーク時は AMOLED 向け真っ黒)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val colorBg = if (night) Color.BLACK else Color.WHITE
        val colorText =
            if (night) Color.WHITE else Color.rgb(0x21, 0x21, 0x21)
        val colorSub =
            if (night) Color.rgb(0xB0, 0xB0, 0xB0) else Color.rgb(0x66, 0x66, 0x66)
        val colorAccent =
            if (night) Color.rgb(0x66, 0xBB, 0x6A) else Color.rgb(0x2E, 0x7D, 0x32)
        val colorTrack =
            if (night) Color.rgb(0x42, 0x42, 0x42) else Color.rgb(0xBD, 0xBD, 0xBD)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(BASE_PADDING, BASE_PADDING, BASE_PADDING, BASE_PADDING)
            setBackgroundColor(colorBg)
        }

        val title = TextView(this).apply {
            text = "HyperChargeLimit"
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(colorText)
        }
        root.addView(title)

        percentLabel = TextView(this).apply {
            text = "${ConfigStore.DEFAULT_PERCENT}%"
            textSize = 48f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(colorText)
        }
        root.addView(percentLabel)

        seekBar = SeekBar(this).apply {
            max = ConfigStore.MAX_PERCENT - ConfigStore.MIN_PERCENT
            progress = ConfigStore.DEFAULT_PERCENT - ConfigStore.MIN_PERCENT
            progressTintList = ColorStateList.valueOf(colorAccent)
            thumbTintList = ColorStateList.valueOf(colorAccent)
            progressBackgroundTintList = ColorStateList.valueOf(colorTrack)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    percentLabel.text = "${progress + ConfigStore.MIN_PERCENT}%"
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    savePercent((bar?.progress ?: 0) + ConfigStore.MIN_PERCENT)
                }
            })
        }
        root.addView(seekBar)

        statusLabel = TextView(this).apply {
            textSize = 14f
            text = "スライダーを離すと保存されます"
            setTextColor(colorSub)
        }
        root.addView(statusLabel)

        val desc = TextView(this).apply {
            textSize = 15f
            setTextColor(colorText)
            text = "\n使い方:\n" +
                "1. LSPosed マネージャーでこのモジュールを有効化し、" +
                "スコープで「セキュリティセンター」を選ぶ\n" +
                "2. 端末を再起動 (またはセキュリティセンターを強制停止)\n" +
                "3. 設定 → バッテリー → バッテリー保護 を ON にする\n" +
                "4. この画面のスライダーで上限%を決める\n\n" +
                "HyperOS 純正の 80% 固定リミットを、ここで決めた値に差し替えます。" +
                "値を変えたら、バッテリー保護を OFF→ON し直すとすぐ反映されます。" +
                "(スライダーを離した時点で保存されます)"
        }
        root.addView(desc)

        setContentView(root)

        // Android 15 (targetSdk 35) の edge-to-edge 対応
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(
                    BASE_PADDING + bars.left,
                    BASE_PADDING + bars.top,
                    BASE_PADDING + bars.right,
                    BASE_PADDING + bars.bottom
                )
                insets
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 保存済みの値をスライダーに反映する
        val saved = ConfigStore.getPercent(this)
        seekBar.progress = saved - ConfigStore.MIN_PERCENT
        percentLabel.text = "$saved%"
    }

    private fun savePercent(percent: Int) {
        try {
            ConfigStore.putPercent(this, percent)
            statusLabel.text = "保存しました: 上限 ${percent}%"
        } catch (e: Exception) {
            // 保存の失敗でアプリを落とさない (v1.3.1 までの教訓)
            statusLabel.text = "保存に失敗しました: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    companion object {
        private const val BASE_PADDING = 48
    }
}
