# نشر Backend وصلني

## 1) تسجيل Firebase CLI

من جهازك:

```bash
npm install -g firebase-tools
firebase login
```

## 2) من مجلد المشروع

فعّل Firebase Storage وأنشئ bucket للمشروع `wasalny-app-f5dbb` من Firebase Console قبل رفع صور طلب السائق أو إثبات التحويل. صور البطاقة والمركبة والشخصية (اختيارية) وإثبات التحويل تقبل JPG/PNG/WEBP حتى 5 ميجابايت، وقراءتها مقيدة بالسائق صاحبها والمشرف. تحقق أن `app/google-services.json` يشير إلى المشروع نفسه.

```bash
firebase use wasalny-app-f5dbb
firebase deploy --only firestore:rules,firestore:indexes,storage,functions
```

تأكد من وجود `admins/{UID}` بحقلَي `role: "admin"` و`active: true`. تعتمد مراجعة الاشتراكات على صلاحية UID هذه؛ لا تستخدم كلمة مرور ثابتة داخل التطبيق.

رسوم السائق 100 جنيه لأول شهر و200 جنيه للتجديد، والتحويل إلى 01069631950. المراجعة يدوية؛ لا يؤكد التطبيق وصول الأموال تلقائيًا. السائقون المعتمدون الحاليون دون اشتراك سابق يحتاجون أول اشتراك بقيمة 100 جنيه بعد التفعيل.

الشروط الموجودة صياغة تشغيلية أولية محفوظة محليًا لكل UID؛ اعتمد النص وسياسة الخصوصية قانونيًا قبل الإطلاق العام.

## 3) بناء APK

```bash
./gradlew assembleDebug
```

أو استخدم GitHub Actions، حيث يوجد Workflow لفحص Android وCloud Functions.

## مهم

Cloud Functions وFirebase Storage تحتاج إعداد Billing مناسبًا وفق مشروع Firebase. ضع Budget Alert، وحدد سياسة الاحتفاظ بإيصالات التحويل، واختبر الرفع والقبول والرفض بحسابات تجريبية قبل الإنتاج.

الدفع الإلكتروني غير مفعّل؛ إثباتات الاشتراك تُراجع يدويًا. لا توجد إشعارات دورية أثناء الرحلة؛ الإشعار الحالي يرسل تحديثًا عند بدء الرحلة فقط.
