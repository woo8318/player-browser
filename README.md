# Player Browser

URL 입력으로 웹을 탐색하고, **동영상에 제스처 컨트롤·즐겨찾기·방문 기록 표시** 기능을 더한 안드로이드 브라우저입니다.

## 주요 기능

1. **주소창 브라우저** — 검색어/URL 자동 인식 (Google 검색 폴백)
2. **동영상 제스처**
   - 한 손가락 좌/우 스와이프 → **-10초 / +10초 시킹**
   - 두 손가락 좌/우 스와이프 → **이전 / 다음 video 요소로 전환**
   - 동영상 영역 더블탭 → 재생/일시정지
3. **즐겨찾기** — 상단 북마크 아이콘으로 토글
4. **방문 기록 표시** — 즐겨찾기 목록의 방문한 URL 앞에 ✓ 표시 + 회색 처리
5. **방문 기록 페이지** — 메뉴 → 방문 기록에서 전체/개별 삭제 가능

## 갤럭시 S24에 설치하기

### 1. GitHub Actions로 APK 빌드
- 이 저장소를 GitHub에 push하면 `.github/workflows/android.yml` 워크플로우가 자동 실행됩니다.
- GitHub 저장소 → **Actions** 탭 → 최근 워크플로우 실행 → **Artifacts** 섹션의 `player-browser-debug-apk` 다운로드.
- 압축을 풀면 `app-debug.apk` 파일이 나옵니다.

### 2. S24에 설치
1. 다운로드한 `app-debug.apk`를 S24로 전송 (USB / 카카오톡 나에게 보내기 / Google Drive 등).
2. **설정 → 보안 및 개인정보 보호 → 출처를 알 수 없는 앱 설치** 에서 사용 중인 파일 관리자(또는 브라우저)에 권한 허용.
3. 파일 관리자에서 APK 탭 → 설치.
4. 첫 실행 시 보안 경고가 뜨면 "그래도 설치" 선택.

> ⚠️ 디버그 빌드는 디버그 키로 서명됩니다. 정식 배포는 release 빌드 + 자체 키스토어가 필요합니다.

## 로컬 빌드 (선택)

Android Studio Hedgehog 이상 + JDK 17 필요.

```bash
gradle wrapper --gradle-version 8.7 --distribution-type bin
./gradlew assembleDebug
# APK 위치: app/build/outputs/apk/debug/app-debug.apk
```

## 프로젝트 구조

```
app/src/main/
  AndroidManifest.xml
  assets/video_gestures.js        # 동영상 제스처 JS (WebView에 주입)
  java/com/playerbrowser/app/
    MainActivity.kt
    PlayerBrowserApp.kt
    data/                          # Room: Bookmark / HistoryEntry / DAO / Repo
    ui/                            # Compose 화면 (Browser / Bookmarks / History)
    web/                           # WebView 유틸 (URL 정규화, JS 로더)
.github/workflows/android.yml      # GitHub Actions APK 빌드
```

## 동영상 제스처 동작 원리

- WebView의 `onPageFinished`에서 `video_gestures.js`를 페이지에 주입합니다.
- 페이지의 `<video>` 요소 위에서 발생하는 터치 이벤트(`touchstart` / `touchmove` / `touchend`)를 가로채 스와이프 방향과 손가락 개수에 따라 `currentTime` 조작 또는 다음/이전 video로 전환합니다.
- 시킹/전환 시 화면 중앙 상단에 토스트가 잠시 표시됩니다.

YouTube처럼 자체 제스처를 가진 사이트에서는 일부 제스처가 사이트 자체 동작과 충돌할 수 있습니다. 그런 경우 일반 `<video>`를 직접 노출하는 사이트에서 가장 잘 작동합니다.
