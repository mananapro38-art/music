# No Limit Music v0.1

Wi-Fi에서 YouTube를 검색하고, 결과를 **공식 앨범/오디오 후보 우선**으로 재정렬한 뒤, 사용자가 선택한 콘텐츠의 원본 오디오 스트림을 앱 전용 저장소에 저장하고 로컬 플레이리스트에서 재생하는 Android 테스트판입니다.

> 다운로드 기능은 본인이 권리를 보유하거나 다운로드 허가를 받은 콘텐츠에만 사용하세요. DRM 우회 기능은 포함하지 않습니다.

## v0.1에서 되는 것

- `ytsearch25:` 기반 YouTube 검색
- `- Topic`, `Official Audio` 가점 / MV, Live, Cover, Shorts 감점 랭커
- 검색 결과에서 **공식 앨범 음원 후보** 배지 표시
- Wi-Fi 연결 상태에서만 저장 시작
- QuickJS + yt-dlp EJS remote component 사용
- `bestaudio` 원본 스트림 우선 저장 (FFmpeg 재인코딩 없음)
- 다운로드 진행률 표시
- 완료 즉시 `내 플레이리스트` 자동 추가
- Media3 ExoPlayer 로컬 재생 / 재생·일시정지
- 플레이리스트 영속 저장(SharedPreferences JSON)
- 로컬 곡 삭제

## 의도적으로 v0.1에서 뺀 것

- FFmpeg 변환/MP3 강제 변환
- 앨범아트 캐시 및 ID3/MP4 태그 쓰기
- 여러 개의 사용자 플레이리스트
- 백그라운드 MediaSession/잠금화면 컨트롤
- Wi-Fi가 돌아왔을 때 자동 재개하는 다운로드 큐
- YouTube 로그인/쿠키 가져오기

## 엔진 버전 정책

이 테스트판은 `youtubedl-android 0.18.1`이 제공하는 런타임을 그대로 사용합니다. 앱에서 yt-dlp를 최신 stable로 무조건 자동 업데이트하지 않습니다.

이유는 Android 래퍼가 번들 Python 3.8 런타임을 문서화하는 반면, 최신 yt-dlp는 Python 3.10+를 요구하기 때문입니다. 런타임보다 yt-dlp만 앞서 업데이트하면 시작 자체가 깨질 수 있어 v0.1에서는 버전을 고정했습니다. 대신 YouTube 추출에 필요한 JS 처리는 래퍼의 QuickJS와 `--remote-components ejs:github`를 사용하도록 요청합니다.

## 개발 환경

- minSdk 26 / targetSdk 36 / compileSdk 36
- Android Gradle Plugin 8.13.2
- Java 17
- Media3 1.11.0
- youtubedl-android 0.18.1

## Android Studio에서 빌드

이 압축에는 Gradle Wrapper binary(`gradle-wrapper.jar`)가 포함되지 않습니다. Android Studio에서 프로젝트를 열고 Gradle JDK를 17로 지정한 뒤 Sync/Build하면 됩니다. 로컬 Gradle 8.13이 있다면:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

APK 생성 위치:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions에서 APK 만들기

`.github/workflows/build-apk.yml`을 포함했습니다. 이 프로젝트를 GitHub 저장소에 올린 뒤 **Actions → Build debug APK → Run workflow**를 실행하면 unit test 후 debug APK가 artifact로 올라옵니다.

## 테스트 순서

1. Wi-Fi에 연결합니다.
2. 앱을 실행하고 `음악 엔진 준비됨 · QuickJS/EJS · Wi‑Fi에서만 저장`을 확인합니다.
3. `아이유 밤편지`처럼 `아티스트 + 곡명`을 검색합니다.
4. `- Topic`/Official Audio 후보가 MV/Live보다 위에 오는지 확인합니다.
5. 원하는 검색 결과를 누릅니다.
6. 저장 진행률이 올라가는지 확인합니다.
7. 완료 후 `내 플레이리스트`에 자동 추가되고 바로 재생되는지 확인합니다.
8. Wi-Fi를 끄고 다른 곡을 누르면 저장을 차단하는지 확인합니다.

## 저장 위치

v0.1은 별도 저장 권한 없이 테스트하기 위해 Android 앱 전용 Music 디렉터리 아래 `NoLimitMusic`에 저장합니다. 앱을 삭제하면 파일도 함께 지워질 수 있습니다. 다음 버전에서 MediaStore/사용자 선택 폴더 저장을 붙이는 것이 좋습니다.

## 알려진 제한

- 실제 YouTube 동작은 사이트 변경, 지역/연령 제한, yt-dlp 추출기 상태에 영향을 받습니다.
- 로그인 필요 콘텐츠나 DRM 콘텐츠를 우회하지 않습니다.
- 현재 랭킹은 메타데이터 기반 heuristic이므로 모든 곡에서 앨범 트랙을 100% 판별하지는 못합니다.
- v0.1은 Activity 내부 플레이어이므로 완전한 음악 앱 수준의 백그라운드 서비스/잠금화면 컨트롤은 아직 없습니다.
- youtubedl-android는 GPL-3.0 계열 라이선스 의무가 있으므로 공개 배포 전에 라이선스 검토가 필요합니다.
