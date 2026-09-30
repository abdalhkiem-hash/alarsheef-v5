# سجل الإصلاحات — ArsheefApp3

نسخة معالجة من المشروع الأصلي. **لم تُبنَ بمترجم أندرويد** — افتحها في Android Studio ونفّذ بناءً كاملًا قبل الاعتماد.

## إصلاحات أُجريت أثناء فحص ضمان الجودة (QA على جهاز Galaxy S22 Ultra)

| المشكلة | الإصلاح |
|---|---|
| الفحص استورد **كل المكتبة** (437 ميجابايت) لأن الفلتر كان `lastScanAt` (أول فحص = آخر_scanAt=0 → كل شيء). | استُبدل بفلتر **«اليوم بيومه»**: `entry.lastModified() < startOfToday()` في `scanFolder` و `visitDocuments`. أُضيف `FileUtils.startOfToday()`. |
| `PdfToImageConverter` يستخدم `Bitmap.Config.RGB_565` → `PdfRenderer.Page.render()` يرفضه برسالة **"Unsupported pixel format"** → كل صفحات PDF تفشل بصمت. | تبديل إلى `ARGB_8888` (نوع ما يشترطه PdfRenderer). تحديث تعليق الذاكرة. أُضيفت سجلات تشخيصية (`Log.i`) في المحوِّل وفرع PDF بالفحص لتظهر الأخطاء بدل الصمت. |
| `FileScannerWorker` لا يُسجّل أي أثر عند فشل المحوِّل أو تخطي الملفات → صعوبة تشخيص المشاكل. | أُضيفت سجلات INFO: اسم الملف قيد المعالجة، عدد صفحات PDF، نتيجة الاستيراد (`Added`/`Duplicate`)، وأسماء المجلدات المفحوصة. |

### التحقق من الجهاز
- الفحص الآن يستورد **فقط ملفات اليوم** (3 صور + صفحة PDF واحدة → ~370 كيلوبايت)، ويترك ملفات الأيام القديمة (2022/2024/2025/يناير-2026) دون لمس ✅.
- تحويل PDF→PNG يعمل: الصفحة المُصاغة (143 كيلوبايت) أُدرجت في الأرشيف تحت `2026/09/20/IMG_...png` ✅.
- التنقل الهرمي (سنة → شهر → يوم → ملفات) والبحث يعملان ✅.
- شاشة "حول التطبيق" تُظهر الإصدار **5.0.0** ✅.
- ⚠️ ملاحظة: إعادة الفحص تُعيد استيراد الصور (مشكلة مسبقة في `findByOriginalPath` لا تمنع التكرار) — لا علاقة لها بهذا الإصلاح وهي تحتاج معالجة لاحقة.

---

## ملفات مستعادة
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` (كانت مفقودة بالكامل)

## ملفات معدّلة
| الملف | التغيير |
|---|---|
| `ArchiveRepository.kt` | نقل كل عمليات الملفات إلى `Dispatchers.IO`؛ حماية من `null` في دمج PDF؛ `mergeImagesToPdf` صار يرجع `File?`؛ `moveImages` صار يرجع عدد المنقول ولا يحدّث السجل عند غياب الملف؛ `capturedAt` من الملف المصدر |
| `AppDatabase.kt` | رفع الإصدار إلى 2 + `MIGRATION_1_2` (تنظيف التكرارات ثم إنشاء الفهارس) بدون فقدان بيانات؛ محوّل `SourceApp` آمن |
| `ArchivedImage.kt` | فهرس تفرّد على `contentHash` + فهرس على `(year, month, day)` |
| `ArchivedImageDao.kt` | حذف `update()` و `countInYear()` (ميتتان) |
| `FileScannerWorker.kt` | مؤشر `lastScanAt` (فحص تدريجي)؛ دخول المجلدات الفرعية حتى 4 مستويات؛ التحقق من الصلاحية؛ `countDuplicate = false` لإيقاف تضخّم العدّاد؛ تنظيف ملفات الكاش |
| `SettingsPreferences.kt` | إضافة `lastScanAt` |
| `PdfToImageConverter.kt` | الدقة 400 → 200 نقطة؛ سقف أبعاد 4000px؛ سقف 100 صفحة؛ التقاط `OutOfMemoryError` |
| `FaceDetectionUtil.kt` | كاشف مشترك بدل واحد لكل صورة؛ تصغير الصورة قبل التحليل؛ حماية من الإلغاء |
| `FilesScreen.kt` | `BackHandler` للعارض والتحديد؛ `Locale.ROOT`؛ `itemsIndexed`؛ `ClipData` للمشاركة؛ معالجة `null` من دمج PDF؛ إزالة زر بحث معطّل |
| `HomeScreen.kt` | `remember(query)` لتدفق البحث + `emptyFlow()` عند الفراغ؛ إزالة زر بحث معطّل |
| `MonthScreen.kt` / `DayScreen.kt` | إزالة أزرار البحث المعطّلة؛ `Locale.ROOT` لرقم اليوم |
| `OnboardingScreens.kt` | حذف 3 استيرادات غير مستخدمة |
| `Color.kt` | حذف `Divider` و `TextSecondary` |
| `FileUtils.kt` | حذف `isSupported()`؛ `Locale.forLanguageTag("ar")` |
| `AndroidManifest.xml` | إضافة `android:roundIcon`؛ حذف صلاحية `POST_NOTIFICATIONS` |
| `backup_rules.xml` / `data_extraction_rules.xml` | استثناء قاعدة البيانات مع ملفات الأرشيف |
| `colors.xml` | حذف 4 ألوان غير مستخدمة |

## P0 #1 — إزالة MANAGE_EXTERNAL_STORAGE والهجرة إلى MediaStore/SAF (20/9/2026)
| الملف | التغيير |
|---|---|
| `AndroidManifest.xml` | حذف `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`. إضافة `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`. |
| `SourceFolder.kt` | **ملف جديد**: `data class SourceFolder` + `sealed class FolderType` (`MediaStoreRelativePath`, `Saf`). |
| `SourceFolders.kt` | إعادة كتابة كاملة: `galleryMediaStoreFolders()` (MediaStore)، `safFolders(prefs)` (SAF tree URIs)، `activeFolders(prefs)`. |
| `PermissionUtils.kt` | إعادة كتابة: `mediaPermissions` (READ_MEDIA_IMAGES/VIDEO)، `hasMediaPermission(context)`، `describePermissionsResults`. حذف `MANAGE_EXTERNAL_STORAGE` flow. |
| `SettingsPreferences.kt` | إضافة `whatsappImagesTreeUri`, `whatsappDocumentsTreeUri`, `downloadsTreeUri` (SAF tree URIs كـ `Flow<String?>`). إضافة `lastBackupAt: Flow<Long>`. استيراد `longPreferencesKey`. |
| `FileScannerWorker.kt` | إعادة كتابة: `scanMediaStore()` (استعلام MediaStore للصور المضافة اليوم)، `scanSafFolder()` (SAF عبر DocumentFile)، `visitDocuments()` مع `doc.name` local var. |
| `ArchiveRepository.kt` | إضافة `importFile(sourceUri: Uri, ...)` + `contentExtension(context, uri)` لاستيراد عبر Uri. |
| `FaceDetectionUtil.kt` | إضافة `hasFace(context: Context, uri: Uri)` (نسخ Uri → ملف مؤقت → تحليل). |
| `OnboardingScreens.kt` | `PermissionScreen` تستخدم `RequestMultiplePermissions()` بدل فتح صفحة MANAGE_EXTERNAL_STORAGE. |
| `SettingsPanels.kt` | أزرار SAF folder picker لواتساب صور، وثائق، تنزيلات. |
| `HomeScreen.kt` | دعم `pendingTreeTarget` أسماء و`treeLauncher` لفتح منتقي SAF. |
| `CHANGELOG.md` | هذا السجل. |

### التحقق
- `:app:assembleDebug` = **BUILD SUCCESSFUL** ✅
- APK مُثبّت على Samsung Galaxy S22 Ultra (SM-S908U) ✅
- التطبيق يُطلق بدون تعطيل (`MainActivity` resumed) ✅
- لا يطلب صلاحية `MANAGE_EXTERNAL_STORAGE` ✅

---

## إضافة — مجلدات فرعية تحت الأشهر (P1)
| الملف | التغيير |
|---|---|
| `SubFolder.kt` (جديد) | Entity `SubFolder(year, month, name)` جدول `sub_folders` مع فهرس فريد `(year, month, name)` |
| `SubFolderDao.kt` (جديد) | `insert`, `observeByMonth(year, month)`, `getById`, `delete` |
| `AppDatabase.kt` | رفع الإصدار إلى **5** + `MIGRATION_4_5` (إنشاء جدول `sub_folders` بدون فقدان بيانات) |
| `ArchiveRepository.kt` | `SubFolderRow` data class + `observeSubFolders(year)`, `createSubFolder(year, month, name)`, `deleteSubFolder(id)` |
| `MonthScreen.kt` | إضافة `showSubFolderDialog`/`newSubFolderName`/`subFolderMonth` state + dialog إنشاء مجلد فرعي + `observeSubFolders` |
| `MonthScreen.kt` — `MonthRowCard` | زر "إضافة مجلد فرعي" في القائمة المنسدلة لكل شهر |

ملاحظات:
- المجلدات الفرعية تُنشأ تحت شهر محدد (مثال: سبتمبر 2026 → "اجتماعات" → أيام) عبر حوار إدخال اسم.
- البناء: `:app:assembleDebug` = **BUILD SUCCESSFUL** ✅، APK مُثبّت على الجهاز ✅.

---

## لم يُعالَج (يحتاج قرارك)
- `MANAGE_EXTERNAL_STORAGE` → خطر رفض في Google Play. البديل: `MediaStore` + `READ_MEDIA_IMAGES` (تغيير معماري).
- 7 ميزات معلنة وغير منفّذة (تصدير/استيراد ZIP، مشاركة نطاق، منتقيات المجلدات، النسخ الاحتياطي).
- شاشة الصلاحية بلا مخرج عند الرفض.

## إضافة — اختبارات الترحيل وتصدير المخطط (P0)
| الملف | التغيير |
|---|---|
| `app/build.gradle.kts` | تفعيل `room.schemaLocation` إلى `app/schemas/`؛ إضافة مجلد المخطط إلى assets الخاصة بـ androidTest |
| `AppDatabase.kt` | `exportSchema = true`؛ جعل `MIGRATION_1_2` / `MIGRATION_2_3` / `MIGRATION_3_4` عامة لاستخدامها في الاختبار |
| `app/schemas/com.alarsheef.archive.data.AppDatabase/4.json` | أول مخطط Room مُصدَّر (مرجع لترقيات قادمة) |
| `AppDatabaseMigrationTest.kt` (جديد) | 3 اختبارات ترحيل على SQLite حقيقي: 1→2 (تنظيف التكرارات + الفهارس)، 2→3 (عمود `originalPath`)، 3→4 (جداول الذكاء الأربعة + `aiAnalyzedAt`) دون فقدان بيانات |

ملاحظات:
- اختبارات الترحيل **اختبارات أجهزة** (`androidTest`) — جُرت ونُفّذت بنجاح على جهاز فعلي (Galaxy S22 Ultra / Android 14): `connectedDebugAndroidTest` = **14/14 اختبارًا ناجحًا** (11 DAO + 3 ترحيل).
- `:app:compileDebugAndroidTestKotlin` + `:app:assembleDebugAndroidTest` يعملان الآن (سُدّت التبعية الناقصة `concurrent-futures-ktx`).
- `gradle.properties`: رفع مهلات HTTP لمحمّل التبعيات (180 ثانية) لشبكة بطيئة/مقطعة.
---
## ����� � ����� ������ ����� �� ���� ������� (P1)

| ����� | ������� |
|---|---|
| CommonDialogs.kt | ����� NewFolderDialog composable (���� ����� ��� ������ ������) |
| HomeScreen.kt | onAddFolder ���� NewFolderDialog ? createSubFolder(latestYear, 0, name) |
| MonthScreen.kt | onAddFolder ���� NewFolderDialog ? createSubFolder(year, month, name) |
| DayScreen.kt | onAddFolder ���� NewFolderDialog ? createSubFolder(year, month, name) |
| FilesScreen.kt | onAddFolder ���� NewFolderDialog ? createSubFolder(year, month, name) |

## ����� � ���� ��������� �������� (P0)

| ������� | ������� |
|---|---|
| isitDocuments �� FileScannerWorker ������ ����� �������� ���� ��� ��� ? ��� stack overflow ��� ������ ����� | ����� if (depth > MAX_DEPTH) return �� ����� isitDocuments |
| AiAnalysisScheduler.start ������ APPEND_OR_REPLACE ? �� ���� ����� ��� ���� | ����� ��� ExistingWorkPolicy.KEEP |
| ����� ������ �� scanMediaStore (��� 122) | ����� spacing |

## تحسين أداء الاستيراد التلقائي (24/9/2026)

| الملف | التغيير |
|---|---|
| `ArchivedImageDao.kt` | إضافة `findAllOriginalPaths()` — تحميل جميع المسارات مرة واحدة بدل استعلام لكل صورة |
| `ArchiveRepository.kt` | إضافة `findAllOriginalPaths()` للمرجع |
| `FileScannerWorker.kt` | **تحسينات كبيرة**: تحميل `existingPaths` كـ Set مرة واحدة، تخطي كشف الوجوه إن لم يُفعَّل، تتبع `newImagesCount`، بدء `AiAnalysisScheduler` فقط عند وجود صور جديدة، إصلاح نوع `ImportResult.Added` |
| `SettingsPanels.kt` | فرد الـ `Column` بـ `verticalScroll` لمنع فقدان الأزرار |
| `HomeScreen.kt` | `skipPartiallyExpanded = true` للـ bottom sheet + `verticalScroll` |

### النتائج
- `:app:assembleDebug` = **BUILD SUCCESSFUL** ✅
- APK مُثبّت على Samsung Galaxy S21 Ultra (SM-S908U) ✅
- الاستيراد التلقائي يعمل مع **واتساب عادي** و**واتساب أعمال** ✅
- قاعدة البيانات تُظهر `sourceApp=WHATSAPP_BUSINESS` للصور من `Android/media/com.whatsapp.w4b/` ✅
- الأزرار الجديدة في الإعدادات مرئية وقابلة للتمرير ✅

## إصلاح استيراد واتساب أعمال + تبسيط المسارات (24/9/2026)

| المشكلة | الإصلاح |
|---|---|
| **خطأ حرج**: فرع `isImage`/`isPdf` معكوس في `visitDocuments` — كل صورة SAF تُنسخ للذاكرة المؤقتة ثم **تُرمى بلا استيراد**، وفرع PDF مستحيل الوصول | ترتيب صحيح: صور ← MediaStore، PDF ← تحويل صفحات، غير ذلك ← استيراد خام. تحديد النوع **قبل** النسخ |
| صور واتساب أعمال مشروطة بوجود SAF grant يدوي | **MediaStore placeholder** لكل مصدر صور (واتساب، أعمال، تنزيلات) — تعمل فورًا بدون اختيار مجلد |
| SAF بطيء جدًا للصور (آلاف استعلامات IPC لكل ملف) | **P1 تبسيط**: MediaStore للصور كلها (استعلام SQL واحد)، SAF للوثائق/PDF فقط |
| الصور تُحفظ في مجلد تاريخ الاستيراد بدل تاريخها الأصلي | **P2**: قراءة `DATE_TAKEN`/`DATE_MODIFIED` من MediaStore، حفظ في مجلد التاريخ الأصلي |

### نتائج التحقق على الجهاز
- `:app:assembleDebug` = **BUILD SUCCESSFUL** ✅
- APK مُثبّت على Samsung Galaxy S21 Ultra ✅
- `Worker result SUCCESS` للفحص اليدوي بعد التعديل ✅
- أزرار اختيار مجلدات الصور أُزيلت من الإعدادات (أصبحت تلقائية) ✅
- الواجهة تظهر: "الصور تُستورد تلقائيًا عبر MediaStore (بدون اختيار مجلد)" ✅

---

## منع تكرار الاستيراد + بوابة المستندات فقط + سجلات قرار الفحص (25/9/2026)

طلب المستخدم: حل التكرار، إصلاح استيراد PDF، استبعاد الصور العائلية والمناظر، والاستيراد **المستندات فقط** (وثائق، فواتير، حوالات صرافة، حركات صناديق).

| الملف | التغيير |
|---|---|
| `ImportGate.kt` (جديد) | بوابة قرار واحدة `shouldArchive(...)`: عند «المستندات فقط» يقرّر `SmartClassifier.isDocumentLike` — **الوثيقة تسبق الوجه** (هوية/جواز/فاتورة بشخص تُقبل)؛ وإلا يرفض صور الوجوه عند تفعيل استبعادها. سجل دائم للقرار `قبول:`/`رفض:` (وسم `ImportGate`). |
| `SmartClassifier.kt` | ترتيب `isDocumentLike` الجديد: طبيعة/مناظر (رفض) ← لافتات وثائق ولقطات شاشة (قبول) ← وجوه (رفض) ← إحصاءات فضفاضة. |
| `FileScannerWorker.kt` | إزالة تخطي الوجوه الصامت؛ سجل لكل قرار (تعذّر/تعطيل/مصدر/أُرشف سابقًا/بصمة/فشل نسخ/Added/Duplicate/Failed)؛ `try/catch` لكل ملف (`IOException`/`SecurityException` ← استمرار بدل فشل الفحص كله)؛ إعادة رمي `CancellationException` بدل تسجيلها «خطأ غير متوقع»؛ انتظار حتى 3 ثوانٍ لتحرير قفل الفحص عند الاستبدال (دبل-تيب الزر)؛ تطبيع السجلات القديمة `//` وبناء `originalPath` بشرطة واحدة؛ الكاش المؤقت يحافظ على امتداد الملف الأصلي (`.png` لا `.jpg` بفرض)؛ سجل صفوف MediaStore. |
| `WorkScheduler.kt` | `runOnce` صار `ExistingWorkPolicy.REPLACE` + سجل «طلب فحص يدوي فوري» — كان `KEEP` يتجاهل النقر أثناء Backoff بصمت. |
| `ArchiveRepository.kt` | **إصلاح لغة الأرقام**: كل `"%02d".format` ← `FileUtils.twoDigits` (`Locale.ROOT`) — بأجهزة عربية كانت الأسماء تُبنى `IMG_2026٠٩٢٥_…` وأهم أصلًا أن مجلدات الحذف السنوي/الشهري (`deleteMonth`/`deleteDay`) تُبنى بأرقام لا تطابق مجلدات الأرشيف الفعلية. |
| `SettingsPanels.kt` | مفتاح «استيراد المستندات فقط (فواتير/حوالات/حركات — بلا صور عائلية أو مناظر)». |
| `SettingsPreferences.kt` | `documentsOnly` (افتراضي مفعّل). |
| `ArchivedImageDao.kt` + ترحيل Room 6→7 | فهرس تفرّد على `originalPath` + `claimOriginalPath()` — طبقة الحماية الثالثة ضد التكرار (كشف المسار ← البصمة ← قاعدة البيانات). |

### التحقق على الجهاز (Galaxy S22 Ultra، 25/9/2026)
- `test_doc.png` (وثيقة) ← `قبول` ← **أُرشف**؛ إعادة الفحص ← `تخطي (أُرشف سابقًا)` — بوابة المسار ✅
- `test_doc_copy.png` (نفس البايتات باسم جديد) ← **`مكرر (بصمة مطابقة)`** — لا صف جديد ولا ملف جديد ✅
- `test_landscape.png` (منظر طبيعي) ← **`رفض`** ✅
- ملف وثيقة جديد ← `IMG_20260925_89675.png` — أرقام ASCII وامتداد `.png` صحيح ✅
- `اكتمل الفحص — صور جديدة مؤرشفة: 0` + `Worker result SUCCESS` ✅
- إلغاء الفحص لم يعد يظهر كـ «خطأ غير متوقع» ✅

ملاحظات:
- مرحلة SAF لا تزال بطيئة (3–7 دقائق لكل فحص) — أداء غير مُعالج.
- سجلات أرشيف قديمة بأسماء `IMG_2026٠٩٢٥_…` تبقى كما هي (تخرج من نافذة «اليوم» خلال 24 ساعة).

---

## مراجعة كود خارجية — تطهير قائمة الإضافة ومفاتيح الميزات (25/9/2026)

مصدر: `alarsheef-v5-reviewed.zip` (مراجعة بالقراءة بدون بيئة بناء، مبنية على commit `5e6e29b`). طُبِّقت تعديلاتها الخمسة يدويًا (لا كامل الحزمة — تجنبًا لمجلدات `{data/...}` المكسورة و`local.properties`):

| الملف | التغيير |
|---|---|
| `util/FeatureFlags.kt` (جديد) | `SUB_FOLDERS_ENABLED = false` — مجلدات فرعية «مدخل بلا مخرج»: تُنشأ في `sub_folders` لكن لا شاشة تعرضها ولا عمود `subFolderId` يربط الملفات بها. إخفاء المدخل لا حذف كود. |
| `AddFab.kt` | `onAddDay` صار `(() -> Unit)? = null` — «إضافة يوم» لا تظهر إلا حيث تُمرَّر دالة فعلية (DayScreen)؛ «إضافة مجلد» مخفية خلف `FeatureFlags.SUB_FOLDERS_ENABLED`. |
| `HomeScreen.kt` / `MonthScreen.kt` / `FilesScreen.kt` | حذف `onAddDay = {}` الميت — كان الزر يظهر ولا يفعل شيئًا عند الضغط. |
| `.gitignore` | استبعاد `/arsheef.json` و`/1arsheef.json` و`/2arsheef.json` (سجلات جلسات opencode الضخمة). |
| `PROJECT_STATUS.md` | نسخة محدَّثة تعكس الحالة الفعلية (قاعدة 7، ترحيلات 1→7 كاملة، مراجعة قراءة فقط — بلا ادّعاء بناء). |

- التحقق: `assembleDebug` = **BUILD SUCCESSFUL** ✅ بعد التطبيق؛ توقيعات `AddFab` الخمسة متسقة (`DayScreen` يمرر `onAddDay` فعليًا) ✅ — وفُحصت قوائم الشاشات الأربع على الجهاز لاحقًا (انظر قسم «إخفاء زر الإضافة في وضع التحديد»).

---

## إخفاء زر الإضافة في وضع التحديد (25/9/2026)

| المشكلة | الإصلاح |
|---|---|
| زر **«حذف»** في شريط أزرار التحديد أسفل FilesScreen كان **مختفيًا خلف زر FAB «إضافة»** — في التخطيط RTL يقع الـFAB أسفل اليسار وهو موضع نفس الزر الأخير في الشريط (مشاركة/نقل/حذف). | `FilesScreen.kt`: يُعرض `AddFab` فقط عند `!hasSelection` — يختفي أثناء التحديد ويعود فور إلغائه. |

### التحقق على الجهاز
- تحديد ملف ← الشريط يعرض **مشاركة + نقل + حذف (أحمر)** كاملة، بلا FAB ✅ (لقطة شاشة)
- إلغاء التحديد ← FAB «إضافة» يعود أسفل اليسار ✅
- فحص قوائم FAB الأربع (Home/Month/Day/Files): «إضافة مجلد» غائبة من كل الشاشات (خلف `FeatureFlags`) و«إضافة يوم» تظهر في DayScreen فقط ✅

---

## واتساب أعمال بمفتاح فقط + كشف PDF عبر MediaStore (25/9/2026)

| الملف | التغيير |
|---|---|
| `SourceFolders.kt` | صور واتساب أعمال تُسجَّل كمجلدات MediaStore **بمفتاح المصدر فقط** (لا اختيار SAF) — والآن المسارتان الشائعان: الجيل الجديد `Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images` والجيل القديم `WhatsApp Business/Media/WhatsApp Business Images`. |
| `FileScannerWorker.kt` | `sourceFromRelativePath` تحوّل كاملة: مطابقة **غير حساسة لحالة الأحرف** وفرع واتساب الأعمال أولًا (يغطي `com.whatsapp.w4b` و`whatsapp business`). **دالة جديدة `scanMediaStorePdfs`**: تستعلم `MediaStore.Files` عن `application/pdf` المضافة اليوم (قبل مرحلة SAF لتسبق هوياتها)، تحوّل الصفحات عبر `PdfToImageConverter`، الهوية `المسار` / `المسار#page=N` + شكل SAF للمسار (`primary:...`) لمنع المعالجة المزدوجة، وتحمل `SecurityException` بتنبيه بدل إسقاط الفحص. إعادة تسمية `copyMediaStoreImageToTemp` → `copyMediaStoreToTemp`. |
| `SettingsPanels.kt` | تفعيل مفتاح «واتساب أعمال» يشغّل الفحص فورًا (`runOnce`) — النتيجة ظاهرة مباشرة بلا أي خطوة SAF. |
| `PermissionUtils.kt` + `AndroidManifest.xml` | **إصلاح**: `mediaPermissions` صارت شرطية — `READ_EXTERNAL_STORAGE` على API ≤32 (كان `READ_MEDIA_*` يُرفض هناك ⇒ الفحص كله معطّل على أندرويد 11/12!) و`READ_MEDIA_IMAGES/VIDEO` على 33+؛ المانيفست يضيف `READ_EXTERNAL_STORAGE` بحد أقصى 32. |

### التحقق على الجهاز (Android 14)
- الفحص بعد التعديل: صور MediaStore تعمل ✓ + `صفوف PDF في MediaStore: 0` بلا خطأ ثم `اكتمل الفحص` ✓
- تحويل PDF فعلي عبر المسار الكامل: فرع SAF أرشف **6 صفحات** من ملفين PDF في الفحص نفسه ✓
- واتساب أعمال: صور `w4b` تُكتشف وتُتخطى كـ«أُرشف سابقًا» بمفتاح واحد فقط ✓

### ملاحظة صلاحية صادقة
- على **أندرويد 13+** لا يغطي `READ_MEDIA_*` ملفات غير الوسائط (PDF) — استعلام `MediaStore.Files` للـPDF يرجع 0 صفوف مهما كان اليوم (سُجّل هذا السلوك في الاختبار). لذلك: على 33+ يظل **SAF** طريق الـPDF الفعلي (مجلدات التنزيلات/الوثائق المعتمدة)، و`scanMediaStorePdfs` يعمل على **أندرويد ≤12** حيث يمنح `READ_EXTERNAL_STORAGE` رؤية PDF كاملة — وبُني ليلتقط تلقائيًا أي تغيّر في سياسة المنصة مستقبلًا.

---

## رسالة تأكيد الخروج + تمرير الصور في عارض الصور (26/9/2026)

| الملف | التغيير |
|---|---|
| `CommonDialogs.kt` | مكوّن جديد `ConfirmExitDialog` (عنوان «الخروج من التطبيق»، نص «هل تريد الخروج من الرشيف؟»، زرّا «خروج»/«إلغاء»). |
| `HomeScreen.kt` | `BackHandler` في الشاشة الرئيسية: زر الرجوع يغلق القوائم المفتوحة (الدرج/اللوحة السفلية) أولًا، وإلا يعرض تأكيد الخروج — «خروج» ينفّذ `finishAffinity()` لإنهاء التطبيق كاملًا. |
| `FilesScreen.kt` | **إصلاح تمرير العارض**: كان `detectTransformGestures` يستهلك السحب بإصبع واحد بالكامل بعد التجاوز ⇒ `HorizontalPager` لا يرى الحركة إطلاقًا ولا ينتقل بين الصور. استُبدل بـ `awaitEachGesture` مخصّص: مصبع واحد والصورة بالحجم العادي ⇐ **بلا استهلاك** (يمرّر السحب للتنقل بين الصفحات)، إصبعان فأكثر ⇐ تقريب/تصغير (حتى 5×) مع الاستهلاك، ومصبع واحد والصورة مكبّرة ⇐ تحريك داخلها دون تغيير الصفحة. |

### التحقق على الجهاز (Galaxy S22 Ultra، Android 14)
- خروج: زر الرجوع من الرئيسية ⇐ يظهر تأكيد الخروج ✓ — «إلغاء» يُغلق الحوار ويبقى التطبيق حيًّا ✓ — «خروج» ينهي التطبيق (أصبح اللانشر هو النشط) ✓
- الرجوع مع درج القائمة مفتوح ⇐ يغلق الدرج فقط بلا تأكيد ✓
- العارض: سحب من المنتصف ينتقل للصورة التالية/السابقة ويبقى على الصفحة بعد الإفلات ✓ (تُحقّق من تغيّر الاسم أسفل الشاشة 11→12→13→12 بالترتيب)
- ملاحظة سلوك جهاز: السحب الذي **يبدأ من الحافة اليسرى** (~115dp) يستولي عليه نطاق رجوع النظام فيستعيد الصفحة الأصلية — سلوك المنصة وتشمل كل التطبيقات؛ الحافة اليمنى والمنتصف تعملان طبيعيًا.

---

## تكامل Gemini السحابي — تصنيف ووصف وفواتير وOCR عربي (27/9/2026)

نظام **هجين**: فحص محلي سريع أولًا (بدون إنترنت) + استدعاء Gemini عند الحاجة فقط لحماية الحد اليومي.

| الملف | التغيير |
|---|---|
| `app/build.gradle.kts` | تحميل `GEMINI_API_KEY` بالأولوية: `local.properties` (متجاهل في Git) ← `gradle.properties` ← متغيّر البيئة، ثم `buildConfigField("String", "GEMINI_API_KEY", ...)` + `buildFeatures.buildConfig = true`. |
| `local.properties` | سطر `GEMINI_API_KEY=` فارغ بانتظار المفتاح (الملف متجاهل في Git أصلًا — لا يُرفع المفتاح أبدًا). |
| `AndroidManifest.xml` | صلاحية `INTERNET` (لـHTTPS فقط). |
| `GeminiRepository.kt` (جديد) | عميل REST v1beta بـ`HttpURLConnection` **بلا أي مكتبة خارجية**: ترويسة `x-goog-api-key` (لا تُطبع)، نماذج `gemini-3.5-flash` (افتراضي — بُدِّل عن `2.5-flash` لأن نماذج 2.5 موقوفة لحسابات جديدة) و`gemini-3.1-pro-preview` (اختياري حسب اقتراح الـAPI)، تصغير الصورة إلى ≤1024px (1600 للOCR) → JPEG → base64، `suspend + Dispatchers.IO`، **أي فشل (شبكة/429/مفتاح/استجابة) يرجع `null` بهدوء** فلا يسقط المسح. يضمّ `extractText` لـOCR عربي حرفي. سقف `DAILY_LIMIT = 50` + **مؤشّر `rateLimited`**: أول ردّ 429 من Google يوقف كل نداءات السحابية في الدفعة الجارية (يُصفَّر في بدايتها) فلا تُهدر حصّة اليوم. |
| `GeminiClassifier.kt` (جديد) | تصنيف سحابي بمخطط `responseSchema` JSON منظّم: `labels[]` عربية + `description` + `invoice{vendor,date,amount,currency,details}` — مع `parse()` دفاعي (`runCatching`) ورفض الحقول الفارغة/"null". |
| `AiEntities.kt` | كيان جديد `AiCloudMeta(imageId PK, description, vendor, invoiceDate, amount, currency, details, model, analyzedAt)` — صف واحد لكل صورة سُحبت نتائجها. |
| `AppDatabase.kt` | الإصدار **7 ← 8** + `MIGRATION_7_8` (جدول جديد فقط — لا تعبئة بيانات). |
| `AiDao.kt` | `upsertCloudMeta` / `observeCloudMeta` / `getCloudMeta` / `deleteCloudMetaForImageIds`. |
| `ArchiveRepository.kt` | `saveCloudMeta` (وصف + فاتورة) + `observeCloudMeta`؛ وحذف الصفوف السحابية في `cleanupAiRowsForImages`. |
| `ArchivedImageDao.kt` | `searchAll` يبحث الآن أيضًا في `ai_cloud_meta` (الوصف/الجهة/البنود) — البحث يشمل الوصف والفاتورة. |
| `SettingsPreferences.kt` | مفتاحا `geminiCloudEnabled` (افتراضي مفعّل) و`geminiUsePro` (افتراضي flash) + **حصة يومية**: `tryConsumeGeminiQuota(50)` تحجز محاولة قبل كل استدعاء وتتصفَّر كل يوم + `geminiQuota` للعرض. |
| `AiAnalysisWorker.kt` | **السياسة الهجينة**: التصنيف المحلي أولًا؛ سحابي فقط إذا كانت الصورة بلا وسوم/ثقة < 0.6 أو مستند/لقطة شاشة (لتوليد الوصف والفاتورة)، مع دمج وسوم ML Kit للوجوه مع نتيجة السحابة. OCR: نص محلي ≥20 حرفًا يكفي؛ وإلا سحابي (يغطي أندرويد < 13 أو غياب النموذج)؛ وأخيرًا احتياطي بالنص المحلي. الحصة/المفتاح/الخطأ ⇒ عودة صامتة للمحلّي. **عند `rateLimited` (429 من Google) تُوقَف محاولات السحابية لبقية الدفعة** مع بقاء الفحص المحلي مستمرًا. |
| `AiPanel.kt` | قسم «Gemini السحابي»: مفتاح التفعيل + مفتاح flash/pro + **حالة المفتاح** (مضبوط ✓ / غير مضبوط مع رابط AI Studio) + عداد «استهلاك اليوم: x من 50» + شرح سياسة الحفاظ على الحد اليومي؛ وتحديث نصوص تنبيه OCR لجاهزية البديل السحابي. **إصلاح**: إضافة `verticalScroll` لأن اللوحة أصبحت أطول من الـModalBottomSheet (كان أزرار إعادة التحليل والوجوه خارج الشاشة بلا تمرير). |
| `FilesScreen.kt` | العارض يعرض أسفل الصورة: وصف عربي من Gemini + شارة «فاتورة: الجهة · المبلغ · التاريخ» إن وُجدت. |
| `CHANGELOG.md` | هذا السجل. |

### التهيئة (شرط تشغيل السحابة)
1. أنشئ مفتاحًا مجانيًا من `https://aistudio.google.com/apikey`.
2. ضعه في `local.properties`: `GEMINI_API_KEY=AIza...` (الملف متجاهل في Git).
3. أعد بناء التطبيق — بلا مفتاح يعمل التطبيق كاملًا محليًا كما كان (السحابة متوقفة بهدوء).

### التحقق على الجهاز (Galaxy S22 Ultra، Android 14)
- `assembleDebug` = **BUILD SUCCESSFUL** ✅ (3 بناءات: بدون مفتاح، بمفتاح تجريبي، النهائي)
- ترقية قاعدة بيانات حقيقية **7 → 8** على جهاز به DB v7 قائم: التطبيق أُطلق بلا crash ولا فقدان ✅
- لوحة الذكاء الاصطناعي: قسم «Gemini السحابي» + «المفتاح غير مضبوط» (بناء بلا مفتاح) ⇐ «المفتاح مضبوط ✓» (بناء بالمفتاح) ✓ + عداد «استهلاك اليوم: 0 من 50 استدعاء» ✓
- **مسار الخطأ الحقيقي**: بمفتاح غير صالح — استدعاء فعلي إلى `generativelanguage.googleapis.com` ⇐ رد `400 API_KEY_INVALID` سُجّل تحذيرًا و**كُتل بصمت** ⇐ عودة للتحليل المحلي ⇐ **صفر `FATAL EXCEPTION`** وانتهى الـWorker بـ`SUCCESS` ✅
- **الحد اليومي**: بعد 50 محاولة توقّف النداء السحابي تمامًا وعرض «50 من 50» ثم تحلّل الباقي محليًا ✅
- لوحة تمرير عمودي الآن: زرا «إعادة تحليل كل الأرشيف» و«مجموعات الوجوه» أصبحا قابلين للوصول ✓
- بدون مفتاح: لا يُجرى أي اتصال شبكة إطلاقًا (`isConfigured = false` ⇒ null قبل أي نداء) ✅

### تحقّق حقيقي بمفتاح فعّال (Galaxy S22 Ultra، 30/9/2026)
- **نماذج 2.5 موقوفة لحسابات جديدة**: ردّ فعلي `404 This model is no longer available to new users` لكل من `gemini-2.5-flash` و`gemini-2.5-pro` — فحُدِّث الكود إلى ما يقترحه الـAPI: `gemini-3.5-flash` (افتراضي، اختُبر `200 OK`) و`gemini-3.1-pro-preview` (اختياري). فحص مضيف مباشر أكّد: `3-flash-preview` و`3.5-flash` و`3.1-flash-lite` تعمل، وكل نماذج pro تُرجع `429` على حساب مجاني (لذلك هي **معطّلة افتراضيًا** وتُعامل بالبديل المحلّي).
- **نداءات سحابية حقيقية ناجحة**: إعادة تحليل كاملة ⇐ استُهلكت «8 من 50» محاولة ⇐ **6 نداءات `200 OK` صامتة** + `503` (ضغط مؤقت) + `429` واحد ⇐ **توقّف بقية السحابية تلقائيًا (`rateLimited`)** ⇐ صفر `FATAL EXCEPTION` ⇐ Worker `SUCCESS` ✅
- **نتائج محفوظة فعلًا**: 3 صفوف في `ai_cloud_meta` بموديل `gemini-3.5-flash` — منها فاتورة كاملة «مكتب الدكتور أيمن لخدمات الدواجن · 15.81 دولار أمريكي · 23/09/2026» وأوصاف عربية صحيحة (لقطة شاشة محادثة Gemini، أشخاص نائمون…) ✅
- **البحث بالوصف السحابي يعمل**: بحث `Gemini` وجد صورة الوصف السحابي، وبحث `15.81` وجد صورة الفاتورة عبر `ai_cloud_meta` ✅
- **العارض**: وصف عربي كامل أسفل الصورة + شارة «فاتورة» + سطر «فاتورة: الجهة · المبلغ · التاريخ» ظاهر فعليًا على الصورة الفاتورة ✅



## تصميم واجهات عصري حديث (30/9/2026)

أسلوب «عصري نظيف وبسيط» (Notion/Things) منفَّذ مباشرة في نظام التصميم كله:

| الملف | التغيير |
|---|---|
| `Color.kt` | خلفية محايدة دافئة `AppBackground` + توافقات جديدة: `TextMuted` (نص ثانوي هادئ) و`OutlineSoft` (خيوط شعرية) و`SurfaceTinted` (أسطح ثانوية) مع الحفاظ على الهوية تركوازي/كهرماني. |
| `Type.kt` | سلّم حديث11 حجمًا (headline→label) مع `lineHeight` أرحب — يحسّن قراءة العربية. |
| `Theme.kt` | أشكال مسجّلة `Shapes` (8–24dp) تُطبَّق تلقائيًا على البطاقات والحوارات + ألوان `outline`/`onSurfaceVariant`/`surfaceVariant` + **شريط حالة بلون الخلفية بأيقونات داكنة** (بديل الشريط التركوازي الصلب). |
| `SearchField.kt` | «حبّة» بيضاء بخيط شعري ومدخل رمادي هادئ بدل الصندوق التركوازي. |
| `HomeScreen/MonthScreen/DayScreen/FacesScreen/FilesScreen` | **أشرطة علوية محايدة** متجانسة مع الخلفية (بدون أيقونات بيضاء ثابتة) + **بطاقات18dp** + بلاطات أيقونات14dp. |

### التحقق على الجهاز (Galaxy S22 Ultra، 30/9/2026)
- `assembleDebug` = BUILD SUCCESSFUL + تثبيت ناجح بلا `FATAL EXCEPTION` واحد منذ التثبيت (7 انهيارات سابقة كلها من فترة البناء المعطوب v7-vs-v8 ثم حُلّت) ✅
- لقطات شاشة مؤكدة بصريًا: **الرئيسية** (شريط محايد + حبة بحث + بطاقة2026 بظل ناعم + FAB كهرماني) + **قوائم الملفات** (بطاقات مستديرة + فواصل نظيفة) + **لوحة Gemini** (مقبض تمرير + مفاتيح حديثة + زر حبّة) ✅
- ملاحظة تشغيلية: ظهر `checkout` إلى فرع `backup-pre-refactor` أثناء العمل فأعاد التطبيق لوضع v7 القديم (انهيار ترقية عكسية8→7) — عُدِّل بالعودة إلى `master` وكود v8 كاملًا ✅

## إعادة تصميم كاملة: النمط الزجاجي المتدرّج (30/9/2026 — الاتجاه B)

اختار المستخدم اتجاه «زجاجي متدرّج» بعد معاينة ثلاثة مخططات مرئية أُعدّت محليًا (HTML/CSS → Chrome headless)
بدل Canva/nano-banana (نانو بنانا محجوب: 7/7 نماذج صورة بحدّ `free-tier limit: 0` يحتاج Billing، وCanva غير قابل للتشغيل الآلي):

| الملف | التغيير |
|---|---|
| `MainActivity.kt` | خلفية مشتركة: تدرّج `#0F766E → #12A5A0 → #4F46E5` + ثلاث كرات ضوئية ناعمة (تركوازي/نيلي/كهرماني) خلف كل الشاشات. |
| `Color.kt` | نظام زجاجي جديد: `Glass/GlassStrong/GlassSheet/GlassBorder` (أسطح شفافة بيضاء 15–33% + خيوط30%) + `TealBright/Mint` للعناصر الصغيرة + `Gradient*` و`Glow*`. |
| `Theme.kt` | `background` شفاف، `surface` زجاجي، نصوص بيضاء، أخطاء حمراء مضيئة، شريط حالة شفاف بأيقونات فاتحة. |
| `AddFab.kt` | زر الإضافة: أبيض + أيقونة تركوازية داكنة (بدل كهرماني). |
| الشاشات الخمس + الدرج | بطاقات بحدّ زجاجي شعري 18dp، بلاطات أيقونات زجاجية22%، أيقونات/روابط بيضاء، ملاحظات فارغة بشفافية45%. |
| `HomeScreen` | ألواح منزلقة (لوحة AI/الإعدادات) بشفافية33% لقراءة أنظف فوق التدرّج. |

### التحقق على الجهاز (Galaxy S22 Ultra، 30/9/2026)
- `assembleDebug` BUILD SUCCESSFUL + تثبيت ناجح + تشغيل بلا انهيار ✅
- لقطات مؤكدة: **الرئيسية** (تدرّج كامل + حبة بحث زجاجية + بطاقة2026 بحدّ شعري + FAB أبيض) + **شاشة الشهر** (نفس اللغة) + **لوحة Gemini** (شيت زجاجي33% فوق التدرّج، مفاتيح تركوازية، استهلاك 14 من50) ✅

### جولة مطابقة المعاينة B (30/9/2026 — بعد ملاحظة المستخدم «التصميم يختلف عن المعاينة»)
- **كثافة زجاج أعلى** لمطابقة مظهر المعاينة الحليبي: `Glass 15→24%`، `GlassStrong 22→30%`، `GlassBorder 30→35%`، `GlassSheet 33→40%`.
- **كرات ضوئية بمواقع المعاينة**: تركوازي أعلى اليسار (`0.10w,0.04h`، α0.50)، بنفسجي `#8B5CF6` أسفل اليمين (α0.60)، كهرماني يسار الوسط (α0.22) + تدرّج بنقاط توقّف `0/38%/100%` (منتصف كما في CSS الخاص بالمعاينة).
- **شواخذ موسّطة**: استبدال `TopAppBar` بـ `CenterAlignedTopAppBar` في كل الشاشات السبع (الرئيسية/الشهر/اليوم/الوجوه×2/الملفات×2) + **شرائح زجاجية** (`GlassStrong` + نصف قطر14) خلف أيقونات القائمة/البحث/الرجوع/المشاركة/التحديد.
- `HomeScreen`: عنوان بطاقة السنة أصبح `headlineMedium` (مظهر «بطل» كما في المعاينة).
- التحقق: `assembleDebug` BUILD SUCCESSFUL (5m5s) + تثبيت + لقطات **g06 الرئيسية / g08 قائمة الأيام / g07 الملفات** ✅ (الجهاز أفقي2316×1080).

