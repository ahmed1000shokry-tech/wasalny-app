# نشر Backend وصلني

## 1) تسجيل Firebase CLI

من جهازك:

```bash
npm install -g firebase-tools
firebase login
```

## 2) من مجلد المشروع

```bash
firebase use wasalny-app-f5dbb
firebase deploy --only firestore:rules,firestore:indexes,functions
```

## 3) بناء APK

```bash
./gradlew assembleDebug
```

أو استخدم GitHub Actions، حيث يوجد Workflow لفحص Android وCloud Functions.

## مهم

Cloud Functions تحتاج Firebase/Google Cloud Billing على خطة Blaze. ضع Budget Alert قبل نشرها.
