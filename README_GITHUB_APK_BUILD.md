# وصلني — بناء APK من GitHub

## قبل البناء

1. تأكد من إعداد Firebase وFirestore حسب [FIRESTORE_SETUP_AR.md](FIRESTORE_SETUP_AR.md).
2. يستخدم التطبيق OpenStreetMap، ولا يحتاج مفتاح Google Maps؛ يلزم اتصال إنترنت لعرض الخريطة.

## البناء والتنزيل

ادفع إلى `main` أو شغّل **Build Wasalny APK** يدوياً من Actions. بعد نجاح البناء نزّل artifact باسم `Wasalny-APK`.

يتطلب البناء JDK 17 وGradle Wrapper 8.7. أقل إصدار مدعوم Android 7 (API 24).


## أمان التوقيع
لا ترفع `keystore.properties` أو أي ملف `.jks`/`.keystore` إلى GitHub.
بناء Debug يعمل بدون مفتاح توقيع مخصص. ولإخراج Release موقّع، استخدم Secrets في GitHub أو ملف `keystore.properties` محليًا.
