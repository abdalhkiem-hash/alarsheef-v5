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

- التحقق: `assembleDebug` = **BUILD SUCCESSFUL** ✅ بعد التطبيق؛ توقيعات `AddFab` الخمسة متسقة (`DayScreen` يمرر `onAddDay` فعليًا) ✅. فحص قائمة FAB على الجهاز معلّق حتى إعادة توصيله.

