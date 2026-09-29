# وصلني توكتوك — V5.1.0

منصة حجز توكتوك لسيدي سالم، مبنية على Android + Firebase، مع فصل واضح بين الراكب والسائق والإدارة.

## ما تم تطويره في V5.1

- تسجيل المستخدمين برقم هاتف موثق عبر SMS بدل الحسابات المجهولة.
- دورة الرحلة كاملة: بحث → عروض → اختيار → في الطريق → وصل → بدأت → انتهت.
- مطابقة سائقين Server-side عبر Firebase Cloud Functions.
- توسيع البحث: 500م → 1كم → 2كم → 5كم.
- حجز مدارس/حجز مسبق بحالة `scheduled` وتشغيل البحث تلقائيًا وقت الموعد.
- إشعارات Push للراكب والسائق عند الطلب والعرض والقبول وحالات الرحلة.
- تقييم متبادل بعد انتهاء الرحلة مع منع التقييم المكرر.
- سائقون معتمدون فقط يدخلون نظام استقبال الرحلات.
- حالة السائق متاح/غير متاح مع تحديث الموقع في الخلفية.
- متابعة موقع السائق لحظيًا أثناء الرحلة.
- صلاحيات Firestore مقيدة حسب الدور والحالة.
- إدارة طلبات السائقين من لوحة المشرف.
- GitHub Actions لفحص وبناء Android وCloud Functions.
- تنظيف أسرار التوقيع وعدم تضمين ملفات keystore في Git.

## المجلدات

- `app/` تطبيق Android.
- `functions/` Backend للمطابقة والحجوزات والإشعارات.
- `firestore.rules` قواعد الأمان.
- `firestore.indexes.json` الفهارس المطلوبة.
- `.github/workflows/` فحص وبناء المشروع.

## تشغيل Android

```bash
./gradlew assembleDebug
```

## تشغيل Backend

```bash
cd functions
npm ci
npm run lint
npm run build
```

ثم من جذر المشروع:

```bash
firebase use wasalny-app-f5dbb
firebase deploy --only firestore:rules,firestore:indexes,functions
```

## متطلبات الإنتاج

- فعّل Phone Authentication في Firebase.
- فعّل Cloud Functions وCloud Scheduler؛ الحجز المسبق والإشعارات يعتمد عليهما.
- استخدم خطة Blaze لـ Firebase Functions/Scheduler وفق إعدادات مشروعك.
- لا ترفع `app/google-services.json` إلى Git؛ أضف محتواه Base64 إلى GitHub Secret باسم `GOOGLE_SERVICES_JSON` لبناء نسخة CI متصلة بمشروعك.
- أضف رقم/حساب المشرف إلى مجموعة `admins` بصلاحية `role=admin` و`active=true`.
- اختبر التطبيق على جهازين حقيقيين: راكب + سائق، قبل الإطلاق العام.
- الدفع الإلكتروني غير مفعّل؛ الرحلات الحالية تعتمد على الأجرة النقدية المتفق عليها.

## حالة الاختبار

نجح `assembleDebug` باستخدام Gradle 8.7 وAGP 8.6.1 على JDK 21، ونجحت اختبارات تثبيت واعتماديات Cloud Functions (`npm ci`, lint, build, audit). يجهز GitHub Actions Java 17 وNode 22؛ يجب تأكيد تشغيله على GitHub بعد رفع التغييرات. لم يُشغّل Firebase Emulator أو اختبار جهازين حقيقيين في هذه البيئة.

راجع `AUDIT_REPORT_AR.md` و`PRODUCTION_ARCHITECTURE_AR.md` قبل النشر.
