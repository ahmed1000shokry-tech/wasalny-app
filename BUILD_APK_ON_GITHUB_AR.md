# وصلني — بناء APK من GitHub

## 1) إنشاء Repository
- افتح GitHub وأنشئ Repository جديد، ويفضل Private.
- ارفع **محتويات هذا المجلد** إلى جذر الـ Repository.
- تأكد أن الملف `.github/workflows/build-apk.yml` موجود.

## 2) إعداد Firebase
- فعّل Anonymous Authentication وأنشئ Firestore.
- انشر قواعد Firestore والفهارس من [FIRESTORE_SETUP_AR.md](FIRESTORE_SETUP_AR.md).
- لا يحتاج عرض OpenStreetMap إلى مفتاح Google Maps أو GitHub Actions secret للخرائط.
- يلزم اتصال إنترنت لتحميل بلاطات الخريطة.

## 3) تشغيل البناء
من GitHub:
Actions → Build Wasalny APK → Run workflow

أو اعمل Push على فرع `main`.

## 4) تنزيل APK
بعد نجاح الـ workflow:
Actions → افتح آخر تشغيل ناجح → Artifacts → `Wasalny-APK`

نزّل الملف المضغوط واستخرج APK ثم ثبته على الموبايل.

## ملاحظات
- هذه نسخة Debug للتجربة.
- `minSdk 24`، أي Android 7 أو أحدث.
- الـ workflow يستخدم JDK 17 وGradle Wrapper 8.7 وAndroid SDK.
