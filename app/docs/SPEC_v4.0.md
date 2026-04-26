

以下、NEXUS Vision 仕様書 v4.0 です。これまでの全議論・エラー修正・新手法（EASS + FECS）を反映した完全版です。

---

# NEXUS Vision 仕様書 v4.0

**最終更新: 2026-04-26**
**ステータス: Phase 0 完了 → Phase 1 開発準備中**

---

## 第1章 プロジェクト概要

### 1.1 アプリ名

NEXUS Vision

### 1.2 設計思想

NEXUS Vision は「黒子のAI」として設計されます。独自のUIを前面に出すのではなく、Android の既存メカニズム（共有メニュー、通知、クイック設定タイル、ウィジェット、Gemini 呼び出し）に NEXUS の推論能力を注入する形で機能します。完全オフラインで動作し、プライバシーを100%確保します。ネット未接続・機内モード・プライバシー重視の場面で Gemini では対応できない処理を担います。電力効率を最優先とし、モデルは必要な瞬間にのみロードし、待機時の消費はゼロです。

### 1.3 対象端末

主要ターゲット: DOOGEE S200（Dimensity 7050、12GB RAM、100MP カメラ、10,100mAh、IP69K）。最低動作要件: Android 12+（API 31）、ARM64、6GB RAM。

### 1.4 中核方針

完全オフライン AI ハブとしてデバイス内で画像・テキスト・コードを処理します。推論の無駄を減らすためシャノンエントロピーと pHash を活用し、不要な計算をスキップします。他アプリと連携可能な高汎用性を目指し、Gemini やOS標準アシスタント、共有メニューと壁を越えて動作します。

---

## 第2章 エンジン構成

### 2.1 ソケットアーキテクチャ

AIモデルをプラグイン化し、タスク種別に応じてエンジンをホットスワップします。オンデマンドロードで、ユーザーが機能を起動した瞬間にのみモデルをロードし、待機時の電力はゼロです。

### 2.2 フェーズ別リソース管理（6GB RAM 対応）

フェーズ A で Gemma-4 をロードし推論（テキスト分類・JSON 計画生成）を実行します。フェーズ B で Gemma-4 をアンロードし約 1.5 GB を解放します。フェーズ C で NCNN（Real-ESRGAN 等）または拡散モデルをロードして画像処理を実行します。12GB RAM の S200 では A と C の一部を並列化可能ですが、6GB 端末では厳密に直列実行します。

### 2.3 サーマル・アダプティブ・スイッチング

Android Thermal API で 6 段階を監視します。NONE〜LIGHT は通常動作、MODERATE で 2 秒クールダウン挿入、SEVERE で高負荷マルチモーダル推論を停止しテキスト専用にダウングレードまたは 5 秒クールダウン、CRITICAL で処理中断＋15 秒クールダウン、EMERGENCY で即時中断します。

### 2.4 エントロピー駆動モデル切替

シャノンエントロピー H(X) = −Σ P(xᵢ) log₂ P(xᵢ) に基づき、情報量が低いデータは軽量フィルターだけで処理し、重いモデルロードをスキップします。

### 2.5 NPU 段階的対応

現行（S200 / Dimensity 7050 / NPU 550）では GPU Vulkan（Mali-G68 MC4）を主推論バックエンドとします。LiteRT NeuroPilot Accelerator の公式サポート SoC リスト（Dimensity 7300, 8300, 9000, 9200, 9300, 9400, 9500）に Dimensity 7050 は含まれていないため、NPU は使用しません。将来対応（Dimensity 7300+ デバイス）では、エンジン初期化時に SoC を判定し、サポート対象なら NPU バックエンド、非対象なら GPU Vulkan にフォールバックする自動選択ロジックを実装します。NPU 加速時は GPU 比で 1/2〜1/3 の高速化が見込まれます。

### 2.6 StableNexusEngine

Mutex 直列化、連続失敗カウンター（3 回で自動再初期化）、OOM 時は RAM 500 MB 未満でエンジンアンロードを行う安定化ラッパーです。

---

## 第3章 依存関係とビルド構成

### 3.1 Gradle / 依存

AGP 8.7.3、Kotlin 2.2.21、Compose BOM 2026.04.01（最新安定版）。

必須ライブラリ: `litertlm-android:0.10.2`（Gemma-4 推論）、`androidx.compose.material3`（UI）、`androidx.activity:activity-compose:1.9.0`、`objectbox-android:5.4.1`（キャッシュ DB + HNSW ベクトル検索）、`work-runtime-ktx:2.10.0`（バッチ処理）、`com.google.mlkit:text-recognition-japanese:16.0.1`（OCR バンドル版）。

### 3.2 LiteRT-LM API 確定シグネチャ（Phase 0 検証済み）

`EngineConfig(modelPath: String, backend: Backend, visionBackend: Backend?, cacheDir: String?)`。`Engine(engineConfig)` + `engine.initialize()` で初期化。`SamplerConfig(temperature: Double, topK: Int, topP: Double)`。`ConversationConfig(samplerConfig: SamplerConfig)`。`sendMessage` は 3 オーバーロード: `sendMessage(String)` でテキスト、`sendMessage(Contents)` でマルチモーダル、`sendMessage(Message)` で構造化入力。`Content.ImageFile(String)` と `Contents.of(vararg Content)` でマルチモーダル入力を構成。レスポンスは `Message` オブジェクトで `.toString()` でテキスト取得。

### 3.3 Phase 0 ビルドエラー修正記録

| エラー | 原因 | 対処 |
|---|---|---|
| `:app:checkDebugAarMetadata` 失敗 | `android.useAndroidX=true` 未設定 | `gradle.properties` に追記 |
| Kotlin metadata 2.2.0 / 2.3.0 不一致 | Kotlin 2.0.21 が litertlm 0.10.2 の要求（2.2+）を満たさない | `libs.versions.toml` の kotlin を 2.2.21 に更新 |
| `EngineConfig.builder()` unresolved | API v0.10.2 でビルダーパターン廃止 | コンストラクタ直接呼び出しに変更 |
| `SamplerConfig` Float/Double 型不一致 | temperature / topP が Double 型 | Float → Double リテラルに修正、`f` サフィックス削除 |
| `response.text` unresolved | Message オブジェクトに `.text` プロパティなし | `.toString()` に変更 |
| `Content.ImageFile(File)` 型不一致 | ImageFile は String パスを受け取る | `File` → `String` に変更 |

### 3.4 AndroidManifest.xml 要件

`android:largeHeap="true"`（Gemma-4 が約 1.5 GB 確保）。`FOREGROUND_SERVICE_SPECIAL_USE` パーミッション。`uses-native-library` で `libOpenCL.so`, `libcdsprpc.so`, `libvndksupport.so` を `android:required="false"` で宣言し、GPU 非対応端末でもクラッシュしません。

---

## 第4章 DEOR（Dynamic Entropy-Optimized Resize）

### 4.1 エントロピー計算

128×128 グレースケールに縮小し、256 ビンのヒストグラムからシャノンエントロピーを計算します（< 1 ms）。

### 4.2 適応リサイズ

エントロピー H に応じたターゲット長辺: H < 2.5 → 128 px、2.5〜4.0 → 256 px、4.0〜5.5 → 384 px、5.5〜7.0 → 512 px、H ≥ 7.0 → 768 px。Phase 0 実測: 128 px = 20,042 ms、384 px = 23,255 ms、768 px = 31,597 ms（解像度差 57%）。

### 4.3 2層キャッシュ

L1: pHash（64-bit DCT、ハミング距離 ≤ 5、< 2 ms）→ 10,000 エントリ、照合 < 0.1 ms。L2: ObjectBox 推論結果キャッシュ（50,000 エントリ、LFU + 時間減衰エビクション）。将来の L1.5: ObjectBox HNSW ベクトル埋め込みキャッシュ。

---

## 第5章 画像処理

### 5.1 Gemma-4 マルチモーダル分類

Gemma-4-E2B（INT4、約 2.58 GB）で画像分類。テキスト推論 約 1,969 ms、画像推論 20,000〜31,600 ms（解像度依存）。

### 5.2 100MP ダイレクトクロップ

`BitmapRegionDecoder` で 100MP JPEG から必要な矩形領域のみをメモリに読み込みます。ROI 検出 → 10% パディング → フル解像度クロップ（200〜500 ms）→ 短辺 1024 px 以上なら Real-ESRGAN スキップ → アンシャープマスク（α=0.5、< 10 ms）。処理合計 300〜600 ms（Real-ESRGAN 使用時の 5〜17 秒から 10〜30 倍高速化）。

### 5.3 ドキュメント鮮鋭化パイプライン

ケース A（書類・ホワイトボード）: `inSampleSize=2`（25MP 相当）で読み込み → Sauvola 適応的二値化（200〜400 ms）→ アンシャープマスク（50〜100 ms）→ ML Kit OCR（500 ms）。合計約 1.1 秒。ケース B（看板・遠距離小文字）: 縮小 OCR で大文字検出 → Canny エッジで未検出領域特定 → `BitmapRegionDecoder` フル解像度クロップ → 補正 → ML Kit 再実行 → 結果マージ。合計約 1.0 秒。コントラスト比 > 0.4 で二値化スキップの自動判定あり。短辺 < 512 px のみ Real-ESRGAN 2× フォールバック。

### 5.4 表データ OCR

ML Kit Text Recognition v2 でテキストとバウンディングボックス座標を取得し、Y 座標クラスタリングで行、X 座標クラスタリングで列を復元、CSV / Markdown テーブルに変換します。NEXUS Sheets（別アプリ）に渡してスプレッドシートエディタで編集・CSV/XLSX エクスポートが可能です。

---

## 第6章 超解像パイプライン

### 6.1 Real-ESRGAN x4plus（現行ベースライン）

NCNN Vulkan バックエンド。GPU で 3〜5 秒、CPU で 8〜15 秒。モデルサイズ約 64 MB。

### 6.2 NLM デノイズ

Non-Local Means。512×512 で約 500 ms。ノイズが多い場合のみ DEOR エントロピー判定で適用。

### 6.3 スマートデジタルズーム

ROI 検出 → 10% パディング → クロップ → 100MP ダイレクトクロップ優先 → 不足時のみ ESRGAN 4× → アンシャープマスク α=0.5。

### 6.4 明暗補正

線形ヒストグラムストレッチ（< 10 ms）。低光量時ガンマ補正 0.4〜0.6（< 5 ms）。

---

## 第7章 EASS + FECS（新規・NEXUS Vision オリジナル）

### 7.1 EASS（Entropy-Adaptive Selective Super-Resolution）

画像をタイル（128×128 px）に分割し、タイルごとに処理強度を 3 段階で動的に切り替える統合超解像パイプラインです。全タイルに同一モデルを適用する従来手法と異なり、不要な領域の計算を省略し、重要な領域に最高品質を集中させます。

### 7.2 FECS（Frequency-Entropy Coupled Scoring）

EASS のルート振り分けに使う NEXUS Vision 独自のスコアリング手法です。

各タイルに対して 2D DCT を実行し、DCT 係数を低周波帯（左上 1/4）、中周波帯、高周波帯（右下 1/4）に分割します。各帯域のエネルギー比率 E_low, E_mid, E_high（合計 1.0）を算出し、以下のスコアを計算します。

**FECS スコア: F = H × (E_mid + 2 × E_high) / (1 + E_low)**

H はシャノンエントロピー。H が高く高周波エネルギーが支配的な領域で F が大きくなります。ノイズは E_high が高いが E_mid が低い傾向があり、テクスチャは E_mid も高い傾向があるため、`E_mid + 2 × E_high` の重み付けでテクスチャとノイズを分離します。DCT 計算コストは 128×128 タイルあたり約 0.1 ms です。

### 7.3 3段階ルート振り分け

**ルート A（F < 1.5）– バイキュービック補間のみ**: 青空、白壁、単色背景。< 1 ms / タイル。AI モデル不使用。典型的に全タイルの 30〜50%。

**ルート B（1.5 ≤ F < 4.0）– 蒸留済み RRDB + LoRA**: 草木、建物壁面、服の模様。SwinIR 教師から知識蒸留した改良版 RRDB（Phase 14.6）。約 50〜100 ms / タイル（GPU Vulkan）。条件に応じて LoRA アダプター適用。典型的に全タイルの 30〜40%。

**ルート C（F ≥ 4.0）– 1 ステップ拡散モデル + LoRA**: 文字、顔、網目模様、モアレ。Edge-SD-SR 方式（Phase 14.7、169M パラメータ）。約 200〜400 ms / タイル（GPU Vulkan）。典型的に全タイルの 10〜25%。

### 7.4 LoRA 条件付き適用（Phase 14.3a）

事前学習済み LoRA アダプター（各 2〜10 MB）を推論時にロードし、ベースウェイトに加算します。「テキスト鮮明化 LoRA」は OCR で文字検出されたタイルに適用。「夜景 LoRA」は EXIF ISO 値が高い場合に適用。「ポートレート LoRA」は顔検出タイルに適用。LoRA ロードは一度だけ、適用は < 1 ms。

### 7.5 タイル結合とシーム補正

異なるモデルで処理されたタイルの境界で 8 px オーバーラップ帯の線形ブレンディングを適用します。約 30 ms。

### 7.6 処理時間予測（100MP、432 タイル @ inSampleSize=4）

ルート A（40%、173 タイル）: 約 173 ms。ルート B（35%、151 タイル）: 約 11.3 秒。ルート C（25%、108 タイル）: 約 32.4 秒。合計約 44 秒。全拡散処理（130 秒）の 1/3、品質は重要領域で拡散モデル水準。

### 7.7 閾値の最適化方針

F の閾値（初期値 1.5 と 4.0）は Phase 5 で 100 枚のテスト画像を使い、PSNR / LPIPS / 処理時間の 3 指標でグリッドサーチにより最適化します。シーム補正のオーバーラップ幅も同時に検証します。テストを繰り返しながら最適値に昇華させます。

---

## 第8章 ファイル解析

Excel / CSV → Apache POI / OpenCSV でパース → Markdown 表 / JSON に変換 → Gemma-4 に構造化テキストとして送信。ソースコード（Kotlin / Python）→ 正規表現 + 簡易 AST パーサーで関数・クラス・インポートを抽出 → 構造化テキストとして Gemma-4 に送信しバグ候補やリファクタ案を取得。PDF → Android PdfRenderer でページ画像化 → ML Kit OCR → テキスト統合 → Gemma-4 に要約・抽出を依頼。

---

## 第9章 OS 統合

App Actions（`shortcuts.xml`）で Gemini 音声コマンドから起動。Assist API（`onProvideAssistContent`）で画面コンテキストを提供。`ACTION_PROCESS_TEXT` で選択テキストに「NEXUS 解析」メニュー追加。`ACTION_SEND` で共有ターゲットとして表示。クイック設定タイル。HUD オーバーレイ（`SYSTEM_ALERT_WINDOW`、`TYPE_APPLICATION_OVERLAY`。Accessibility Service 非依存で Android 17 Advanced Protection Mode 対応）。

---

## 第10章 NEXUS Clip（画面テキスト抽出）

スクリーンショット + ML Kit OCR 方式（方式 A）。ユーザーが手動で起動した場合のみ動作します。抽出したテキストはコピー履歴として ObjectBox に保存し、検索・Gemini 要約連携が可能です。表形式が検出された場合は座標クラスタリングで CSV / Markdown テーブルに変換します。

---

## 第11章 ウィジェットと通知

### 11.1 バックグラウンド処理ウィジェット

Jetpack Glance で実装。進行中のバッチ処理タスク一覧と進捗を表示します。タスク完了時に通知を発行しウィジェットから消去します。

### 11.2 通知インライン応答

RemoteInput で通知シェードから Gemma-4 にテキスト質問を送信し、結果を通知で返します。Gemma-4 がオフラインでも動作する環境（機内モード、ネット未接続時）でのみ有効化し、Gemini で対応可能な場合は Gemini を優先します。

---

## 第12章 長期記憶

ObjectBox HNSW ベクトル DB で過去の推論結果・やり取りを意味的に保存します。ユーザーの使用パターンを学習し、パーソナライズ（よく使うカテゴリの優先表示など）を提供します。

---

## 第13章 外部 API

### 13.1 NexusClient SDK

`implementation("com.nexus.ai:nexus-client:1.0.0")`。メソッド: `classify(imagePath, categories)`, `enhance(imagePath, instruction)`, `ask(instruction, text)`, `translate(text, targetLang)`, `analyze(filePath)`。

### 13.2 署名付き Intent API

パッケージ署名検証で不正アプリからの呼び出しを拒否します。

### 13.3 ContentProvider

キャッシュ・履歴を他の NEXUS ファミリーアプリ（Sheets, Translate, Audio）と共有します。

---

## 第14章 将来の最適化候補

### 14.3a LoRA 推論適用

事前学習済み LoRA アダプター（テキスト鮮明化、夜景、ポートレート等）を推論時にロードしベースモデルに加算。各 2〜10 MB。EASS のルート B/C に条件付き適用。

### 14.6 SwinIR / HAT 蒸留超解像

PC 上で SwinIR / HAT を教師、RRDB を生徒として知識蒸留。蒸留済みモデルは既存 NCNN Vulkan パイプラインにファイル差し替えのみで導入可能。モアレ除去、網目模様復元、微細テキスト鮮明化で品質向上。処理時間は現行 Real-ESRGAN と同等。

### 14.7 1 ステップ拡散超解像

Edge-SD-SR 方式（169M パラメータ、約 640 MB モデル）。双方向コンディショニング + スケール蒸留で 1 ステップ推論。S200 GPU Vulkan での推定処理時間は 200〜400 ms / タイル。EASS ルート C 専用。

---

## 第15章 Phase 0 検証結果

モデル: gemma-4-E2B-it.litertlm（2,583,085,056 bytes ≈ 2.58 GB）。テスト画像: test_image.jpg（71,371 bytes）。結果: 6/6 全テスト合格。テスト 1（テキスト推論）: 正答、1,969 ms。テスト 2（画像推論 GPU Vision）: 応答あり、29,994 ms。テスト 3（解像度比較）: 128 px = 20,042 ms、384 px = 23,255 ms、768 px = 31,597 ms。テスト 4（連続推論）: 20/20 成功（100%）。テスト 5（エンジン再起動）: 5/5 サイクル成功。GPU Vision 安定、SIGSEGV エラーなし。

---

## 第16章 別アプリ一覧（NEXUS ファミリー）

NEXUS Sheets: 表 OCR、グリッドエディタ、CSV/XLSX エクスポート。NEXUS Translate: リアルタイム翻訳 HUD、Vosk + Marian NMT 2 段パイプライン、AudioPlaybackCapture。NEXUS Audio: U-Net 音源分離、pYIN 音階検出、CQT コード進行解析。NEXUS Clip: スクリーンショット OCR、コピー履歴管理、Gemini 要約連携。すべて NEXUS Vision の推論エンジンを ContentProvider 経由で共有します。

---

## 第17章 リソース見積もり

Gemma-4 推論: 1.5 GB。ESRGAN / 拡散モデル: 0.3〜0.7 GB。OS + UI + ObjectBox: 2 GB。合計ピーク: 約 4.2 GB（12 GB RAM の S200 で余裕あり）。RAM < 4 GB の端末は画像処理機能を無効化します。

---
