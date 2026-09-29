# قائمة تدقيق التشغيل الحقيقي قبل الإطلاق

## الحالة الحالية الموثقة

تم التحقق فعليًا محليًا عبر الأمر التالي:

```bash
cd /workspaces/wasalny-app && ./gradlew testDebugUnitTest assembleDebug --no-daemon && cd /workspaces/wasalny-app/functions && npm run lint && npm run build && npm audit --audit-level=high
```

النتيجة الموثقة:
- Android build: نجح
- Functions lint: نجح
- Functions build: نجح
- npm audit: 0 vulnerabilities

هذا يثبت أن المشروع يحترم البناء والتجميع محليًا، لكنه لا يثبت التشغيل الحقيقي على Firebase أو الإطلاق الإنتاجي.

## ما يجب تدقيقه على Firebase الحقيقي

### 1) Firebase Project Setup
- [ ] إنشاء مشروع Firebase جديد أو مستهدف
- [ ] تفعيل Firebase Authentication
- [ ] تفعيل Phone Authentication
- [ ] تفعيل Firestore Database
- [ ] تفعيل Cloud Functions
- [ ] تفعيل Cloud Messaging (FCM)
- [ ] تأكيد إعدادات التحقق من الهوية للأرقام الهاتفية
- [ ] تحميل ملف google-services.json إلى [app](app)

### 2) Firestore Security Rules
- [ ] نشر [firestore.rules](firestore.rules) إلى مشروع Firebase الحقيقي
- [ ] اختبار كل سيناريو: إنشاء مستخدم، إنشاء سائق، إنشاء رحلة، إلغاء رحلة، تقييم، مشاركة الموقع
- [ ] التحقق من أن البيانات الخاصة مثل رقم الهاتف لا تُخزن في مستندات قابلة للقراءة العامة
- [ ] اختبار حالة السائق غير المعتمد وعدم السماح له بالكتابة أو استقبال الطلبات
- [ ] اختبار أن السائق المختار فقط يمكنه الوصول إلى بيانات الاتصال الخاصة

### 3) Firestore Indexes
- [ ] نشر [firestore.indexes.json](firestore.indexes.json) إلى المشروع الحقيقي
- [ ] اختبار الاستعلامات التالية:
  - rides by customerId + createdAt
  - rides by status + scheduledAt
  - drivers by approved + available + geohash
- [ ] التحقق من عدم ظهور أخطاء index required في الجلسة الفعلية

### 4) Cloud Functions
- [ ] نشر Functions من [functions](functions)
- [ ] اختبار startRideSearch على rideId حقيقي
- [ ] اختبار dispatchScheduledRides عندما يكون ride في scheduled
- [ ] اختبار notifyRideEvents عند تغيير status من searching إلى offered
- [ ] اختبار notifyDriverOnRequest عند إنشاء request للسائق
- [ ] اختبار heartbeatDriver بإحداثيات صالحة وغير صالحة
- [ ] التحقق من سلوك الوقت، الـ timeout، والموارد في Firebase

### 5) Scheduler
- [ ] تفعيل Cloud Scheduler على المشروع الحقيقي
- [ ] التحقق من cron: every 1 minutes
- [ ] التأكد من أن التوقيت يعرض Africa/Cairo الصحيح
- [ ] مراقبة تشغيل الوظيفة في السجلات وتجنب التكرار أو الحظر

### 6) FCM / Notifications
- [ ] اختبار تسجيل FCM token للمستخدم والسائق
- [ ] اختبار استقبال push notification عند:
  - طلب جديد
  - قبول عرض
  - تغيير حالة الرحلة
  - إلغاء الرحلة
  - انقضاء البحث
- [ ] التحقق من إزالة token المنتهي أو غير المسجل

### 7) Android / Release Build
- [ ] إنشاء ملف keystore.properties بشكل آمن
- [ ] اختبار إصدار release الحقيقي: debug + release
- [ ] التأكد من أن [app/build.gradle.kts](app/build.gradle.kts) يقرأ التوقيع من ملف محلي وليس من المصدر
- [ ] اختبار تثبيت APK على أجهزة فعليّة
- [ ] التحقق من أن المهمة في [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml) تعمل على GitHub Actions

### 8) Real User Flow Testing
- [ ] تسجيل مستخدم جديد كـ customer
- [ ] إنشاء رحلة الآن
- [ ] مشاهدة البحث وتغير status إلى offered أو no_drivers
- [ ] قبول عرض من سائق
- [ ] تتبع تحديث حالة الرحلة
- [ ] تسجيل سائق جديد مع اعتماد المشرف
- [ ] قبول طلب الرحلة واستلام الموقع
- [ ] إنهاء الرحلة وتقييمها
- [ ] إلغاء رحلة قبل القبول واثناء الرحلة
- [ ] اختبار الحجز المسبق (scheduled)

### 9) Metrics and Production Monitoring
- [ ] تفعيل Firebase Crashlytics
- [ ] تفعيل Analytics
- [ ] تفعيل Performance Monitoring
- [ ] إعداد alerts على:
  - أعطال Cloud Functions
  - فشل dispatchScheduledRides
  - ارتفاع معدل فشل تسجيل الدخول
  - ارتفاع معدل إنهاء الرحلة غير الناجح
  - قضايا push notifications
- [ ] تضمين مؤشرات:
  - عدد الرحلات اليومي
  - معدل الانتهاء
  - متوسط الوقت حتى العرض الأول
  - نسبة السائقين الذين يلتزمون
  - معدل الإلغاء
  - معدل فشل التتبع الجغرافي

### 10) Production Safety Checks
- [ ] التأكد من أن كل القيم الحرجة تأتي من خادم موثوق وليس من العميل فقط
- [ ] التحقق من أن جميع التحديثات المهمة محمية عبر Firestore Rules
- [ ] التحقق من أن الحقول الحساسة مثل customerPhone، selectedDriverId، status ليست قابلة للتزوير من العميل
- [ ] التأكد من أن السائق لا يرسل موقعًا غير صحيح أو ما دون حدود العالم
- [ ] اختبار حالات الاستثناءات والـ null

## أولوية التنفيذ

### Priority A: لازم قبل الإطلاق
1. Firebase Auth + Phone + Firestore + Rules + Indexes
2. Functions deploy + Scheduler
3. FCM tests
4. Release signing
5. End-to-end ride flow test

### Priority B: مناسب للحادثة الأولى بعد الإطلاق
1. Crashlytics
2. Analytics
3. Performance monitoring
4. alerting
5. dashboards

## الخلاصة

المشروع في حالة بنائية جيدة جدًا، لكنه غير جاهز للإطلاق الإنتاجي إلا بعد اختبار حقيقي على Firebase حقيقي، مع تشغيل كامل لوظائف التطبيق، والاختبارات السلوكية لكل رحلة، والتحقق من المقاييس. هذا ما يميز المشروع الجاهز للإطلاق عن المشروع الذي يجمع فقط محليًا.
