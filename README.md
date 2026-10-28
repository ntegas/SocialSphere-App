# SocialSphere

A personal, offline-first relationship manager for Android: contacts, companies, events, reminders,
gift ideas and notes — stored only on your device. No server, no accounts, no ads, no analytics.

Languages: English and Greek (Ελληνικά), with Russian and more to follow.

- **Privacy policy:** https://ntegas.github.io/SocialSphere-App/ (sources: [English](PRIVACY_POLICY.md), [Ελληνικά](PRIVACY_POLICY_EL.md))
- **Stack:** Kotlin, Jetpack Compose, Room, Google Maps. minSdk 24, targetSdk 36.

## Build and run

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 36.0.0), or just Android Studio.

```bash
cd ss-v1
cp local.properties.example local.properties   # then set MAPS_API_KEY (use a restricted key)
./gradlew assembleDebug                         # OCR language data is downloaded on first build
./gradlew testDebugUnitTest                     # unit tests
```

The debug APK is written to `ss-v1/app/build/outputs/apk/debug/`. Without a Maps key the app works,
the map screen just shows no tiles.

## Release signing

Copy `ss-v1/keystore.properties.example` to `ss-v1/keystore.properties` and fill in your own keystore.
Both the properties file and any `.jks` / `.keystore` are git-ignored — never commit them.

---

# SocialSphere (Ελληνικά)

Προσωπική εφαρμογή διαχείρισης σχέσεων για Android που λειτουργεί κυρίως εκτός σύνδεσης: επαφές,
εταιρείες, εκδηλώσεις, υπενθυμίσεις, ιδέες δώρων και σημειώσεις — αποθηκευμένα μόνο στη συσκευή σας.
Χωρίς διακομιστή, λογαριασμούς, διαφημίσεις ή αναλυτικά στοιχεία. Οδηγίες κατασκευής: δείτε παραπάνω.
