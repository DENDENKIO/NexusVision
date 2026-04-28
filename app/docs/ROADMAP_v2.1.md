# NEXUS Vision 開発ロードマップ v2.1

**最終更新: 2026-04-28**

---

## プロジェクト設計条件

```
アプリ名:          NEXUS Vision
パッケージ名:       com.nexus.vision
Minimum SDK:       API 31 (Android 12)
Target SDK:        35
言語:              Kotlin
Build DSL:         Kotlin DSL (build.gradle.kts)
Compose BOM:       2026.04.01
Kotlin:            2.2.21
AGP:               8.7.3
Java互換:          17
versionCode:       1
versionName:       "1.0.0-dev"
```

### ディレクトリ構成（最終形）

```
app/
├── docs/
│   ├── SPEC_v4.1.md          ← 仕様書
│   └── ROADMAP_v2.1.md       ← このロードマップ
├── src/main/
│   ├── java/com/nexus/vision/
│   │   ├── NexusApplication.kt
│   │   ├── MainActivity.kt
│   │   ├── engine/
│   │   │   ├── NexusEngineManager.kt
│   │   │   ├── EngineSocket.kt
│   │   │   └── ThermalMonitor.kt
│   │   ├── deor/
│   │   │   ├── EntropyCalculator.kt
│   │   │   ├── PHashCalculator.kt
│   │   │   ├── AdaptiveResizer.kt
│   │   │   └── FECSScorer.kt
│   │   ├── cache/
│   │   │   ├── L1PHashCache.kt
│   │   │   ├── L2InferenceCache.kt
│   │   │   └── CacheEntities.kt
│   │   ├── image/
│   │   │   ├── DirectCrop100MP.kt
│   │   │   ├── DocumentSharpener.kt
│   │   │   ├── EASSPipeline.kt
│   │   │   ├── ImageCorrector.kt
│   │   │   ├── RegionDecoder.kt
│   │   │   ├── RouteAProcessor.kt
│   │   │   ├── RouteBProcessor.kt
│   │   │   └── TileManager.kt
│   │   ├── ncnn/
│   │   │   ├── NcnnSuperResolution.kt
│   │   │   └── RealEsrganBridge.kt
│   │   ├── pipeline/
│   │   │   └── RouteCProcessor.kt
│   │   ├── parser/
│   │   │   ├── ExcelCsvParser.kt
│   │   │   ├── SourceCodeParser.kt
│   │   │   └── PdfExtractor.kt
│   │   ├── ocr/
│   │   │   ├── MlKitOcrEngine.kt
│   │   │   └── TableReconstructor.kt
│   │   ├── os/
│   │   │   ├── AppActionsHandler.kt
│   │   │   ├── ShareReceiver.kt
│   │   │   ├── ProcessTextActivity.kt
│   │   │   ├── QuickSettingsTile.kt
│   │   │   └── HudOverlay.kt
│   │   ├── worker/
│   │   │   ├── BatchEnhanceWorker.kt
│   │   │   └── BatchEnhanceQueue.kt
│   │   ├── memory/
│   │   │   └── LongTermMemory.kt
│   │   ├── sdk/
│   │   │   ├── NexusContentProvider.kt
│   │   │   └── IntentApiReceiver.kt
│   │   ├── widget/
│   │   │   └── ProgressWidget.kt
│   │   ├── notification/
│   │   │   ├── InlineReplyHandler.kt
│   │   │   └── BatchNotificationHelper.kt
│   │   ├── benchmark/
│   │   │   └── PerformanceTracker.kt
│   │   └── ui/
│   │       ├── MainScreen.kt
│   │       ├── MainViewModel.kt
│   │       ├── components/
│   │       │   ├── ChatBubble.kt
│   │       │   ├── ChatInput.kt
│   │       │   ├── CropSelector.kt
│   │       │   └── BenchmarkDashboard.kt
│   │       └── theme/
│   ├── jni/
│   │   ├── CMakeLists.txt
│   │   ├── realesrgan_simple.h / .cpp
│   │   ├── realesrgan_jni.cpp
│   │   ├── image_fusion.h / .cpp
│   │   ├── streaming_jpeg.h / .cpp
│   │   ├── libjpeg-turbo/
│   │   │   ├── jconfig.h
│   │   │   ├── jconfigint.h
│   │   │   ├── jversion.h
│   │   │   └── src/ (+ src/wrapper/)
│   │   └── ncnn-android-vulkan/
│   ├── res/
│   └── AndroidManifest.xml
├── build.gradle.kts
└── assets/models/
    ├── realesr-general-x4v3.param / .bin
    ├── realesr-animevideov3-x4.param / .bin
    ├── realesrgan-x4plus.param / .bin
    └── realesrgan-x4plus-anime.param / .bin
```

---

## 依存ライブラリ最終版

```
litertlm-android:0.10.2
androidx.compose:compose-bom:2026.04.01
androidx.activity:activity-compose:1.9.0
androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0
androidx.work:work-runtime-ktx:2.11.2
io.objectbox:objectbox-android:5.4.1
com.google.mlkit:text-recognition:16.0.1
com.google.mlkit:text-recognition-japanese:16.0.1
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0
org.apache.poi:poi-ooxml:5.5.1  (poi-android wrapper)
```

---

## フェーズ一覧

| Phase | 名称 | 期間 | ステップ数 | 主な成果物 | 状態 |
|---|---|---|---|---|---|
| 1 | プロジェクト基盤 | 2週 | 6 | 新規プロジェクト作成、Gradle構成、Manifest、Application クラス | ✅ 完了 |
| 2 | エンジン基盤 | 2週 | 6 | NexusEngineManager、EngineSocket、ThermalMonitor、フォアグラウンドサービス | ✅ 完了 |
| 3 | DEOR + キャッシュ | 2週 | 6 | EntropyCalculator、PHashCalculator、AdaptiveResizer、L1/L2 キャッシュ、ObjectBox | ✅ 完了 |
| 4 | 基本 UI + バッチ高画質化 | 2週 | 7 | Compose メイン画面、画像選択、単一推論、バッチキュー、WorkManager、進捗通知、サーマル連携 | ✅ 完了 |
| 5 | 100MP + ドキュメント鮮鋭化 | 2週 | 5 | DirectCrop100MP、DocumentSharpener、ML Kit OCR 統合 | ✅ 完了 |
| 6 | EASS + FECS | 3週 | 7 | FECSScorer、EASSPipeline、タイル分割・ルート振り分け・結合、閾値テスト | 🔧 FECSScorer のみ完了 |
| 7 | Real-ESRGAN + 画像補正 | 2週 | 5 | NCNN JNI ブリッジ、RealEsrganBridge、NLM デノイズ、明暗補正、ストリーミング JPEG | ✅ 完了 |
| 8 | ファイル解析 | 2週 | 4 | ExcelCsvParser、SourceCodeParser、PdfExtractor | ⬜ 未着手 |
| 9 | OCR + 表復元 | 1週 | 3 | MlKitOcrEngine、TableReconstructor、CSV/Markdown 出力 | ⬜ 未着手 |
| 10 | OS 統合 | 2週 | 6 | App Actions、Assist API、ACTION_PROCESS_TEXT、ACTION_SEND、クイック設定タイル、HUD | ⬜ 未着手 |
| 11 | 長期記憶 | 1週 | 3 | HNSW ベクトル DB、意味的検索、パーソナライズ基盤 | ⬜ 未着手 |
| 12 | ウィジェット + 通知 | 1週 | 3 | Jetpack Glance ウィジェット、通知インライン応答 | ⬜ 未着手 |
| 13 | SDK + API | 1週 | 3 | NexusContentProvider、IntentApiReceiver、署名検証 | ⬜ 未着手 |
| 14 | 将来最適化（実験） | 3週 | 5 | LoRA 推論適用、SwinIR 蒸留モデル差し替え、1 ステップ拡散 SR、NPU 自動選択 | ⬜ 未着手 |
| 15 | テスト + 最適化 | 2週 | 4 | E2E テスト、パフォーマンスプロファイリング、EASS 閾値グリッドサーチ、リリースビルド | ⬜ 未着手 |

**合計: 約 28 週（7 ヶ月）、68 ステップ**

---

## Phase 1 – プロジェクト基盤（2 週・6 ステップ） ✅ 完了

**Step 1-1** ✅: Android Studio で新規プロジェクト作成。Empty Compose Activity テンプレート。アプリ名 = NEXUS Vision、パッケージ名 = com.nexus.vision、Kotlin、API 31、Kotlin DSL。

**Step 1-2** ✅: `gradle/libs.versions.toml` を完全置換。`app/build.gradle.kts` を完全置換。全依存ライブラリを記述。`gradle.properties` に `android.useAndroidX=true` と `android.enableJetifier=true` を追記。Gradle Sync 確認。

**Step 1-3** ✅: `app/src/main/AndroidManifest.xml` を作成。全パーミッション、`largeHeap=true`、`uses-native-library`（GPU 系、required=false）、フォアグラウンドサービス宣言。

**Step 1-4** ✅: `NexusApplication.kt` 作成。ObjectBox 初期化スタブ。

**Step 1-5** ✅: `docs/` ディレクトリに `SPEC_v4.0.md` と `ROADMAP_v2.0.md` を配置。

**Step 1-6** ✅: `MainActivity.kt` + `ui/MainScreen.kt` + `ui/MainViewModel.kt` の最小構成。Compose で「NEXUS Vision」タイトルのみ表示。Build → Rebuild 成功を確認。

---

## Phase 2 – エンジン基盤（2 週・6 ステップ） ✅ 完了

**Step 2-1** ✅: `engine/EngineSocket.kt` – エンジンプラグインのインターフェース定義（initialize, infer, destroy）。

**Step 2-2** ✅: `engine/NexusEngineManager.kt` – シングルトン。Mutex 直列化、連続失敗カウンター（3 回で自動再初期化）、フェーズ A/B/C 管理、OOM 時アンロード（RAM < 500 MB 判定）。

**Step 2-3** ✅: `engine/ThermalMonitor.kt` – Android Thermal API ラッパー。6 段階監視、StateFlow でUI に公開。

**Step 2-4** ✅: フォアグラウンドサービス。`FOREGROUND_SERVICE_SPECIAL_USE` で宣言。エンジンのライフサイクル管理。

**Step 2-5** ✅: NexusEngineManager に Gemma-4 用の LiteRT-LM 接続を実装。Phase 0 の TestEngine のコードを基に、確定済み API シグネチャで書き直し。

**Step 2-6** ✅: 統合テスト。モデルファイルを adb push 後、エンジン初期化 → テキスト推論 → 破棄のサイクルを検証。

---

## Phase 3 – DEOR + キャッシュ（2 週・6 ステップ） ✅ 完了

**Step 3-1** ✅: `deor/EntropyCalculator.kt` – 128×128 グレースケール縮小、256 ビンヒストグラム、シャノンエントロピー計算。

**Step 3-2** ✅: `deor/PHashCalculator.kt` – 32×32 縮小、DCT、上位 8×8 係数、64-bit ハッシュ生成、ハミング距離計算。

**Step 3-3** ✅: `deor/AdaptiveResizer.kt` – エントロピー値に応じた 5 段階リサイズ（128〜768 px）。

**Step 3-4** ✅: `cache/CacheEntities.kt` + ObjectBox 初期化。L1PHashCache エンティティ。L2InferenceCache エンティティ。

**Step 3-5** ✅: `cache/L1PHashCache.kt` – 10,000 エントリ上限、ハミング距離 ≤ 5 で検索。`cache/L2InferenceCache.kt` – 50,000 エントリ、LFU + 時間減衰エビクション。

**Step 3-6** ✅: DEOR → キャッシュ照合 → 推論 → キャッシュ格納の一連フローを結合テスト。

---

## Phase 4 – 基本 UI + バッチ高画質化（2 週・7 ステップ） 🔧 進行中

**Step 4-1** ✅: `ui/MainScreen.kt` 拡張。画像選択ボタン（`ActivityResultContracts.PickVisualMedia`）、チャット UI、範囲選択（CropSelector）、結果表示。

**Step 4-2** ✅: `ui/MainViewModel.kt` 拡張。画像選択 → DEOR → キャッシュ照合 → 推論 → 結果表示の完全フロー。高画質化・ズーム・OCR の振り分け。ストリーミング JPEG 保存（saveBitmapToGalleryStreaming）も実装済み。

**Step 4-3** ✅: `worker/BatchEnhanceQueue.kt` 作成。キュー管理、StateFlow で進捗を UI に公開。

**Step 4-4** ✅: `worker/BatchEnhanceWorker.kt` 作成。WorkManager CoroutineWorker。直列処理 + フォアグラウンドサービス連携（Android 14+ クラッシュ対策済み）。

**Step 4-5** ✅: `notification/BatchNotificationHelper.kt` 作成。進捗・完了・発熱通知管理。

**Step 4-6** ✅: MainViewModel + MainScreen にバッチ UI を統合。複数画像選択、進捗メッセージ表示。

**Step 4-7** ✅: ThermalMonitor とバッチ処理の連携。熱レベルに応じた自動一時停止・再開・中断ロジック。バッチ用デコードサイズを 2048px に制限し、タイル数を削減して処理効率を最適化（1枚約4分程度）。

---

## Phase 5 – 100MP + ドキュメント鮮鋭化（2 週・5 ステップ） ✅ 完了

**Step 5-1** ✅: `image/DirectCrop100MP.kt` – BitmapRegionDecoder、inSampleSize 判定、ROI 矩形クロップ、短辺判定ロジック。

**Step 5-2** ✅: `image/DocumentSharpener.kt` – Sauvola 適応的二値化、アンシャープマスク、コントラスト比判定。

**Step 5-3** ✅: `ocr/MlKitOcrEngine.kt` – ML Kit Text Recognition v2 ラッパー。InputImage 生成、TextBlock/Line/Element パース。

**Step 5-4** ✅: ケース A（書類）とケース B（看板）の分岐ロジック統合。縮小 OCR → 未検出領域特定 → ダイレクトクロップ → 補正 → 再 OCR → マージ。

**Step 5-5** ✅: 実機テスト。S200 の 100MP カメラで撮影した書類・看板写真で OCR 精度検証。

---

## Phase 6 – EASS + FECS（3 週・7 ステップ） 🔧 部分完了

**Step 6-1** ✅: `deor/FECSScorer.kt` – タイル分割、2D DCT、3 帯域エネルギー比率算出、FECS スコア計算。

**Step 6-2**: `image/EASSPipeline.kt` – エントロピーマップ + FECS マップ生成、ルート A/B/C 分類ロジック。

**Step 6-3**: ルート A 実装 – バイキュービック補間。RouteAProcessor.kt は存在するが EASSPipeline からの呼び出し統合が必要。

**Step 6-4**: ルート B 実装 – Real-ESRGAN（RRDB）呼び出し。RouteBProcessor.kt は存在するが EASSPipeline からの呼び出し統合が必要。

**Step 6-5**: ルート C 実装 – 1 ステップ拡散モデル呼び出しスタブ（Phase 14 で実モデル接続）。現在は RouteCProcessor が Real-ESRGAN 全体処理を担当。

**Step 6-6**: タイル結合 + 8 px オーバーラップ線形ブレンディング。TileManager.kt は存在するが EASSPipeline との結合が必要。

**Step 6-7**: 合成テスト画像で EASS 全フロー検証。ルート振り分け比率、処理時間、結合画像の品質目視確認。

---

## Phase 7 – Real-ESRGAN + 画像補正（2 週・5 ステップ） 🔧 実機テスト中

**Step 7-1** ✅: NCNN Android SDK セットアップ。ncnn-android-vulkan を jni/ に配置。JNI ブリッジ C++ ファイル作成（realesrgan_simple.cpp、realesrgan_jni.cpp、image_fusion.cpp）。CMakeLists.txt。libjpeg-turbo 3.1.4.1 静的ライブラリ構成（jconfig.h、jconfigint.h、jversion.h + src/ 全ソース）。ストリーミング JPEG（streaming_jpeg.cpp/.h）。

**Step 7-2** ✅: `ncnn/RealEsrganBridge.kt` – JNI ラッパー。nativeInit、nativeProcess、nativeRelease、nativeIsLoaded、nativeFusionPipeline、nativeJpegBeginWrite/WriteRows/EndWrite。`ncnn/NcnnSuperResolution.kt` – 3-Stage Fusion Pipeline。`pipeline/RouteCProcessor.kt` – 高レベルラッパー。

**Step 7-3** ✅: `image/ImageCorrector.kt` – NLM デノイズ、線形ヒストグラムストレッチ、ガンマ補正。

**Step 7-4** ✅: MainViewModel から RouteCProcessor 経由で超解像を呼び出し、結果を saveBitmapToGallery（自動ストリーミング分岐）で保存する完全フロー実装済み。

**Step 7-5** ✅: 実機テスト。S200 で 4× 拡大の処理時間・品質検証。大画像ストリーミング JPEG 保存テスト。`memset` 未定義エラーや `jversion.h` 不足も修正済み。

---

## Phase 8 – ファイル解析（2 週・4 ステップ） ⬜ 未着手

**Step 8-1**: `parser/ExcelCsvParser.kt` – Apache POI で .xlsx 読み込み → Markdown 表 / JSON 変換。CSV は OpenCSV。

**Step 8-2**: `parser/SourceCodeParser.kt` – Kotlin / Python の正規表現パーサー。関数・クラス・インポート抽出。

**Step 8-3**: `parser/PdfExtractor.kt` – PdfRenderer でページ画像化 → ML Kit OCR → テキスト統合。

**Step 8-4**: パーサー結果を Gemma-4 に送信して要約・分析を取得するフロー結合テスト。

---

## Phase 9 – OCR + 表復元（1 週・3 ステップ） ⬜ 未着手

**Step 9-1**: `ocr/TableReconstructor.kt` – TextBlock の座標クラスタリング（Y で行、X で列）。

**Step 9-2**: CSV / Markdown 出力。ファイル保存とクリップボードコピー。

**Step 9-3**: S200 で表画像を撮影して復元精度テスト。

---

## Phase 10 – OS 統合（2 週・6 ステップ） ⬜ 未着手

**Step 10-1**: `res/xml/shortcuts.xml` + `os/AppActionsHandler.kt` – Gemini 音声コマンド対応。

**Step 10-2**: `os/ShareReceiver.kt` – ACTION_SEND 受信。画像・テキスト・ファイル振り分け。

**Step 10-3**: `os/ProcessTextActivity.kt` – ACTION_PROCESS_TEXT 受信。選択テキスト解析。

**Step 10-4**: `os/QuickSettingsTile.kt` – クイック設定タイル。タップでHUDトグル。

**Step 10-5**: `os/HudOverlay.kt` – SYSTEM_ALERT_WINDOW オーバーレイ。翻訳結果・解析結果のフローティング表示。

**Step 10-6**: 統合テスト。Gemini → App Actions → NEXUS 推論 → 結果返却の E2E フロー。

---

## Phase 11 – 長期記憶（1 週・3 ステップ） ⬜ 未着手

**Step 11-1**: `memory/LongTermMemory.kt` – ObjectBox HNSW ベクトルエンティティ。埋め込みベクトル格納。

**Step 11-2**: 意味的検索（コサイン類似度による近傍検索）。

**Step 11-3**: パーソナライズ基盤。頻出カテゴリ学習、優先表示ロジック。

---

## Phase 12 – ウィジェット + 通知（1 週・3 ステップ） ⬜ 未着手

**Step 12-1**: `widget/ProgressWidget.kt` – Jetpack Glance。バッチ高画質化の進捗表示。

**Step 12-2**: `notification/InlineReplyHandler.kt` – RemoteInput で通知から Gemma-4 に質問。

**Step 12-3**: タスク完了通知発行 + ウィジェット消去の連携テスト。

---

## Phase 13 – SDK + API（1 週・3 ステップ） ⬜ 未着手

**Step 13-1**: `sdk/NexusContentProvider.kt` – キャッシュ・履歴の共有 ContentProvider。

**Step 13-2**: `sdk/IntentApiReceiver.kt` – 署名検証付き Intent API。classify, enhance, ask, analyze。

**Step 13-3**: 外部アプリからの呼び出しテスト。

---

## Phase 14 – 将来最適化・実験（3 週・5 ステップ） ⬜ 未着手

**Step 14-1**: LoRA 推論適用。蒸留済み RRDB に LoRA ウェイト加算ロジック実装。テキスト鮮明化 / 夜景 / ポートレート LoRA ファイル準備。

**Step 14-2**: SwinIR 蒸留モデル差し替え。PC で蒸留した RRDB モデルを NCNN フォーマットに変換し、RealEsrganBridge のモデルパスを差し替え。品質比較テスト。

**Step 14-3**: 1 ステップ拡散 SR。Edge-SD-SR 方式モデルの NCNN 変換。EASS ルート C への実接続。

**Step 14-4**: NPU 自動選択。SoC 判定ロジック。LiteRT NeuroPilot Accelerator 対応 SoC リスト照合。GPU / NPU フォールバック。

**Step 14-5**: 全実験結果の評価。PSNR / LPIPS / 処理時間で最適構成を決定。

---

## Phase 15 – テスト + 最適化（2 週・4 ステップ） ⬜ 未着手

**Step 15-1**: E2E テスト。全パイプライン（画像選択 → DEOR → EASS → OCR → 結果表示）の通しテスト。

**Step 15-2**: パフォーマンスプロファイリング。Android Profiler でメモリ・CPU・GPU 使用率計測。ボトルネック特定。

**Step 15-3**: EASS 閾値グリッドサーチ。100 枚テスト画像で F の閾値（1.5, 4.0）を 0.5 刻みで探索。PSNR / LPIPS / 処理時間の 3 指標で最適値決定。

**Step 15-4**: リリースビルド作成。ProGuard / R8 設定。APK サイズ最適化。署名。

---

## マイルストーン

| 週 | マイルストーン | 実績 |
|---|---|---|
| 2 | Phase 1 完了：プロジェクトビルド成功 | ✅ 完了 |
| 6 | Phase 2-3 完了：エンジン + DEOR + キャッシュ動作 | ✅ 完了 |
| 10 | Phase 4-5 完了：基本 UI + 100MP + OCR 動作 | ✅ 完了 |
| 13 | Phase 6 完了：EASS + FECS 初回動作 | 🔧 EASSPipeline 未作成 |
| 15 | Phase 7 完了：Real-ESRGAN 統合 | ✅ 完了 |
| 18 | Phase 8-9 完了：ファイル解析 + 表復元 | ⬜ 未着手 |
| 20 | Phase 10 完了：OS 統合 | ⬜ 未着手 |
| 22 | Phase 11-13 完了：記憶 + ウィジェット + SDK | ⬜ 未着手 |
| 25 | Phase 14 完了：実験的最適化 | ⬜ 未着手 |
| 28 | Phase 15 完了：リリース候補 | ⬜ 未着手 |

---

## 次のアクション（Phase 6 Step 6-2 から開始）

コード作成 AI に渡す順序:

1. `image/EASSPipeline.kt` — カテゴリ分けロジックの実装（Step 6-2）
2. RouteA/B/C の統合呼び出し（Step 6-3, 6-4, 6-5）
3. TileManager との結合（Step 6-6）

---
