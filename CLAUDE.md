# CLAUDE.md

Player Browser — Android WebView 기반 브라우저. URL 탐색 + 동영상 제스처 컨트롤 + 즐겨찾기/방문기록 + 멀티탭(그룹/멀티선택/부모복귀/카드메뉴 그룹 이동) + 광고 차단 + 쿠키 동의 배너 자동 거부 + SNI 우회 + Chromecast + 자체 업데이트 + 크래시 로깅. 현재 버전: v1.3.20.

## 빌드 / 배포

- **빌드 시스템:** Gradle 8.7 + Kotlin 1.9.24 + AGP 8.5.2, JDK 17
- **타깃:** `compileSdk=34`, `minSdk=26`, `targetSdk=34`
- **앱 ID:** `com.playerbrowser.app` (debug는 `.debug` suffix)
- **버전 관리:** `app/build.gradle.kts`의 `versionCode` / `versionName` 직접 수정
- **로컬 빌드:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **CI:** push 시 GitHub Actions가 debug APK 빌드, `v*` 태그 push 시 GitHub Release 생성 + 최신 3개 릴리스만 유지
- **로컬 wrapper 없음:** CI가 `gradle wrapper --gradle-version 8.7`로 생성. 로컬에서 처음 빌드할 때도 동일 명령 필요할 수 있음

## 디렉터리 구조

```
app/src/main/
  assets/video_gestures.js              # WebView에 주입되는 제스처 JS
  java/com/playerbrowser/app/
    MainActivity.kt                     # 단일 액티비티 + Compose, CastSessionBridge attach/detach
    PlayerBrowserApp.kt                 # Application — CrashRecorder.install / Proxy / SNI+AdBlock 옵저버 / Cast init
    cast/                               # Chromecast (mediarouter + play-services-cast)
      CastOptionsProvider.kt
      CastSessionBridge.kt
      VideoStreamSniffer.kt             # 페이지 내 video URL 추출 (.m3u8 / .mp4 / .webm)
    data/                               # Room DB + 세션 영속화
      AppDatabase.kt
      Bookmark.kt / BookmarkDao.kt
      HistoryEntry.kt / HistoryDao.kt
      BrowserRepository.kt
      TabPersistence.kt                 # 멀티탭 세션 영속화 (tabs + parent + groups, JSON in SharedPreferences)
    network/                            # 네트워크 인터셉트 / 진단
      AdBlocker.kt / AdBlockSwitch.kt   # 광고 차단 (host suffix + URL pattern, 빈 204 응답)
      CookieBannerKiller.kt             # 쿠키 동의 배너 자동 거부 JS (OneTrust/Cookiebot/Quantcast/Didomi/Sourcepoint/TrustArc)
      CookieBannerSwitch.kt             # 볼륨 토글 (volatile, hot path)
      DohClient.kt                      # DNS-over-HTTPS (Cloudflare)
      FragmentingSocketFactory.kt       # TLS ClientHello 조각화
      SniBypassClient.kt / SniBypassSwitch.kt
      ProxyManager.kt                   # HTTP/HTTPS 프록시 (WebView Proxy API)
      NetworkSettings.kt / NetworkSettingsRepository.kt
      CrashRecorder.kt                  # 충돌 영속화 (filesDir/crashes/)
      DebugLog.kt                       # 인앱 로그 링버퍼 (500개)
    ui/                                 # Jetpack Compose 화면
      RootNavigation.kt                 # navigation-compose 그래프
      BrowserScreen.kt                  # 상단(주소+즐겨찾기+Cast+메뉴) + 중앙(WebView) + 하단(뒤로/앞/새로고침/홈/탭)
      BrowserWebView.kt                 # WebViewClient / WebChromeClient / GestureCapturingFrame
      BrowserViewModel.kt               # 탭 + 그룹 + 부모-자식 + isCurrentBookmarked + 업데이트 상태
      TabSwitcher.kt                    # 탭 스위처 오버레이 (그룹 섹션 + 길게 누름 멀티 선택 + 카드 ⋮ 메뉴 1탭 이동 + 일괄 닫기/이동)
      BookmarksScreen.kt / HistoryScreen.kt
      SettingsScreen.kt / SettingsViewModel.kt
      DebugLogScreen.kt                 # 로그 + 충돌 기록 뷰어 (확장/복사/삭제)
      UpdateDialog.kt
      ErrorPage.kt
      theme/
    update/                             # 자체 업데이트 (GitHub Releases)
      UpdateClient.kt / UpdateInstaller.kt / UpdateModels.kt / Version.kt
    web/
      UrlUtils.kt                       # URL/검색어 판별 + 정규화
      WebAssetLoader.kt                 # assets/JS 로딩
      IframeScriptInjector.kt           # cross-origin iframe HTML에 JS prepend
```

## 핵심 아키텍처 메모

- **단일 액티비티 + Compose:** `MainActivity` → `RootNavigation` → 화면별 Composable. `MainActivity`는 `CastSessionBridge` lifecycle만 관리.
- **상태 관리:** `BrowserViewModel` (탭 / 그룹 / 현재 URL / 제목 / 즐겨찾기 / 업데이트 상태), `SettingsViewModel` (네트워크 설정)
- **WebView:** `buildBrowserWebView()`에서 단건 생성. `WebViewClient.shouldInterceptRequest`가 **AdBlocker → VideoStreamSniffer → SniBypassClient → IframeScriptInjector** 순서로 체이닝. `onPageFinished`에서 `video_gestures.js` + (광고차단 켜져있으면) `AdBlocker.HIDE_CSS_JS` + (쿠키배너 켜져있으면) `CookieBannerKiller.SCRIPT` inject.
- **멀티탭:** 탭 전환 시 WebView 자체를 swap (단일 WebView 재사용 X). `SnapshotStateMap<TabId, BrowserWebViewState>`로 보관. 탭 닫히면 `webView.destroy()`로 GC.
- **탭 그룹 / 부모-자식:** `TabState`에 `groupId` + `parentTabId` 필드. `TabGroup`(id, name, color)은 `BrowserViewModel._groups` StateFlow + `TabPersistence`의 JSON에 함께 저장 (Room 마이그레이션 비용 회피). 그룹 삭제 시 탭은 살아남고 `groupId`만 null로 떨어짐.
- **target=_blank / window.open → 부모 탭 복귀:** `WebChromeClient.onCreateWindow`가 throwaway WebView로 URL만 뽑아 `WebViewCallbacks.onOpenInNewTab(url)` 콜백 → `BrowserScreen`이 `viewModel.newTab(url, parentTabId = ownerId)`로 새 탭 생성 (부모의 `groupId`도 자동 상속). 자식 탭에서 뒤로가기 → 자체 history가 없으면 `viewModel.tryReturnToParent()`로 부모 탭으로 복귀 + 자식 탭 닫기. 부모 탭이 이미 사라진 자식은 `closeTabs`에서 `parentTabId`를 null로 정리해 댕글링 방지.
- **탭 스위처 멀티 선택 + 카드 메뉴 1탭 이동:** `TabSwitcher.kt`의 `TabSwitcherOverlay`(internal). `combinedClickable`(ExperimentalFoundationApi) 길게 누름 → `selectedIds` Set에 추가 → `derivedStateOf`로 선택 모드 진입. 선택 모드에서는 상단 액션 바가 morph(전체 / 그룹으로 이동 / 일괄 닫기). BackHandler가 선택 모드면 선택 해제, 아니면 오버레이 dismiss. 그룹별 섹션은 `LazyVerticalGrid` + `GridItemSpan(maxLineSpan)` 헤더로 렌더. 단일 탭 이동은 카드 우측 ⋮ 메뉴 ("그룹으로 이동..." / "그룹에서 빼기") 로 멀티 선택 없이 처리 — `pendingMoveTargets: List<String>?` 상태가 단일/멀티 양쪽 흐름을 통일해 `GroupPickerDialog`/`NewGroupDialog`(create-then-move 원자 처리) 한 벌을 공유. 카드 메뉴는 `inSelectMode == false` 일 때만 노출해 선택 모드 액션과 충돌하지 않음.
- **iframe 처리:** `IframeScriptInjector`가 cross-origin iframe HTML 응답에 `<script>`를 prepend해 제스처 JS 주입.
- **광고 차단 (`AdBlocker`):** 약 50개 광고/트래커 host suffix + URL 패턴 매칭으로 빈 204 반환. 메인 프레임은 절대 차단 안 함. CSS 셀렉터로 same-domain 광고 슬롯 숨김. `AdBlockSwitch.enabled` volatile 플래그 (hot path).
- **쿠키 동의 배너 자동 거부 (`CookieBannerKiller`):** OneTrust / Cookiebot / Quantcast(TCF) / Didomi / Sourcepoint / TrustArc / Google 컨센트의 "Reject All" 버튼 셀렉터 리스트를 들고 400ms 간격으로 최대 10회 폴링 클릭. 동시에 컨센트 컨테이너를 `display:none` + `html,body{overflow:auto}`로 CSS 숨김 (배너 dismiss 실패 시 콘텐츠/스크롤 락 같이 풀어주는 fallback). `window.__pbCookieKill` 플래그로 idempotent — 매 `onPageFinished`에서 재주입돼도 1회만 동작. `CookieBannerSwitch.enabled` volatile 플래그 (hot path).
- **SNI 우회:** `SniBypassClient` + `FragmentingSocketFactory`가 TLS 레코드 레이어에서 ClientHello를 분할. `DohClient`로 DNS도 우회. `SniBypassSwitch.enabled` volatile 플래그.
- **Chromecast:** `CastSessionBridge`를 액티비티 lifecycle에 attach/detach. 세션 시작 시 `VideoStreamSniffer.current(host)`로 현재 페이지의 캐스트 가능한 URL 조회 → `RemoteMediaClient.load()`.
- **풀스크린 동영상:** `WebChromeClient.onShowCustomView`에서 `GestureCapturingFrame`(FrameLayout)으로 감싸 `dispatchTouchEvent`로 제스처 캡처 + 화면 회전/풀스크린/세로 드래그 처리. `onHideCustomView`에서 brightness 복원.
- **크래시 로깅:** `PlayerBrowserApp.onCreate` 가장 먼저 `CrashRecorder.install(this)`. `Thread.setDefaultUncaughtExceptionHandler` 체이닝으로 시스템 다이얼로그는 그대로 유지. 추가로 `WebViewClient.onRenderProcessGone`이 `true` 반환해 앱 살아남기 + 에러 페이지 + `CrashRecorder.record()`.
- **자체 업데이트:** GitHub Releases API 조회 → APK 다운로드 → `FileProvider`로 설치 인텐트.

## 동영상 제스처 (`video_gestures.js`)

- 1 손가락 좌/우 스와이프 → -10/+10초 시킹
- 2 손가락 좌/우 스와이프 → 이전/다음 `<video>` 전환
- 양쪽 가장자리 더블탭 → 사이드 인식 더블탭 시킹 (좌 -10, 중앙 토글, 우 +10)
- 수평 드래그 → 정밀 시킹 (드래그 양에 비례)
- 풀스크린 세로 드래그 → 좌측 화면밝기 / 우측 시스템 볼륨 (`window.__pb.showVbOverlay` 호출)
- iframe 내부: `IframeScriptInjector`가 cross-origin iframe HTML에도 주입

수정 후에는 실제 페이지(YouTube, 일반 `<video>` 사이트)에서 테스트 필요. JS는 페이지마다 다른 player 구현과 부딪힐 수 있음.

## 종속성 메모

- Compose BOM `2024.06.00`
- Room `2.6.1` (KSP)
- androidx.appcompat `1.7.0` (MediaRouteButton 호환용)
- androidx.mediarouter `1.7.0` + play-services-cast-framework `21.5.0`
- androidx.webkit `1.11.0`
- OkHttp `4.12.0` (SNI 우회 / 업데이트 클라이언트)
- DataStore Preferences `1.1.1`

## 검증 / 수동 테스트

- **테스트 인프라 없음** — 단위/E2E 테스트 디렉터리가 비어있음. 변경사항은 실제 기기(S24가 타깃)에서 빌드/설치 후 확인이 기본 검증
- 빌드 검증: `./gradlew assembleDebug` 성공 여부 (로컬 wrapper 없으면 CI가 PR/푸시에서 검증)
- 기능 검증은 사용자가 S24에 APK 설치 후 직접 진행
- 충돌 진단: 메뉴 → 설정 → 디버그 로그 상단의 "충돌 기록" 섹션에서 스택트레이스 확인/복사 (`filesDir/crashes/`에 영구 저장)

## 작업 시 주의사항

- **버전 bump:** 기능 추가/수정 commit과 함께 `versionCode`/`versionName` 증가 — 자체 업데이트 동작에 필수
- **commit 메시지 컨벤션:** `feat(scope):` / `fix(scope):` 형태. scope는 `ui`, `player`, `sni`, `adblock`, `cookies`, `cast`, `tabs`, `debug` 등
- **태그 푸시:** `v1.3.x` 태그 push로 GitHub Release + APK 자동 발행. CI가 오래된 릴리스 자동 정리 (최신 3개 유지)
- **문서 동기화:** 기능 추가/제거 시 `README.md`의 "주요 기능" 섹션과 본 파일의 "디렉터리 구조" / "핵심 아키텍처 메모"를 같은 commit에 묶기
- **JS 자산 변경:** `app/src/main/assets/video_gestures.js`는 WebView 컨텍스트에서 실행 — `console.log`는 Chrome devtools 또는 `chrome://inspect`에서만 보임
- **SNI 우회 작업:** `DebugLog` + `DebugLogScreen`으로 인앱 진단 가능. KT/SKT의 DPI 정책이 자주 바뀌므로 fragmentation 파라미터 조정이 필요할 수 있음
- **광고 차단 작업:** 새 광고 도메인은 `AdBlocker.BLOCKED_HOSTS`에 host suffix로 추가 (substring이 아니라 endsWith `.entry` 매칭이므로 false positive 위험 낮음). 사이트가 광고차단 감지로 콘텐츠를 막으면 설정 토글로 우회.
- **쿠키 배너 작업:** 새 컨센트 플랫폼 셀렉터는 `CookieBannerKiller.SCRIPT`의 `REJECT_SELECTORS` / `HIDE_SELECTORS`에 추가. CSS 와일드카드 셀렉터(`.sp_message_container_*`)는 `document.querySelector`가 throw할 수 있어 try/catch로 감싸져 있음 — 새 셀렉터도 같은 패턴 유지. 사이트가 배너 동작에 깊게 의존(예: 동의 후에만 video 로드)하면 설정 토글로 우회.
- **탭 그룹 / 부모 관계 작업:** `TabState.parentTabId`/`groupId`와 `TabGroup`은 `TabPersistence` JSON에 함께 저장 — 스키마 추가 시 `optString().ifBlank { null }` 패턴으로 backward-compatible 로드 유지. 그룹 삭제 시 탭은 살아남고 `groupId`만 null로 떨어지는 정책은 사용자 실수 보호용이므로 임의로 바꾸지 말 것. `closeTabs`의 orphan sanitization(부모가 닫힌 자식의 `parentTabId` null화)은 댕글링 BackHandler 방지에 필수.
- **인터셉트 체인 순서:** `shouldInterceptRequest`에서 `AdBlocker → VideoStreamSniffer.observe → SniBypassClient.intercept → IframeScriptInjector.process` 순. AdBlock이 가장 먼저 (cheapest, 빈 204로 짧게 끝남).
- **크래시 핸들러 수정 금지:** `CrashRecorder.install`은 기존 시스템 핸들러를 체이닝하므로 절대 끊지 말 것. WebView `onRenderProcessGone`은 반드시 `true` 반환 (false면 앱 종료).
