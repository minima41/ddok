# APK 빌드

GitHub Actions의 `Build Android APK` 워크플로가 다음을 수행합니다.

- JDK 17 설정
- Gradle 8.9 설정
- `testDebugUnitTest` 실행
- `:app:assembleDebug` 실행
- `ddok-debug.apk` 아티팩트 업로드

Actions 탭의 성공한 실행에서 `ddok-debug-apk` 아티팩트를 내려받아 설치하면 됩니다.
