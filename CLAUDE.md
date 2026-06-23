# CLAUDE.md

Player Browser — Android WebView 기반 브라우저. URL 탐색 + 동영상 제스처 컨트롤 + 동영상 이어보기 + 즐겨찾기/방문기록 + 멀티탭(썸네일 갤러리/그룹/그룹순서변경/멀티선택/부모복귀/카드메뉴 그룹 이동/드래그 그룹 편집/바 스와이프 탭 전환) + 광고 차단 + 쿠키 동의 배너 자동 거부 + SNI 우회 + 프라이빗 DNS(DoH) + URL 숫자 자동/수동 복구 + 링크 항상 새 탭 열기 + Chromecast + 자체 업데이트 + 크래시 로깅. 현재 버전: v1.3.38.

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
      WatchProgressStore.kt             # 동영상 이어보기 위치 영속화 (page URL → pos/dur/title, JSON in SharedPreferences)
    network/                            # 네트워크 인터셉트 / 진단
      AdBlocker.kt / AdBlockSwitch.kt   # 광고 차단 (host suffix + URL pattern, 빈 204 응답)
      CookieBannerKiller.kt             # 쿠키 동의 배너 자동 거부 JS (OneTrust/Cookiebot/Quantcast/Didomi/Sourcepoint/TrustArc)
      CookieBannerSwitch.kt             # 볼륨 토글 (volatile, hot path)
      DohClient.kt                      # DNS-over-HTTPS (RFC 8484 wire-format, 제공자 설정 가능)
      DohProvider.kt                    # DoH 제공자 프리셋(Cloudflare/Google/Quad9/AdGuard) + 커스텀 URL
      PrivateDnsSwitch.kt               # 프라이빗 DNS 토글 + 현재 DoH URL (volatile, hot path)
      FragmentingSocketFactory.kt       # TLS ClientHello 조각화
      SniBypassClient.kt / SniBypassSwitch.kt
      ProxyManager.kt                   # HTTP/HTTPS 프록시 (WebView Proxy API)
      ResumeSwitch.kt                   # 이어보기 토글 (volatile, JS 브리지 hot path)
      UrlRecovery.kt                    # URL 숫자 자동 복구 (접속 실패 시 도메인/경로 숫자 증감 후보 프로브 → 자동 이동)
      NetworkSettings.kt / NetworkSettingsRepository.kt
      CrashRecorder.kt                  # 충돌 영속화 (filesDir/crashes/)
      DebugLog.kt                       # 인앱 로그 링버퍼 (500개)
    ui/                                 # Jetpack Compose 화면
      RootNavigation.kt                 # navigation-compose 그래프
      BrowserScreen.kt                  # 상단(주소+즐겨찾기+Cast+메뉴) + 중앙(WebView) + 하단(뒤로/앞/새로고침/홈/탭)
      BrowserWebView.kt                 # WebViewClient / WebChromeClient / GestureCapturingFrame
      BrowserViewModel.kt               # 탭 + 그룹 + 부모-자식 + isCurrentBookmarked + 업데이트 상태
      TabSwitcher.kt                    # 탭 갤러리/스위처 오버레이 (썸네일 카드 + 그룹 섹션(색상 칩+개수 배지) + 그룹 헤더 ⋮ 메뉴 순서 변경(위/아래) + 길게 누름 멀티 선택 + 카드 ⋮ 메뉴 1탭 이동 + 일괄 닫기/이동 + 드래그 그룹 편집)
      TabThumbnailStore.kt              # 탭별 WebView 썸네일 메모리 캐시 (갤러리 카드 미리보기, 프로세스 종료 시 휘발)
      TabDragAndDrop.kt                 # 탭 카드 드래그 → 그룹 드롭 (TabDragState + tabDragSource/tabDropRegion/tabDropTargetRoot)
      TabSwipeGesture.kt                # Modifier.tabSwitchSwipe — 상/하단 바 수평 스와이프 → 인접 탭 전환
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
      ResumeBridge.kt                   # `window.PBResume` @JavascriptInterface — 이어보기 위치 save/load/clear
```

## 핵심 아키텍처 메모

- **단일 액티비티 + Compose:** `MainActivity` → `RootNavigation` → 화면별 Composable. `MainActivity`는 `CastSessionBridge` lifecycle만 관리.
- **상태 관리:** `BrowserViewModel` (탭 / 그룹 / 현재 URL / 제목 / 즐겨찾기 / 업데이트 상태), `SettingsViewModel` (네트워크 설정)
- **WebView:** `buildBrowserWebView()`에서 단건 생성. `WebViewClient.shouldInterceptRequest`가 **AdBlocker → VideoStreamSniffer → SniBypassClient → IframeScriptInjector** 순서로 체이닝. `onPageFinished`에서 `video_gestures.js` + (광고차단 켜져있으면) `AdBlocker.HIDE_CSS_JS` + (쿠키배너 켜져있으면) `CookieBannerKiller.SCRIPT` inject.
- **멀티탭:** 탭 전환 시 WebView 자체를 swap (단일 WebView 재사용 X). `SnapshotStateMap<TabId, BrowserWebViewState>`로 보관. 탭 닫히면 `webView.destroy()`로 GC.
- **바 스와이프 탭 전환:** `TabSwipeGesture.kt`의 `Modifier.tabSwitchSwipe`를 상단 주소창 `Surface`와 하단 내비 `Row`에 적용. `detectHorizontalDragGestures`(수평 바이어스)라 탭/세로 스크롤/텍스트필드 포커스와 충돌 안 함 — 수평 의도가 확정된 뒤에만 엔게이지. 누적 거리가 touchSlop*3을 넘는 순간 드래그당 1회만 발화(즉각 반응). `viewModel.selectAdjacentTab(forward)`가 실제 전환된 경우만 `true` 반환 → 그때만 햅틱. 좌 스와이프=다음 탭(forward), 우 스와이프=이전 탭. **이전(backward) 방향은 부모 탭 우선** — 현재 탭이 `window.open`/`target=_blank`로 열린 자식(`parentTabId` 생존)이면 인접 탭 대신 부모 탭으로 복귀(back 제스처와 동일 멘탈모델), 부모가 없으면 평탄한 `_tabs` 순서 인접 탭(양 끝 멈춤, no-wrap).
- **탭 전환 슬라이드 애니메이션:** 중앙 WebView 호스트를 `AnimatedContent(targetState = activeTabId)`로 감싸 스와이프 시 콘텐츠가 가로로 슬라이드(`slideIn/OutHorizontally`, tween 260ms). `switchDirection`(+1/-1/0) 상태로 방향 결정 — **스와이프만 애니메이션을 arm**하고 `LaunchedEffect(activeTabId)`가 매 전환 후 0으로 disarm해서 탭 닫기/스위처 선택은 무애니메이션 즉시 swap. 이유: 닫힌(=`webStates` GC로 `destroy()`된) 탭을 슬라이드 아웃하면 파괴된 WebView를 참조할 수 있음. 전환 콘텐츠는 target=현재 active면 `activeWebState`, 빠져나가는 탭은 `webStates[id]`를 **생성 없이** 조회(없으면 빈 Box)해 죽은 탭 부활/크래시 방지.
- **탭 그룹 / 부모-자식:** `TabState`에 `groupId` + `parentTabId` 필드. `TabGroup`(id, name, color)은 `BrowserViewModel._groups` StateFlow + `TabPersistence`의 JSON에 함께 저장 (Room 마이그레이션 비용 회피). 그룹 삭제 시 탭은 살아남고 `groupId`만 null로 떨어짐. **그룹 순서 = `_groups` 리스트 순서**(스위처 섹션 렌더/JSON 영속화 모두 이 순서를 따름) — 그룹 헤더 ⋮ 메뉴의 "위로 이동"/"아래로 이동"이 `viewModel.moveGroup(id, up)`으로 인접 항목을 스왑. 양 끝에서는 해당 메뉴 항목을 숨김.
- **target=_blank / window.open → 부모 탭 복귀:** `WebChromeClient.onCreateWindow`가 throwaway WebView로 URL만 뽑아 `WebViewCallbacks.onOpenInNewTab(url)` 콜백 → `BrowserScreen`이 `viewModel.newTab(url, parentTabId = ownerId)`로 새 탭 생성 (부모의 `groupId`도 자동 상속). 자식 탭에서 뒤로가기 → 자체 history가 없으면 `viewModel.tryReturnToParent()`로 부모 탭으로 복귀 + 자식 탭 닫기. 부모 탭이 이미 사라진 자식은 `closeTabs`에서 `parentTabId`를 null로 정리해 댕글링 방지.
- **링크를 항상 새 탭에서 열기 (설정 토글, 기본 off):** target=_blank/window.open이 아닌 **일반 링크 탭**도 현재 탭을 바꾸지 않고 새 탭에서 연다. `WebViewClient.shouldOverrideUrlLoading`이 `LinkNewTabSwitch.enabled`면 `request.isForMainFrame && hasGesture() && !isRedirect` 이고 http/https인 내비게이션을 가로채 `callbacks.onOpenInNewTab(url)`(부모 탭 자식으로 생성 — 위 메모와 동일, 뒤로 가기로 복귀)로 넘기고 `true` 반환. 게이트 조건이 핵심 — 주소창 입력(`loadUrl`은 이 콜백을 안 거침)·서버 리다이렉트·제스처 없는 JS 내비게이션은 제자리 로드 유지. 설정값은 `NetworkSettings.openLinksInNewTab` → `LinkNewTabSwitch` volatile 스위치(`shouldOverrideUrlLoading` hot path라 DataStore suspend 회피, `PlayerBrowserApp` 옵저버가 미러).
- **탭 스위처 멀티 선택 + 카드 메뉴 1탭 이동:** `TabSwitcher.kt`의 `TabSwitcherOverlay`(internal). `combinedClickable`(ExperimentalFoundationApi) 길게 누름 → `selectedIds` Set에 추가 → `derivedStateOf`로 선택 모드 진입. 선택 모드에서는 상단 액션 바가 morph(전체 / 그룹으로 이동 / 일괄 닫기). BackHandler가 선택 모드면 선택 해제, 아니면 오버레이 dismiss. 그룹별 섹션은 `LazyVerticalGrid` + `GridItemSpan(maxLineSpan)` 헤더로 렌더. 단일 탭 이동은 카드 우측 ⋮ 메뉴 ("그룹으로 이동..." / "그룹에서 빼기") 로 멀티 선택 없이 처리 — `pendingMoveTargets: List<String>?` 상태가 단일/멀티 양쪽 흐름을 통일해 `GroupPickerDialog`/`NewGroupDialog`(create-then-move 원자 처리) 한 벌을 공유. 카드 메뉴는 `inSelectMode == false` 일 때만 노출해 선택 모드 액션과 충돌하지 않음. **열 때 활성 탭으로 자동 스크롤** — 오버레이는 열릴 때마다 새로 composition되므로 `LaunchedEffect(Unit)`에서 `activeTabLazyIndex()`(섹션 헤더 span + 탭 카드 emit 순서를 그대로 미러링해 활성 탭의 평탄 grid 인덱스 계산)로 위치를 구해 `gridState.scrollToItem`. 보던 탭이 목록 아래쪽에 있어도 바로 보임.
- **탭 갤러리 썸네일 (Opera/삼성인터넷 스타일):** 탭 카드가 URL 텍스트 대신 실제 페이지 썸네일을 보여준다. `TabThumbnailStore`(`ui/`)가 탭 id → `ImageBitmap` 메모리 캐시(`mutableStateMapOf`라 새 캡처가 들어오면 카드가 즉시 recompose). 캡처는 `webView.draw(Canvas(downscaled bitmap))`로 현재 뷰포트를 600px 폭 RGB_565로 다운스케일(투명 페이지 대비 흰 배경 fill) — **활성 탭만 attach/레이아웃되어 있으므로 그 순간만 신뢰성 있게 캡처 가능**. 그래서 `BrowserScreen`의 `captureActive()`가 (1) 탭 스위처 열기 직전(`TabCountButton` onClick), (2) 바 스와이프 탭 전환 직전(`switchAdjacentTab`)에 현재 활성 WebView를 캡처한다. 아직 캡처 안 된 탭은 `hostOf(url)` 도메인 플레이스홀더 카드. 저장소는 webStates와 함께 `RootNavigation`에 remember되어 네비게이션을 살아남고, WebView GC `LaunchedEffect`에서 `thumbnails.retain(liveIds)`로 동시 prune(프로세스 종료 시 휘발 — 영속화 안 함, 재방문 시 재캡처). `TabCard`는 상단 제목 바(활성 탭은 primaryContainer 틴트) + 하단 썸네일 `Image(ContentScale.Crop, TopCenter)` 구조, 활성 탭은 강조 테두리(2.5dp primary)+그림자(6dp).
- **드래그로 탭 그룹 편집:** `TabDragAndDrop.kt`. 카드 롱프레스 → (기존대로 선택 모드 진입) → 손을 떼지 않고 touch slop 이상 끌면 `tabDragSource`(`dragAndDropSource` + `detectDragGesturesAfterLongPress`)가 플랫폼 드래그 시작. 드롭 타깃은 per-item `dragAndDropTarget`이 **아니라** 그리드 전체를 덮는 단일 `tabDropTargetRoot` 1개 — Compose 1.6.x에서 드래그 세션 시작 후 attach된 타깃 노드(스크롤로 새로 composition된 lazy item)는 세션에 합류하지 못하는 제약 회피. 각 헤더/카드는 `tabDropRegion`(`onGloballyPositioned`)으로 자기 bounds를 `TabDragState.regions`에 등록하고 루트 타깃이 hit-test로 섹션 결정. 드래그 중에는 빈 "그룹 없음" 섹션을 항상 노출(그룹에서 빼기 드롭 타깃), 가장자리 64dp 밴드에서 자동 스크롤. 선택된 카드를 끌면 `selectedIds` 전체가 함께 이동. `tabDragSource`는 반드시 `combinedClickable` **뒤에** 체이닝 — Main 포인터 패스에서 안쪽 노드가 먼저 이벤트를 소비해야 clickable의 long-press 후 `consumeUntilUp`이 드래그를 죽이지 못함.
- **URL 숫자 자동 복구 (`UrlRecovery`):** 메인 프레임이 접속 실패(`onReceivedError`, 코드 `-1/-2/-6/-7/-8/-11/-16`)하면 URL의 숫자를 증감한 후보(`+1~+10`, `-1~-3`)를 만들어 백그라운드로 접속 가능 여부를 확인하고 살아있는 가장 가까운 주소로 **자동 이동**한다. 우선순위는 **도메인 숫자**(newtoki123 → newtoki124, IP 리터럴 제외) → 없으면 경로 숫자 → 쿼리 숫자. 프로브는 WebView와 같은 경로(SNI 우회 켜짐 → DoH + ClientHello 단편화, 꺼짐 → 일반)로 OkHttp GET을 날려 응답 코드로 판정(`2xx/3xx` + `401/403/429`는 생존, `404/410/5xx`·연결에러는 사망). 프로브 동안 `ErrorPage.probing()` 대기 화면, 못 찾으면 평소 `ErrorPage.build()`. 오실레이션/폭주 방지로 최근 자동 이동 후보(60s)는 후보에서 제외하고 2분 내 자동 이동은 5회로 제한. 404/5xx 같은 HTTP 에러(`onReceivedHttpError`)는 대상이 아님 — "접속 자체가 안 되는" 경우만. 후보 생성/프로브 코어는 `buildCandidates`/`reachable`로 분리돼 자동(`candidates`+`probe`)과 수동(`findAlternative`)이 공유.
- **URL 숫자 수동 복구:** 자동 복구는 *접속 실패* 에러일 때만 발동하므로, 페이지가 정상 로드됐지만(예: 404 안내·잘못된 콘텐츠가 200으로 뜸) 사용자가 직접 새 주소를 찾고 싶을 때를 위한 수동 트리거. 상단 ⋮ 메뉴 → "주소 복구 (URL 찾기)"가 현재 URL로 `UrlRecovery.findAlternative()`를 호출 → 첫 생존 후보로 **즉시 이동**(자동과 동일 정책). 자동과 달리 **TTL/자동이동 예산 가드를 거치지 않는다**(사용자가 명시적으로 다시 시도하는 흐름). 진행/결과는 Toast로 안내, 콜백은 백그라운드 스레드이므로 `WebView.post`로 UI 복귀.
- **iframe 처리:** `IframeScriptInjector`가 cross-origin iframe HTML 응답에 `<script>`를 prepend해 제스처 JS 주입.
- **광고 차단 (`AdBlocker`):** 약 50개 광고/트래커 host suffix + URL 패턴 매칭으로 빈 204 반환. 메인 프레임은 절대 차단 안 함. CSS 셀렉터로 same-domain 광고 슬롯 숨김. `AdBlockSwitch.enabled` volatile 플래그 (hot path).
- **쿠키 동의 배너 자동 거부 (`CookieBannerKiller`):** OneTrust / Cookiebot / Quantcast(TCF) / Didomi / Sourcepoint / TrustArc / Google 컨센트의 "Reject All" 버튼 셀렉터 리스트를 들고 400ms 간격으로 최대 10회 폴링 클릭. 동시에 컨센트 컨테이너를 `display:none` + `html,body{overflow:auto}`로 CSS 숨김 (배너 dismiss 실패 시 콘텐츠/스크롤 락 같이 풀어주는 fallback). `window.__pbCookieKill` 플래그로 idempotent — 매 `onPageFinished`에서 재주입돼도 1회만 동작. `CookieBannerSwitch.enabled` volatile 플래그 (hot path).
- **SNI 우회:** `SniBypassClient` + `FragmentingSocketFactory`가 TLS 레코드 레이어에서 ClientHello를 분할. `DohClient`로 DNS도 우회. `SniBypassSwitch.enabled` volatile 플래그. **OkHttp 클라이언트는 `(fragment, followRedirects)` 2축 매트릭스로 4개** — `fragment`는 SNI 우회 켜짐일 때만 `FragmentingSocketFactory` 부착(프라이빗 DNS 단독이면 평문 소켓, 불필요한 단편화의 호환성 리스크 회피), `followRedirects`는 메인 프레임=false(OkHttp가 내부적으로 리다이렉트를 따라가면 WebView 주소창 URL/origin과 어긋남, 메인 3xx는 WebView가 처리)·서브리소스=true. **WebView는 가로챈 *서브리소스* 응답의 리다이렉트를 따라가지 않으므로**, 같은 호스트 이미지/CSS/JS가 301/302(서명 CDN URL·http→https·확장자 리라이트 등)로 응답하면 3xx를 그대로 돌려줄 경우 조용히 로드 실패 → 서브리소스 클라이언트가 리다이렉트를 직접 따라가 네이티브 로더처럼 로드되게 함.
- **프라이빗 DNS (DoH):** `DohClient`가 RFC 8484 wire-format(POST `application/dns-message`)으로 DNS를 조회해 ISP DNS를 우회/암호화. JSON API가 아니라 wire-format이라 **임의의 DoH 엔드포인트**(Cloudflare/Google/Quad9/AdGuard 프리셋 + 커스텀 URL, NextDNS 등) 지원. 제공자는 `DohProvider` enum(키→URL)으로 모델링, 설정값은 `NetworkSettings.dohProvider`/`dohCustomUrl` → `PrivateDnsSwitch.dohUrl` volatile 미러(provider 변경이 클라이언트 재생성 없이 즉시 반영, `DohClient`가 매 조회 시 live로 읽음). **적용 범위(독립 토글):** `PrivateDnsSwitch.enabled`가 켜지면 SNI 우회와 무관하게 `SniBypassClient.intercept`가 모든 https 메인 프레임을 OkHttp(평문 소켓+DoH)로 라우팅 → 선택 DNS가 모든 페이지에 적용. 인터셉트 게이트는 `if (!sni && !privateDns) return null`, 클라이언트 선택은 `fragment = sni`. **Android 제약:** WebView 네이티브 네트워킹의 DNS는 앱이 못 바꾸므로 DoH는 우리가 가로채는 요청(메인 프레임 + 그 호스트의 same-host 서브리소스, `bypassedHosts`)에만 적용 — 다른 호스트(CDN) 서브리소스는 시스템 DNS. DoH 제공자 설정은 SNI 우회 경로의 DNS에도 공유됨(기본 Cloudflare = 기존 동작 보존).
- **Chromecast:** `CastSessionBridge`를 액티비티 lifecycle에 attach/detach. 세션 시작 시 `VideoStreamSniffer.current(host)`로 현재 페이지의 캐스트 가능한 URL 조회 → `RemoteMediaClient.load()`.
- **풀스크린 동영상:** `WebChromeClient.onShowCustomView`에서 `GestureCapturingFrame`(FrameLayout)으로 감싸 `dispatchTouchEvent`로 제스처 캡처 + 화면 회전/풀스크린/세로 드래그 처리. `onHideCustomView`에서 brightness 복원. **제스처 단일 소스화:** `GestureCapturingFrame`은 `super.dispatchTouchEvent`로 터치를 WebView에도 흘려보내는데, 최신 WebView에선 그 터치가 document에도 도달해 인-도큐먼트 `video_gestures.js` 핸들러가 같은 더블탭을 **이중 시킹**(Kotlin ±10 + JS ±10 → ~20·30초)함. 그래서 `onShowCustomView`/`onHideCustomView`가 `window.__pb.fsActive`를 true/false로 토글하고, JS `touchstart` 핸들러는 `fsActive`면 즉시 bail — 풀스크린에선 Kotlin 프레임이 단일 제스처 소스. (`window.__pb.*` 훅 함수 자체는 Kotlin이 직접 호출하므로 영향 없음)
- **동영상 이어보기 (resume):** 페이지별 마지막 재생 위치를 기록했다가 다음 방문 시 그 지점부터 재생. JS(`video_gestures.js`의 `initResume`) ↔ Kotlin(`ResumeBridge`, `window.PBResume`) 브리지 구조. JS가 비디오 이벤트(`pause`/`seeked`/`ended`)와 5초 인터벌·`visibilitychange`/`pagehide`에 `bridge.save(pos, dur, title)` 호출, `loadedmetadata`/`play` 시 `bridge.load()`로 저장 위치를 받아 `currentTime` 세팅(`v.__pbResumed` 가드로 비디오당 1회). 너무 짧은 영상/시작·끝 근처는 제외(`MIN_DURATION_SEC=90`, `MIN_RESUME_SEC=10`, `END_GUARD_SEC=20`), 끝까지 보면 `ended`에서 기록 삭제. 저장소는 `WatchProgressStore`(`data/`) — Room이 아니라 SharedPreferences JSON 맵(키=프래그먼트 제거한 정규화 URL). Room의 `fallbackToDestructiveMigration()`이 버전 bump 시 즐겨찾기/방문기록을 날리므로 테이블 추가 대신 `TabPersistence`와 같은 SharedPreferences 패턴 채택. 최대 500개 prune. `currentUrl`은 `onPageStarted`/`onPageFinished`에서 `ResumeBridge`에 주입 (JS는 자신의 페이지 URL을 모르므로 Kotlin이 컨텍스트 제공). 설정 토글 → `ResumeSwitch.enabled` volatile 플래그 (브리지 hot path), `WatchProgressStore`에 영향 없이 기록/복원만 무력화.
- **크래시 로깅:** `PlayerBrowserApp.onCreate` 가장 먼저 `CrashRecorder.install(this)`. `Thread.setDefaultUncaughtExceptionHandler` 체이닝으로 시스템 다이얼로그는 그대로 유지. 추가로 `WebViewClient.onRenderProcessGone`이 `true` 반환해 앱 살아남기 + 에러 페이지 + `CrashRecorder.record()`.
- **자체 업데이트:** GitHub Releases API 조회 → APK 다운로드 → `FileProvider`로 설치 인텐트.

## 동영상 제스처 (`video_gestures.js`)

- **싱글탭 → 재생/일시정지** (앱이 탭을 소유). 비디오 위 탭의 합성 `click`을 `suppressClick` + capture-phase `stopPropagation`으로 죽여 사이트/네이티브 `<video>`가 같은 탭으로 토글하지 못하게 함. 단일/더블 구분은 `DOUBLE_TAP_MS` 타이머로 지연 처리(`singleTapTimer`) — 첫 탭은 토글을 예약했다가, 두 번째 탭이 오면 `cancelSingleTap()`으로 취소하고 더블탭 액션 실행.
- **사이트 오버레이 동기화 (`playPauseAtPoint`):** 싱글탭/중앙 더블탭 재생/정지는 비디오 API를 직접 부르지 않고 `playPauseAtPoint(video,x,y)`를 거침. 탭 지점 최상위 요소가 **비디오 위에 따로 얹힌 레이어**(사이트 자체 재생버튼·포스터·툴바 등)면 그 요소에 합성 pointer/mouse `click`(`dispatchSyntheticClick`, `isTrusted=false`)을 dispatch해 **사이트가 직접 처리(재생/정지·⏩·확대 등) + 자기 오버레이를 갱신/숨기게** 함 — 안 그러면 API로만 재생돼 영상은 나오는데 사이트 버튼이 안 사라짐. 합성 클릭은 `isTrusted===false`라 capture click-suppressor가 통과시킴. **폴백은 `paused` 상태 변화가 아니라 요소의 인터랙티브 여부로 판정** — `looksInteractive(top)`이면(버튼/cursor:pointer 등) 사이트에 맡기고 우리가 토글하지 않음(확대 버튼처럼 재생과 무관한 동작을 눌렀을 때 직후 영상이 멈추던 버그 수정), 비인터랙티브 오버레이(포스터/그라데이션)거나 dispatch 실패면 `togglePlay` API 폴백. 탭 지점이 비디오 자신이면 곧장 API.
- **비디오 위 컨트롤 통과 (control passthrough):** 비디오 박스 안이라도 탭 지점의 최상위 엘리먼트가 *작은* 인터랙티브 컨트롤(닫기 ×, 사이트 자체 툴바의 재생/정지/⏪/⏩/확대 버튼 등)이면 탭을 가로채지 않고 그대로 통과시켜 사용자가 누를 수 있게 함. `controlAtPoint(x,y,video)`가 `deepElementFromPoint`(shadow DOM 관통)로 최상위 요소를 찾아 `looksInteractive`(button/a/input/role=button/onclick **또는 `cursor:pointer`** — 커스텀 툴바 버튼이 `<div>/<span>/<i>`라도 잡힘)인지 + 그 bounds 면적이 비디오 면적의 절반 미만인지로 판정 → 참이면 touchstart에서 `onVideo=false`로 떨궈 off-video 탭과 동일 취급(hijack/suppressClick 안 함). **전체 비디오를 덮는 click-catcher(재생/정지 오버레이)는 면적 ≥ 절반이라 통과 대상이 아님** → 맨 비디오 탭의 재생/정지는 기존대로 앱이 소유(Option A 유지).
- 1 손가락 좌/우 스와이프 → -10/+10초 시킹
- 2 손가락 좌/우 스와이프 → 이전/다음 `<video>` 전환
- 양쪽 가장자리 더블탭 → 사이드 인식 더블탭 시킹 (좌 -10, **중앙 재생/정지**, 우 +10). 사이드 더블탭은 **순수 시킹**(첫 탭의 예약 토글이 취소되므로 재생상태 안 바뀜) — 예전엔 첫 탭 `click`이 play/pause를 토글해 시킹+토글이 같이 일어나던 버그를 수정.
- 수평 드래그 → 정밀 시킹 (드래그 양에 비례). **드래그/스와이프/시킹 등 탭이 아닌 제스처는 끝날 때 합성 `click`을 `suppressClick`으로 같이 죽여** 사이트가 그 클릭으로 재생/정지 토글하거나 컨트롤 오버레이를 띄워 안 사라지는 일을 막음. 또 scrub로 인식되지 못한 작은/세로 드래그(`s.moved`)는 탭이 아니므로 재생/정지를 발화하지 않고 클릭만 죽이고 종료.
- 풀스크린 세로 드래그 → 좌측 화면밝기 / 우측 시스템 볼륨 (`window.__pb.showVbOverlay` 호출)
- **풀스크린(Kotlin `GestureCapturingFrame`)도 동일 정책:** `GestureDetector.onSingleTapConfirmed`/`onDoubleTap`(사이드 시킹). 탭 `ACTION_UP`은 WebView로 그대로 흘리지 않고 `ACTION_CANCEL`로 치환해 네이티브 `<video>`의 탭-토글을 차단(JS in-document 경로는 `fsActive`로 이미 꺼져 있음). **단, 탭을 그냥 죽이면 사이트가 영상 위에 올린 오버레이 툴바(재생/⏩/확대 등) 버튼을 풀스크린에서 누를 수 없으므로**, 단일탭/중앙 더블탭은 `togglePlay`를 직접 부르지 않고 `window.__pb.fsTap(xRatio, yRatio)`로 위임 — Kotlin이 탭 위치를 0~1 비율로 넘기면(디바이스 px↔CSS px 무관) JS가 `innerWidth/Height`로 CSS 좌표로 환산해 in-document와 **동일한 `playPauseAtPoint`** 실행 → 툴바 버튼 위면 그 버튼에 합성 클릭 포워딩, 맨 비디오면 재생/정지 토글.
- **이어보기:** `initResume` 모듈이 비디오 위치를 `window.PBResume` 브리지로 저장/복원 (위 "동영상 이어보기" 메모 참조). 90초 이상 영상만 대상, 진입 시 토스트로 안내. 설정에서 토글.
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
