# رفيق المسلم — تطبيق Android واحد

مشروع Android Studio كامل: الواجهة (`app/src/main/assets/www/index.html`) + الأذان الأصلي (Java) في تطبيق واحد. لا يحتاج Capacitor ولا npm.

> لم يُترجَم (compile) ولم يُجرَّب على جهاز في بيئتي (لا Android SDK ولا شبكة). فُحصت صياغة Java وصحة ملفات XML ومنطق الجسر مع الواجهة بمحاكاة. افتح المشروع في Android Studio وأصلح أي خطأ يظهر ثم اختبر على هاتف حقيقي.

## الخطوات
1. شغّل `get_audio.sh` (أو `get_audio.ps1` على ويندوز) مرة واحدة لتنزيل ملفي الأذان.
   أو ضعهما يدويًا بهذين الاسمين في `app/src/main/res/raw/`: `adhan_alafasy_fajr.mp3` (الفجر - العفاسي) و`adhan_qatami.mp3` (باقي الصلوات - القطامي).
2. افتح مجلد المشروع في **Android Studio** (Hedgehog أو أحدث) وانتظر Gradle Sync.
3. APK تجريبي: Build ← Build Bundle(s)/APK(s) ← Build APK(s). الناتج: `app/build/outputs/apk/debug/app-debug.apk`.
4. النسخة النهائية: Build ← Generate Signed Bundle/APK.
(سطر الأوامر: نفّذ `gradle wrapper` مرة واحدة ثم `./gradlew assembleDebug`.)

## كيف يعمل الأذان في الخلفية
الواجهة تحسب مواقيت 30 يومًا وترسلها عبر `window.AndroidNative` إلى `AdhanScheduler`، فيحفظها ويسجّل **منبّهًا واحدًا** (`setExactAndAllowWhileIdle`) للعنصر القادم. عند حلوله يشغّل `AdhanReceiver` خدمة أمامية `AdhanService` (mediaPlayback) تعرض إشعار «حان الآن وقت صلاة …» وتشغّل الـ MP3 من `res/raw` مع Audio Focus، ثم يُسجَّل المنبّه التالي. معرّف ثابت لكل صلاة/يوم + سجل «تم التشغيل» يمنعان التكرار. `BootReceiver` يعيد الجدولة بعد إعادة التشغيل أو تغيير الوقت/المنطقة/التحديث. تذكيرات «ذكر الساعة» تمر بنفس المنبّه كإشعارات بدون صوت أذان (قناة «التذكيرات»).

## أذونات يفعّلها المستخدم (أزرار في الإعدادات ← صوت الأذان)
الإشعارات (Android 13+) · التنبيهات الدقيقة (Android 12+) · استثناء البطارية (وعلى شاومي/هواوي/سامسونج فعّل «التشغيل التلقائي» وعدم تقييد الخلفية). الموقع يُطلب عند الضغط على أزرار الموقع.

## اختبار
فعّل الأذان ومنح الأذونات ← جرّب الصوتين ← أغلق التطبيق وأقفل الشاشة ← قبل الصلاة بدقيقتين اضبط ساعة الهاتف أو غيّر المدينة ← تأكد من الصوت والإشعار وعدم التكرار ← أعد تشغيل الهاتف وتأكد من الصلاة التالية (`adb shell dumpsys alarm | grep rafiq`) ← وضع الطيران.

## نسخة Release موقّعة (APK)
1. أنشئ مفتاح التوقيع **مرة واحدة** (احتفظ به وبكلمات مروره في مكان آمن؛ فقدانه يمنع تحديث التطبيق لاحقًا):
   `keytool -genkeypair -v -keystore rafiq-release.jks -alias rafiq -keyalg RSA -keysize 2048 -validity 10000`
2. انسخ `keystore.properties.example` إلى `keystore.properties` واملأ القيم (ضع ملف `.jks` في جذر المشروع بجانبه).
3. شغّل `get_audio.sh` (أو `.ps1`) قبل البناء ليدخل صوت الأذان في الـAPK.
4. نفّذ: `./gradlew assembleRelease` (على ويندوز `gradlew.bat assembleRelease`)
   الناتج: `app/build/outputs/apk/release/app-release.apk`
5. تحقق من التوقيع: `apksigner verify --verbose app/build/outputs/apk/release/app-release.apk`
أيقونة المتجر (512×512): `app/ic_launcher_512.png`.

## التحديث التلقائي خارج Google Play
التطبيق يفحص ملف التحديث التالي عند التشغيل:
`https://raw.githubusercontent.com/RafiqMuslim-Updates/RafiqMuslim-Updates/refs/heads/main/update.json`

إذا كان `versionCode` الموجود في الملف أكبر من نسخة التطبيق، تظهر نافذة **تحديث الآن / لاحقًا**. عند اختيار التحديث يتم تنزيل APK ثم فتح مُثبّت Android. على Android 8+ سيطلب التطبيق السماح له بالتثبيت من هذا المصدر إذا لم يكن مسموحًا.

> مهم: يجب أن تكون كل نسخة APK لاحقة موقعة **بنفس مفتاح التوقيع** المستخدم للنسخة المثبتة. كما يجب وضع رابط HTTPS مباشر قابل للتنزيل للـAPK في الحقل `apkUrl` داخل `update.json`.

مثال:
```json
{
  "versionCode": 2,
  "versionName": "1.1",
  "apkUrl": "https://example.com/rafiq-muslim-1.1.apk",
  "message": "تحديث جديد لتطبيق رفيق المسلم"
}
```
