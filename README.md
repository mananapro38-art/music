# No Limit Music v0.5

Wi-Fi에서 YouTube를 검색하고 공식 앨범/오디오 후보를 우선 정렬해 저장하고, 로컬 플레이리스트와 Media3 백그라운드 플레이어로 재생하는 Android 테스트판입니다.

> 다운로드 기능은 본인이 권리를 보유하거나 다운로드 허가를 받은 콘텐츠에만 사용하세요. DRM 우회 기능은 포함하지 않습니다.

## v0.5 주요 기능

- 공식 앨범/Topic/Official Audio 우선 음악 검색
- Wi-Fi에서 오디오 저장, yt-dlp client fallback + FFmpeg fallback
- 대한민국 YouTube 주간 Top Songs 홈 차트
- 최근 추가 / 좋아요 / 많이 재생한 곡 스마트 플레이리스트
- 여러 사용자 플레이리스트 생성, 이름 변경, 곡 추가/제거, 드래그 순서 변경
- 플레이리스트 자동재생
- MediaSessionService 백그라운드 재생 및 알림/잠금화면 이전·재생·일시정지·다음 제어
- 홈 화면 음악 위젯: 현재 곡, 이전, 재생·일시정지, 다음
- 작고 반투명한 플로팅 하단 내비게이션: 홈 / 검색 / 플리 / 설정
- 다크/라이트 테마에 맞춰 하단 바와 위젯 색상 자동 변경
- 플레이리스트 JSON 백업/복원: 사용자가 선택한 공유 저장소에 보관
- 복원 후 음악 파일이 없으면 곡을 탭해 같은 YouTube ID로 다시 저장

## 업데이트와 데이터 유지

v0.5부터 GitHub Actions 테스트 빌드는 `no-limit-music-debug-keystore-v1` 캐시의 동일 debug 서명키를 재사용합니다. 따라서 v0.5를 기준으로 이후 테스트 APK가 같은 키로 빌드되면 앱을 삭제하지 않고 덮어쓰기 업데이트할 수 있고 앱 내부 플레이리스트/좋아요/재생횟수도 그대로 유지됩니다.

v0.4.1 이하 APK는 과거 임시 debug 인증서로 서명됐기 때문에 v0.5로 넘어오는 최초 1회에는 기존 앱 삭제가 필요할 수 있습니다. 삭제에 대비해 v0.5부터 설정 > 플레이리스트 백업에서 JSON을 공유 저장소로 내보내고 복원할 수 있습니다.

현재 음악 파일 자체는 앱 전용 Music 디렉터리에 저장되므로 앱을 완전히 삭제하면 사라질 수 있습니다. 백업은 플레이리스트 구조/곡 메타데이터/좋아요/재생횟수를 보존하며, 사라진 음원은 복원된 곡을 눌러 다시 저장합니다. 향후 MediaStore 공개 Music 저장으로 옮기면 음원 파일 자체도 앱 제거와 분리할 수 있습니다.

## 개발 환경

- minSdk 26 / targetSdk 36 / compileSdk 36
- Android Gradle Plugin 8.13.2
- Java 17
- Media3 1.11.0
- youtubedl-android 0.18.1 + FFmpeg

## 빌드

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions의 `Build debug APK` workflow도 같은 테스트를 수행하고 APK artifact를 업로드합니다.

## 알려진 제한

- YouTube 동작은 사이트 변경, 지역/연령 제한, yt-dlp 추출기 상태에 영향을 받습니다.
- 로그인 필요 콘텐츠나 DRM 콘텐츠를 우회하지 않습니다.
- YouTube Charts는 공개 웹 내부 응답 구조에 의존하므로 YouTube 변경 시 별도 패치가 필요할 수 있습니다.
- 홈 화면 위젯의 재생 제어는 MediaSessionService가 실행 중이고 재생 큐가 준비된 상태에서 가장 안정적입니다.
- 공개 배포 전에는 youtubedl-android/yt-dlp/FFmpeg 라이선스와 배포 정책을 별도로 검토해야 합니다.
