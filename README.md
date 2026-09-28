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
npm install
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
- أضف رقم/حساب المشرف إلى مجموعة `admins` بصلاحية `role=admin` و`active=true`.
- اختبر التطبيق على جهازين حقيقيين: راكب + سائق، قبل الإطلاق العام.
- الدفع الإلكتروني غير مفعّل؛ الرحلات الحالية تعتمد على الأجرة النقدية المتفق عليها.

## حالة الاختبار

تم إجراء فحص ساكن للملفات، JSON/XML، وتناسق حالات الرحلة. بناء Android الكامل لم يُنفذ داخل بيئة الفحص لأن تنزيل Gradle الخارجي غير متاح هنا؛ لذلك GitHub Actions هو اختبار البناء النهائي عند الـPush.

راجع `AUDIT_REPORT_AR.md` و`PRODUCTION_ARCHITECTURE_AR.md` قبل النشر.
