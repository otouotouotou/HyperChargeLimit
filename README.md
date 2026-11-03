<img width="432" height="432" alt="HCLアイコン" src="https://github.com/user-attachments/assets/dc713158-4e0f-406d-80d0-cc5117a28e2d" />

# HyperChargeLimit

HyperOS の「バッテリー保護」が固定している充電上限 (80%) を、
好きな値 (20〜100%、スライダーで設定) に変更する LSPosed モジュール。
Kotlin + Modern Xposed API (libxposed) 102 で実装。

## v1.3.9 の変更点
- v1.3.3 をベースに、アイコンだけを変更。初期と同じベクター
  (VectorDrawable) 形式のまま、電池+稲妻のイラストに似せて描き直した
  (背景は白)。機能は v1.3.3 から変更なし

## v1.3.3 の変更点
- 設定画面がシステムのライト/ダークモードに追従するようにした
  (背景・文字・スライダーの色が切り替わる。ダーク時は AMOLED 向けに真っ黒)

## v1.3.2 の変更点
- 設定の保存が実際にはできていなかった問題を修正 (アプリがクラッシュする
  場合もあった)。Settings.System への書き込みは Android の制限で通常
  アプリからは拒否されるため、保存方式をモジュール内蔵の保存領域 +
  ContentProvider に変更。「システム設定の変更」の許可も不要になった

## v1.3.1 の変更点
- 能動適用の可否判定を、getMiChargePath の戻り値ベースから
  「set フックが最後に観測したモード」ベースに変更。
  実機ログで get の戻り値が set と別形式 ("16") だと判明し、
  v1.3 では能動適用が常にスキップされていたため

## ライセンス・出典
- 本プロジェクトは Apache License 2.0 の下で公開しています
  (全文は同梱の LICENSE ファイルを参照)
- 本モジュールのコードは独自実装。充電制御の値エンコード
  (上限% を上位ビットに格納する方式) は、
  HyperSmartCharge で知られた手法を参考にした
- アイコンは Pngtree のフリー素材イラストを基に、ベクター形式で描き直したもの
- 
  (https://pngtree.com/freepng/full-battery-charging-icon-with-lightning-bolt-power-symbol_24345457.html)
- 依存ライブラリ: libxposed API (Apache License 2.0) を
  compileOnly で使用

前提:
- 端末に「バッテリー保護」(充電上限) 機能があること
  (設定 → バッテリー → バッテリー保護 が表示される機種)
- API 102 対応の LSPosed フレームワーク

動作確認　Xiaomi 17Ultra HyperOS3

## 使い方

1. APK をインストール
2. LSPosed マネージャーでモジュールを有効化し、
   スコープで **セキュリティセンター** (`com.miui.securitycenter`) を選ぶ
   (scope.list で推奨スコープとして宣言済み)
3. 端末を再起動 (またはセキュリティセンターを強制停止して開き直す)
4. モジュールアプリ「HyperChargeLimit」を開き、スライダーで上限%を決める
   (スライダーを離した時点で保存される)
5. 設定 → バッテリー → バッテリー保護 を ON にする
   (既に ON の場合は OFF→ON し直すとすぐ反映される)
