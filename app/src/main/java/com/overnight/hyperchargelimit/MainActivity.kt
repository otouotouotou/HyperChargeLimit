package com.overnight.hyperchargelimit

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 充電上限%を設定する画面。
 *
 * 設定値は Settings.System (キー "hyper_charge_limit_percent") に保存する。
 * 書き込みには「システム設定を変更できるアプリ」の許可が要るため、
 * 初回は許可画面へ誘導するボタンを出す。
 * フック側 (セキュリティセンターのプロセス) は同じキーを読み取って反映する。
 * キーが無い / 読めない場合はフック側はデフォルト 90% で動作する。
 *
 * (v1.0 で使った libxposed service ライブラリは compileSdk 37 以上を
 *  要求してきたため、v1.1 でこの方式に切り替えた)
 */
class MainActivity : Activity() {

    private lateinit var percentLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var statusLabel: TextView
    private lateinit var grantButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(BASE_PADDING, BASE_PADDING, BASE_PADDING, BASE_PADDING)
        }

        val title = TextView(this).apply {
            text = "HyperChargeLimit"
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        root.addView(title)

        percentLabel = TextView(this).apply {
            text = "${DEFAULT_PERCENT}%"
            textSize = 48f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        root.addView(percentLabel)

        seekBar = SeekBar(this).apply {
            max = MAX_PERCENT - MIN_PERCENT
            progress = DEFAULT_PERCENT - MIN_PERCENT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    percentLabel.text = "${progress + MIN_PERCENT}%"
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    savePercent((bar?.progress ?: 0) + MIN_PERCENT)
                }
            })
        }
        root.addView(seekBar)

        grantButton = Button(this).apply {
            text = "システム設定の変更を許可する"
            setOnClickListener { openWriteSettingsPermission() }
        }
        root.addView(grantButton)

        statusLabel = TextView(this).apply {
            textSize = 14f
        }
        root.addView(statusLabel)

        val desc = TextView(this).apply {
            textSize = 15f
            text = "\n使い方:\n" +
                "1. 上のボタンから「システム設定の変更」を許可する (初回のみ)\n" +
                "2. LSPosed マネージャーでこのモジュールを有効化し、" +
                "スコープで「セキュリティセンター」を選ぶ\n" +
                "3. 端末を再起動 (またはセキュリティセンターを強制停止)\n" +
                "4. 設定 → バッテリー → バッテリー保護 を ON にする\n" +
                "5. この画面のスライダーで上限%を決める\n\n" +
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
        refreshPermissionState()
        // 保存済みの値があればスライダーに反映する
        val saved = Settings.System.getInt(contentResolver, SETTINGS_KEY, DEFAULT_PERCENT)
            .coerceIn(MIN_PERCENT, MAX_PERCENT)
        seekBar.progress = saved - MIN_PERCENT
        percentLabel.text = "$saved%"
    }

    private fun refreshPermissionState() {
        val canWrite = Settings.System.canWrite(this)
        grantButton.visibility = if (canWrite) View.GONE else View.VISIBLE
        statusLabel.text = if (canWrite) {
            "許可OK: スライダーを離すと保存されます"
        } else {
            "未許可のため保存できません (フック側はデフォルト90%で動作します)"
        }
    }

    private fun openWriteSettingsPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun savePercent(percent: Int) {
        if (!Settings.System.canWrite(this)) {
            statusLabel.text = "未許可のため保存できません。上のボタンから許可してください"
            return
        }
        val ok = Settings.System.putInt(contentResolver, SETTINGS_KEY, percent)
        statusLabel.text = if (ok) {
            "保存しました: 上限 ${percent}%"
        } else {
            "保存に失敗しました"
        }
    }

    companion object {
        private const val BASE_PADDING = 48
        private const val SETTINGS_KEY = "hyper_charge_limit_percent"
        private const val DEFAULT_PERCENT = 90
        private const val MIN_PERCENT = 20
        private const val MAX_PERCENT = 100
    }
}
