# No Limit Music v1.7.2

무료 Android 음악 앱 **No Limit Music**의 소스 저장소입니다.

**공식 웹사이트:** https://nolimitmusic.pages.dev/  
**공식 웹사이트 공개 APK (현재 v1.6.9):** https://nolimitmusic.pages.dev/NoLimitMusic-v1.6.9.apk  
**FAQ:** https://nolimitmusic.pages.dev/faq.html  
**릴리스 정보:** https://nolimitmusic.pages.dev/releases/v1.6.9.html

> **광고 없는 재생**은 음악 재생 스트림 사이에 오디오 광고를 삽입하지 않는다는 뜻입니다. 검색·라이브러리·탐색 등 일부 앱 화면에는 광고가 표시될 수 있습니다.

> 다운로드·저장 기능은 본인이 권리를 보유했거나 다운로드 허가를 받은 콘텐츠에만 사용하세요. DRM 우회 기능은 포함하지 않습니다.

## 주요 기능

- 무료 설치 및 기본 사용
- 설정에서 광고 제거 코드를 적용하면 30일간 앱 배너·전면 광고 숨김
- 음악 재생 중 오디오 광고를 삽입하지 않는 재생 환경
- 음악 검색, YouTube 차트, AI DJ\n- YouTube Music 검색 실패 시 공개 검색 페이지로 자동 보완
- 최근 추가 / 좋아요 / 많이 재생한 곡 스마트 플레이리스트
- 사용자 플레이리스트 생성·편집·순서 변경
- 로컬 라이브러리, 재생 기록, 대기열, 백업
- 음악 인식 및 플레이리스트 스크린샷 OCR 가져오기
- Media3 백그라운드 재생과 홈 화면 위젯
- 다크 / 라이트 테마
- 홈 상단 제목을 N 브랜드 로고로 표시
- 기기 내 번역 모델 기반 **59개 언어 UI 선택**

## 설치 및 업데이트

공식 웹사이트에 현재 공개된 설치 파일은 v1.6.9입니다. v1.7.2 빌드는 GitHub Actions artifact로 먼저 생성됩니다.

https://nolimitmusic.pages.dev/NoLimitMusic-v1.6.9.apk

v1.6.9 APK SHA-256:

```text
7817bf1f3ea84f62140944e1287521b1c1cb59ecd55b67aaaac2737f55d38569
```

현재 빌드는 안정된 동일 서명 인증서를 유지하므로 최근 버전 사용자는 앱을 삭제하지 않고 덮어쓰기 업데이트할 수 있습니다.

## 검색·배포 메타데이터

공식 사이트는 검색엔진과 AI 검색 서비스의 발견을 돕기 위해 다음 공개 엔드포인트를 제공합니다.

- `/robots.txt`
- `/sitemap.xml`
- `/rss.xml`
- `/llms.txt`
- `/llms-full.txt`
- `/crawlers.json`
- `/releases/latest.json`

## 개발 환경

- minSdk 26 / targetSdk 36 / compileSdk 36
- Java 17
- Media3 1.11.0
- youtubedl-android 0.18.1 + FFmpeg
- ML Kit Translation / Text Recognition
- Start.io Android SDK

## 빌드

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions의 `Build debug APK` workflow는 테스트, APK 빌드, 안정된 서명 인증서 검증 후 artifact를 업로드합니다.

## 알려진 제한

- YouTube 동작은 사이트 변경, 지역/연령 제한, yt-dlp 추출기 상태에 영향을 받을 수 있습니다.
- 로그인 필요 콘텐츠나 DRM 콘텐츠를 우회하지 않습니다.
- 자동 UI 번역은 기기에서 처리되며 처음 선택한 언어의 번역 모델 다운로드가 필요할 수 있습니다.
- 음악 제목·아티스트·가사 같은 고유 콘텐츠는 자동 UI 번역 대상에서 제외됩니다.
