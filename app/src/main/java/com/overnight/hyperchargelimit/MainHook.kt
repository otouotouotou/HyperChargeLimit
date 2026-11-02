package com.overnight.hyperchargelimit

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method

/**
 * HyperOS の「バッテリー保護」の充電上限%を変更する LSPosed モジュール。
 * Modern Xposed API (libxposed) 102 / Kotlin。
 *
 * 仕組み:
 *  スコープ先のセキュリティセンター (com.miui.securitycenter) のプロセス内で、
 *  Xiaomi 独自の充電制御サービス IMiCharge (miui.util.IMiCharge) の
 *  setMiChargePath("smart_chg", 値) 呼び出しを横取りする。
 *
 *  "smart_chg" の値は16進文字列で、ビット構成は
 *    上位16ビット: 上限% (20〜100)
 *    下位16ビット: モード (0x11 = 制限ON (共通保護), 0x10 = 制限OFF)
 *  例: 80% で制限ON → (80 shl 16) or 0x11 = 0x500011 → "0x500011"
 *
 *  システムが上限80%で書き込もうとしたら、上位ビットだけをモジュールアプリで
 *  設定した%に差し替えて流すので、実際に充電が止まる%が変わる。
 *  読み取り (getMiChargePath) も同じ差し替えをするので、システム側の表示や
 *  状態チェックにも新しい上限が返る。
 *
 * v1.3 追加: 再起動直後はシステム側の初期化 (80%) が先に効いてしまい、
 * セキュリティセンターが setMiChargePath を呼ぶまで書き換えが起きない
 * 問題への対策として、プロセス起動後と充電開始 (ACTION_POWER_CONNECTED)
 * のタイミングで、モジュール側から能動的に現在値を読み、制限ON のとき
 * だけ設定%で setMiChargePath を呼び直す。制限OFF のときは何もしない
 * (ユーザーが保護を切った状態を勝手にONにしないため)。
 *
 * 設定値の受け渡し:
 *  モジュールアプリが自前の保存領域 (ConfigStore) に保存した値を、
 *  フック側がモジュールの ConfigProvider 経由で読む (v1.3.2〜)。
 *  (v1.1〜v1.3.1 は Settings.System 経由だったが、その書き込みは
 *  targetSdk 31 以降の SettingsProvider の制限で通常アプリからは
 *  拒否されるため、実際には保存できていなかった — 初期値 90% の間は
 *  フック側デフォルトと一致して問題が見えなかった)
 *  読めない環境ではデフォルト 90% で動く。
 */
class MainHook : XposedModule() {

    /** 同一プロセスへの二重登録防止 */
    private var hooksInstalled = false

    @Volatile
    private var cachedResolver: ContentResolver? = null

    /** 能動適用 (v1.3) 用に保持する IMiCharge への参照 */
    @Volatile
    private var miChargeInstance: Any? = null
    private var miChargeSetMethod: Method? = null
    private var miChargeGetMethod: Method? = null

    @Volatile
    private var powerReceiverRegistered = false

    /**
     * set フックが最後に観測した smart_chg のモード (下位16ビット)。
     * -1 = 未観測。能動適用の可否は get の戻り値ではなくこれで判定する
     * (v1.3.1: getMiChargePath の戻り値は set と別形式で "16" のような
     * 値が返り、モード判定に使えなかったため)。
     */
    @Volatile
    private var lastSeenSmartChgMode: Int = -1

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        if (!param.isFirstPackage || hooksInstalled) return
        hooksInstalled = true
        installHooks(param.classLoader)
    }

    private fun installHooks(classLoader: ClassLoader) {
        try {
            // IMiCharge のシングルトン実装クラスを特定する。
            // インターフェース自体ではなく、実際に呼ばれる実装クラスの
            // メソッドをフックしないとすり抜けてしまうため。
            val iMiCharge = Class.forName("miui.util.IMiCharge", false, classLoader)
            val instance = obtainMiChargeInstance(iMiCharge)
                ?: throw IllegalStateException("could not obtain IMiCharge instance")
            val implClass = instance.javaClass

            val setMethod = findMethod(implClass, "setMiChargePath",
                String::class.java, String::class.java)
                ?: throw NoSuchMethodException("setMiChargePath not found on $implClass")
            val getMethod = findMethod(implClass, "getMiChargePath", String::class.java)

            hook(setMethod)
                .setId("hyperchargelimit.setMiChargePath")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val path = chain.getArg(0) as? String
                    val value = chain.getArg(1) as? String
                    if (path == SMART_CHG_PATH) {
                        parseHex(value)?.let { lastSeenSmartChgMode = it and 0xFFFF }
                    }
                    val rewritten = rewriteValue(path, value, "set")
                    if (rewritten != null) {
                        chain.proceed(arrayOf<Any>(path!!, rewritten))
                    } else {
                        chain.proceed()
                    }
                }

            if (getMethod != null) {
                hook(getMethod)
                    .setId("hyperchargelimit.getMiChargePath")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val path = chain.getArg(0) as? String
                        val result = chain.proceed()
                        val rewritten = rewriteValue(path, result as? String, "get")
                        rewritten ?: result
                    }
            }

            miChargeInstance = instance
            miChargeSetMethod = setMethod
            miChargeGetMethod = getMethod

            log(Log.INFO, TAG, "hooks installed on ${implClass.name} (get=${getMethod != null})")
            scheduleProactiveApply()
        } catch (t: Throwable) {
            // HyperOS 3 で IMiCharge の場所や形が変わっていたら、ここに痕跡が残る
            log(Log.ERROR, TAG, "failed to install hooks (IMiCharge not found?)", t)
        }
    }

    /**
     * プロセス起動直後に数回、能動適用を試みる。
     * 起動直後はセキュリティセンター側の初期化 (80%書き込み) と前後
     * する可能性があるため、間隔をあけて複数回呼ぶ。あわせて充電開始
     * ブロードキャストのレシーバ登録も試みる。
     */
    private fun scheduleProactiveApply() {
        try {
            val handler = Handler(Looper.getMainLooper())
            for (delay in longArrayOf(3_000L, 15_000L, 45_000L)) {
                handler.postDelayed({
                    registerPowerReceiverIfNeeded()
                    applyLimit("after_start_${delay}ms")
                }, delay)
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "scheduleProactiveApply failed", t)
        }
    }

    /** 充電開始 (ケーブル接続) のたびに能動適用するレシーバを登録する */
    private fun registerPowerReceiverIfNeeded() {
        if (powerReceiverRegistered) return
        val app = currentApplication() ?: return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    applyLimit("power_connected")
                }
            }
            app.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_POWER_CONNECTED),
                Context.RECEIVER_NOT_EXPORTED
            )
            powerReceiverRegistered = true
            log(Log.INFO, TAG, "power-connected receiver registered")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "registerReceiver failed", t)
        }
    }

    /**
     * set フックが最後に観測したモードが制限ON (0x11) のときだけ、
     * 設定%で setMiChargePath を呼び直す。呼び出す set は自前のフックを
     * 通るが、同じ%への書き換えは素通しされるので冪等。
     * モード未観測・制限OFF のときは何もしない (ユーザーが保護を切った
     * 状態や、状態が分からないときに勝手にONにしないため)。
     */
    private fun applyLimit(reason: String) {
        try {
            val instance = miChargeInstance ?: return
            val setMethod = miChargeSetMethod ?: return
            val mode = lastSeenSmartChgMode
            if (mode != MODE_COMMON_PROTECT) {
                log(Log.INFO, TAG, "apply skipped ($reason): last seen mode=$mode")
                return
            }
            val target = encode(currentLimit())
            setMethod.invoke(instance, SMART_CHG_PATH, target)
            log(Log.INFO, TAG, "applied smart_chg -> $target ($reason)")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "applyLimit failed ($reason)", t)
        }
    }

    private fun currentApplication(): Application? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getMethod("currentApplication").invoke(null) as? Application
    } catch (t: Throwable) {
        null
    }

    /**
     * IMiCharge のインスタンス取得。
     * 本命は IMiCharge.getInstance()、ダメなら ServiceManager 経由の
     * Binder ("mi_charge") から Stub.asInterface で取る方法も試す。
     */
    private fun obtainMiChargeInstance(iMiCharge: Class<*>): Any? {
        try {
            val instance = iMiCharge.getMethod("getInstance").invoke(null)
            if (instance != null) return instance
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "IMiCharge.getInstance() failed", t)
        }
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, "mi_charge") ?: return null
            val stub = Class.forName("miui.util.IMiCharge\$Stub", false, iMiCharge.classLoader)
            return stub.getMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "ServiceManager(mi_charge) route failed", t)
        }
        return null
    }

    /**
     * path が "smart_chg" でモードが制限ON (0x11) の値なら、上限%を
     * 設定値に差し替えた16進文字列を返す。それ以外は null (素通し)。
     */
    private fun rewriteValue(path: String?, value: String?, from: String): String? {
        if (path != SMART_CHG_PATH) return null
        val parsed = parseHex(value) ?: return null
        val mode = parsed and 0xFFFF
        if (mode != MODE_COMMON_PROTECT) return null
        val originalPercent = (parsed shr 16) and 0xFFFF
        val target = currentLimit()
        if (originalPercent == target) return null
        val rewritten = encode(target)
        log(Log.INFO, TAG, "$from smart_chg: $value (${originalPercent}%) -> $rewritten (${target}%)")
        return rewritten
    }

    /** 上限% の短時間キャッシュ (プロバイダ照会は IPC なので毎回は引かない) */
    @Volatile
    private var cachedPercent: Int = -1

    @Volatile
    private var cachedPercentAt: Long = 0L

    /** モジュールアプリが ConfigStore に保存した上限% (20〜100) */
    private fun currentLimit(): Int {
        val now = System.currentTimeMillis()
        val cached = cachedPercent
        if (cached >= 0 && now - cachedPercentAt < PERCENT_CACHE_MS) return cached
        val resolver = contentResolver() ?: return ConfigStore.DEFAULT_PERCENT
        val value = try {
            resolver.query(ConfigStore.uri(), null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else ConfigStore.DEFAULT_PERCENT
            } ?: ConfigStore.DEFAULT_PERCENT
        } catch (t: Throwable) {
            ConfigStore.DEFAULT_PERCENT
        }.coerceIn(ConfigStore.MIN_PERCENT, ConfigStore.MAX_PERCENT)
        cachedPercent = value
        cachedPercentAt = now
        return value
    }

    /**
     * フック先プロセスの ContentResolver を ActivityThread 経由で取る。
     * セキュリティセンターはアプリプロセスなので currentApplication が使える。
     */
    private fun contentResolver(): ContentResolver? {
        cachedResolver?.let { return it }
        val resolver = try {
            currentApplication()?.contentResolver
        } catch (t: Throwable) {
            null
        }
        if (resolver != null) cachedResolver = resolver
        return resolver
    }

    /** クラス階層とインターフェースをたどって宣言メソッドを探す */
    private fun findMethod(start: Class<*>, name: String, vararg params: Class<*>): Method? {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            try {
                return c.getDeclaredMethod(name, *params).apply { isAccessible = true }
            } catch (e: NoSuchMethodException) {
                // 次の候補へ
            }
            c.superclass?.let { queue.add(it) }
            queue.addAll(c.interfaces)
        }
        return null
    }

    companion object {
        private const val TAG = "HyperChargeLimit"
        private const val TARGET_PACKAGE = "com.miui.securitycenter"
        private const val SMART_CHG_PATH = "smart_chg"

        /** 下位16ビットのモード値: 制限ON (共通保護) */
        private const val MODE_COMMON_PROTECT = 0x11

        private const val PERCENT_CACHE_MS = 3000L

        private fun parseHex(value: String?): Int? {
            if (value == null) return null
            val s = value.trim().removePrefix("0x").removePrefix("0X")
            if (s.isEmpty()) return null
            return s.toIntOrNull(16)
        }

        private fun encode(percent: Int): String =
            "0x" + ((percent shl 16) or MODE_COMMON_PROTECT).toString(16)
    }
}
