# CLAUDE.md

Player Browser — Android WebView 기반 브라우저. URL 탐색 + 동영상 제스처 + 내장 Media3 플레이어(스트림 추출 → 네이티브 재생, 인라인 교체, 플로팅 재생 버튼, 영상 롱프레스 메뉴) + 영상 오프라인 다운로드(HLS, 받으면서 보기) + 이어보기 + 즐겨찾기/방문기록 + 도메인 숫자 무관 방문 링크 표시 + 웹툰 이미지 번호순 정렬 + 요소 숨기기(사이트별 영속) + 멀티탭(썸네일 갤러리/그룹/드래그/스와이프 전환/탭별 히스토리) + 광고 차단 + 쿠키 배너 자동 거부 + SNI 우회 + 프라이빗 DNS(DoH) + URL 숫자 복구 + 링크 롱프레스 메뉴 + 캡차 흐름 보호 + Chromecast + 자체 업데이트 + 크래시 로깅. 현재 버전: v1.3.104.

**이 문서는 규칙과 구조만 담는다.** 판별 조사 서사(어떤 로그로 어떤 가설을 세웠는지)는 `docs/HISTORY.md` 에 있다 — 같은 증상이 재발하거나 예전 판단을 뒤집을 때만 그 항목을 찾아 읽는다.

## 빌드 / 배포

- **빌드:** Gradle 8.7 + Kotlin 1.9.24 + AGP 8.5.2, JDK 17. `compileSdk=34`, `minSdk=26`, `targetSdk=34`
- **앱 ID:** `com.playerbrowser.app` (debug는 `.debug` suffix)
- **버전:** `app/build.gradle.kts`의 `versionCode` / `versionName` 직접 수정
- **로컬 빌드:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. 로컬 wrapper 없음 — 필요하면 `gradle wrapper --gradle-version 8.7`
- **CI:** push 시 debug APK 빌드, `v*` 태그 push 시 GitHub Release 생성(최신 3개 유지). **CI 가 debug APK 를 그대로 릴리스한다** — `BuildConfig.DEBUG` 분기는 사용자 기기에서도 참이다.

## 디렉터리 구조

```
app/src/main/
  assets/video_gestures.js        # 제스처 JS + initInlinePlayer(PBInline 보고/take·release) + initBodySniff(fetch/XHR 응답 앞 512B 판별 → PBPlayer.onStreamBody) + initFullscreenFit(풀스크린 영상 contain 최대화) + initResume + initExternalPlayer(영상 롱프레스)
  assets/visited_links.js         # 방문 링크 표시 (함수 표현식 `(<js>)(hashes, siteKey)`, 'use strict' 클로저)
  assets/image_order.js           # 웹툰 이미지 번호순 정렬/되돌리기 (`(<js>)(mode, 기대 호스트)`, 노드 복제 없이 이동)
  assets/element_picker.js        # 요소 숨기기 피커 `window.__pbPicker` (start/startAt/widen/narrow/confirm/exit), 끌기·탭·롱프레스·두 손가락 스크롤
  res/layout/inline_player_view.xml # 인라인 오버레이 PlayerView — surface_type=texture_view (SurfaceView 는 Compose clipToBounds 에 안 잘림)
  java/com/playerbrowser/app/
    MainActivity.kt               # 단일 액티비티(AppCompatActivity 필수) + Compose, CastSessionBridge attach/detach, onStart 에서 DownloadCenter.resumeInterrupted
    PlayerBrowserApp.kt           # Application — CrashRecorder.install 최우선 / ChallengeDetector.attach / EnvSpoofSwitch.attach / 스위치 옵저버 / Cast init
    cast/CastSessionBridge.kt     # 세션 lifecycle + castNow(연결된 리시버로 지금 보내기) + 원격 로드/재생 실패 코드 토스트 `[cast:…]`
    cast/VideoStreamSniffer.kt    # 스트림 후보 (호스트별 최대 12): detectMime(URL) / observeResponseMime(Content-Type + Referer·Origin 캡처) / observeContent(본문 판별) / matching·current·all / dumpMisses(진단)
    data/                         # Room(Bookmark/History) + SharedPreferences 영속화: TabPersistence(탭·그룹 JSON), TabWebStateStore(탭별 saveState Bundle → filesDir/tabstate), WatchProgressStore(이어보기), ElementHideStore(요소 숨기기 규칙, validSelector)
    network/
      AdBlocker.kt                # host suffix + URL 패턴 → 빈 204. 메인 프레임은 절대 차단 안 함
      CookieBannerKiller.kt       # 컨센트 배너 Reject 클릭 + CSS 숨김 JS
      SniBypassClient.kt / FragmentingSocketFactory.kt # ClientHello 조각화. OkHttp 클라이언트 (fragment × followRedirects) 4개
      DohClient.kt / DohProvider.kt # RFC 8484 DoH, TTL 캐시 + 동시조회 합류 + 공유 클라이언트
      *Switch.kt                  # volatile 토글 미러 (hot path 에서 DataStore suspend 회피): AdBlock/CookieBanner/SniBypass/PrivateDns/EnvSpoof/VisitedLink/InlinePlayer/BodySniff/Resume/LinkNewTab
      UrlRecovery.kt              # 접속 실패 시 URL 숫자 증감 후보 프로브 → 자동 이동 / 수동 findAlternative
      ChallengeDetector.kt        # 챌린지 요청 판별(가로채기 금지) + 호스트 격리(등록 도메인 계열, 디스크 영속) + probe/probeTitle
      ChallengeCookies.kt         # cf 쿠키 만료 (resetAll 은 챌린지 시작 전 한 곳에서만)
      UserAgentSpoof.kt           # WebView UA 표식(`; wv`/`Version/4.0`/`Build/`) 제거 + Sec-CH-UA 브랜드 정합 + X-Requested-With 제거
      CookieFlusher.kt / TabTitlePrefetcher.kt / ProxyManager.kt / NetworkSettings*.kt / CrashRecorder.kt / DebugLog.kt(링버퍼 500)
    ui/
      RootNavigation.kt           # 네비 그래프 + webStates/thumbnails/InlinePlayerController 소유(네비게이션을 살아남아야 하는 상태) + ON_STOP 에 saveAll/flush
      BrowserScreen.kt            # 상단(주소+즐겨찾기+Cast+⋮) + WebView 호스트 + 하단 바 + 후보 해석 람다(playCandidate/downloadCandidate/castCandidate/reportNoStream) + 인라인 오버레이 배치
      BrowserWebView.kt           # buildBrowserWebView / WebViewClient(intercept 체인, onPageFinished 주입) / WebChromeClient(풀스크린, onPermissionRequest=DRM만, 위치 즉시 거부, 팝언더 드롭) / GestureCapturingFrame / 링크 롱프레스 메뉴
      InlinePlayerController.kt / InlinePlayerOverlay.kt # 인라인 교체 상태 머신 + Media3 오버레이 (rect 람다, TextureView)
      FullscreenZoom.kt / FullscreenGestureHud.kt # 풀스크린 핀치 줌(View 변환) / 네이티브 HUD
      BrowserViewModel.kt         # 탭·그룹·부모-자식·현재 URL/제목·즐겨찾기·업데이트 상태
      TabSwitcher.kt / TabDragAndDrop.kt / TabSwipeGesture.kt / TabThumbnailStore.kt
      BareWebViewActivity.kt      # 순정 WebView 진단 창 (설정 3개만, 훅·주입·브리지 없음)
      ElementHideUi.kt / DownloadsScreen.kt / SettingsScreen.kt / DebugLogScreen.kt / BookmarksScreen.kt / HistoryScreen.kt / UpdateDialog.kt / ErrorPage.kt
    update/                       # GitHub Releases 조회(403/429 시 API 없는 폴백) + PackageInstaller 세션 설치 + UpdateInstallReceiver
    player/
      VideoPlayerActivity.kt      # Media3 전용 플레이어: Referer/Cookie/UA 주입, 제스처, 이어보기, 다운로드 캐시 읽기, onPlayerError 로깅 + 재시도 사다리 attempt 0~2
      DownloadCenter.kt / VideoDownloadService.kt # DownloadManager + SimpleCache(NoOpCacheEvictor), HLS 는 DownloadHelper, ResolvingDataSource 헤더 재부착, 포그라운드 서비스
    web/
      WebAssetLoader.kt / IframeScriptInjector.kt(cross-origin iframe HTML 에 JS prepend) / BrowserEnvPatch.kt(document-start 환경 정규화)
      ResumeBridge.kt(PBResume) / PlayerBridge.kt(PBPlayer: openVideo, onStreamBody) / InlinePlayerBridge.kt(PBInline + InlinePlayerCommands)
      VisitedLinkMarker.kt / VisitedLinkKeys.kt(사이트 키·페이지 키·cyrb53 해시, JS 와 비트 단위 대응, android 무의존)
      ImageOrderFixer.kt / ElementHider.kt(+ ElementPickerCommands) / UrlUtils.kt
```

## 작업 방식 (모델 역할 분담)

기능 단위 코드 변경은 세 단계로 돈다. 메인 세션은 Fable(`/model claude-fable-5-1`).

1. **메인(Fable) — 설계·판단.** 건드릴 파일과 아래 관련 규칙까지 적은 **구체적 구현 계획**을 만든다. 서브에이전트는 넘겨준 프롬프트만 알고 시작하므로 맥락은 계획에 직접 써 준다.
2. **`sonnet-dev`** (`.claude/agents/sonnet-dev.md`) — 계획대로만 구현. 버전/커밋/문서는 안 건드림.
3. **`opus-reviewer`** (`.claude/agents/opus-reviewer.md`) — diff 를 계획과 대조, 아래 규칙 체크리스트, CRITICAL/HIGH 직접 수정. (전역 `code-reviewer` 는 Edit 권한이 없어 안 씀)
4. **메인(Fable) — 마무리.** 버전 bump → README/CLAUDE.md(+필요 시 `docs/HISTORY.md`) 갱신 → 커밋 → `v1.3.x` 태그 푸시.

한두 줄짜리 사소한 수정은 메인이 직접 해도 된다.

## 검증

- **테스트 인프라 없음.** 컴파일은 CI, 동작은 사용자가 실기기(S24)에서 확인. 로그는 대부분 받을 수 없다 — 그래서 실패 지점마다 **대괄호 코드 토스트**(`[tiny]`/`[error:… http=403]`/`[cast:load:…]`)를 띄우고 사용자가 코드만 알려주는 방식으로 진단한다. 새 실패 분기를 만들면 코드를 붙일 것.
- JS 자산은 `node --check` + 필요 시 jsdom. `VisitedLinkKeys` 는 JVM 에서 컴파일해 JS 와 대조 가능.
- 충돌: 설정 → 디버그 로그 상단 "충돌 기록" (`filesDir/crashes/`).

## 핵심 규칙 (“잡지 말 것”)

각 항목의 배경 서사는 `docs/HISTORY.md` 의 같은 제목 항목에 있다.

### 앱 뼈대
- `MainActivity` 는 **`AppCompatActivity`** 여야 한다 — Cast `MediaRouteButton` 이 `getSupportFragmentManager()` 를 쓴다(아니면 버튼 누르는 순간 크래시). 앱 테마도 `Theme.AppCompat.*`.
- `CrashRecorder.install` 은 기존 핸들러를 체이닝한다 — 끊지 말 것. `onRenderProcessGone` 은 반드시 `true` 반환.
- WebView 생성 시점에 읽히는 값(예: `EnvSpoofSwitch`)은 DataStore 비동기 미러를 기다릴 수 없다 — 동기 SharedPreferences 미러 + `attach()` 를 `onCreate` 에서 먼저.
- 상태 소유: WebView 콜백 람다는 `BrowserScreen` 이 네비게이션으로 빠져도 살아 있다 — 콜백이 쓰는 상태(`InlinePlayerController`, `webStates`, `thumbnails`)는 `RootNavigation` 에 둔다. WebView 콜백 안 토스트는 `applicationContext`.
- 이름 있는 인자로 넘기는 람다의 암묵 라벨은 **호출한 함수 이름**이다 — `onError = onError@{ … }` 처럼 명시 라벨을 쓰고 지우지 말 것.

### 인터셉트 체인 (`shouldInterceptRequest`)
- 순서: 격리 게이트 둘 → `VideoStreamSniffer.observe` → `AdBlocker` → `SniBypassClient.intercept`(응답에서 `observeResponseMime`) → `IframeScriptInjector.process`.
- 격리 게이트: 요청 호스트가 격리 계열이면 스니퍼 관찰만 하고 `null`; 페이지 호스트(`view.tag`)가 격리 계열이고 **메인 프레임이 아니면** 스니퍼 + AdBlocker 만 거치고 `null`. `view.tag` 는 `onPageStarted` 에서만 바뀌므로 메인 프레임 판정에는 쓰지 말 것(이전 페이지 호스트).
- **스니퍼는 항상 AdBlock 앞** — 뒤에 두면 차단된 스트림 주소를 영영 못 본다. 격리 페이지에서도 `observe` 는 남길 것(빼면 재생 버튼/다운로드가 안 뜬다).
- `onPageFinished` 주입(제스처 JS·광고 CSS·쿠키배너·방문 링크·이미지 정렬·요소 숨기기)은 전부 `!onChallenge` 블록 안. `onChallenge` = 챌린지 창 60초 ∨ `isChallengeTitle(view.title)`.

### 캡차 / 안티봇 (가장 많이 되풀이된 회귀)
- **챌린지 페이지 위에서 `evaluateJavascript` 를 한 줄도 돌리지 말 것** — 진단 목적이라도. v1.3.87 에서 이것이 무한 보안확인의 원인으로 확정됐다(관찰이 대상을 바꾼다). 챌린지 판정은 `WebView.getTitle()`(`isChallengeTitle`) 같은 Kotlin 신호만. `probeTitle` 이 그 경로.
- **챌린지 요청은 절대 가로채지 말 것.** 챌린지 호스트는 `ChallengeDetector` 격리 셋(등록 도메인 계열, 7일, 디스크 영속)에 들어가 문서·서브리소스·iframe 전부 네이티브 전담. `SniBypassClient`/`IframeScriptInjector` 는 격리 여부만 묻고 자기 예외를 만들지 않는다. 새 서비스는 `CHALLENGE_HOSTS`/`CHALLENGE_PATHS` 에 추가만. `/cdn-cgi/` 통째로 넣지 말 것(`/cdn-cgi/image/` 는 콘텐츠).
- 접속 실패(`UrlRecovery.shouldProbe` 코드)면 `clearQuarantine` — 캡차 루프보다 접속 불가가 나쁘다.
- `ChallengeCookies.resetAll` 은 챌린지 **시작 전**(`markChallengedHost(preflight=true)`) 한 곳에서만. 루프 중 `cf_clearance` 는 지우지 않는다(관찰만). 만료 문자열엔 `Secure` 필수.
- **UA 는 `UserAgentSpoof.chromeLike()` 한 곳에서만.** 허용 방향은 표식 제거(비표준 → 표준)뿐 — 접미사·데스크톱 UA 는 TLS/UA-CH 지문과 어긋나 점수가 나빠지고 과거 `ERR_CONNECTION_RESET`. UA 문자열을 바꾸면 `applyClientHints` 로 `Sec-CH-UA` 도 짝을 맞출 것.
- `EnvSpoofSwitch` 하나가 UA·Sec-CH-UA·X-Requested-With·`BrowserEnvPatch` **넷 전부**를 감싼다(A/B 가 진짜 순정 테스트여야 한다). `BrowserEnvPatch` 는 값이 이미 있으면 손대지 않고, shim 은 `Function.prototype.toString` 프록시로 `[native code]` 를 돌려준다 — 어설픈 셰이프는 부재보다 강한 신호.
- 원격 디버깅(`setWebContentsDebuggingEnabled`)은 명시적으로 `false`. `BareWebViewActivity` 에 설정을 셋 이상 넣으면 더는 순정 측정이 아니다.
- `onPermissionRequest` 는 `PROTECTED_MEDIA_ID` 만 grant(DRM), 카메라/마이크는 매니페스트 권한이 없으니 deny 유지. 위치는 즉시 거부. `javaScriptCanOpenWindowsAutomatically=false` + 광고 호스트 팝업 드롭.
- 격리 호스트에서는 `fetch` 래핑(`initBodySniff`)·브리지 노출도 꺼진다 — 그 경계를 넘기면 루프가 되살아난다.
- "이 사이트 데이터 지우고 새로고침"(`resetSite`+`forgetHost`+`clearCache`)은 사용자가 직접 부를 때만 — 자동 경로 금지(로그인 세션이 날아간다).

### SNI 우회 / DoH
- OkHttp 클라이언트는 `(fragment, followRedirects)` 2축 4개. 메인 프레임은 `followRedirects=false`(3xx 는 WebView 가 처리), 서브리소스는 `true`(WebView 는 가로챈 서브리소스 리다이렉트를 안 따라간다).
- SNI 켜짐 → 모든 https 서브리소스 가로채 단편화, 단 **cross-host + `Range` 헤더는 네이티브로**(미디어 206/스트리밍 복원력). 프라이빗 DNS 단독이면 cross-host 안 건드림.
- 메인 프레임만 `IOException` 1회 재시도(단편화는 확률적). 서브리소스는 재시도 금지(CDN 버스트가 곱해진다).
- `DohClient`: TTL 캐시(60s~10분) + 호스트별 동시조회 합류 + 공유 클라이언트 + A 실패 시 AAAA 생략. 이 셋을 빼면 웹툰 CDN 에서 DoH 폭주로 첫 로드가 실패한다.
- 인터셉터가 심는 `Set-Cookie` 는 `CookieFlusher.schedule()` 로 디스크 flush(안 하면 프로세스 종료 시 사라진다).

### 스트림 감지 (`VideoStreamSniffer`)
- 인식 경로 셋: `detectMime`(URL — 경로 끝 확장자 → 비영숫자 경계 토큰 → 부모 폴더가 정확히 `m3u8` 인 HLS) / `observeResponseMime`(Content-Type — 우리 OkHttp 를 탄 요청만) / `observeContent`(페이지 JS 가 본 응답 앞 512B — `BodySniffSwitch`).
- **조각·부속은 절대 후보로 넣지 말 것** — `.ts`/`.m4s`/`video/mp2t`/`iso.segment`/`moof`/`styp`/`sidx`/DASH·CMAF 조각/256KB 미만 mp4. 12칸 목록에서 재생목록이 밀려난다(같은 실수를 세 번 고쳤다). `m3u8` 폴더 규칙을 mp4/webm 으로 넓히지 말 것. "마지막 조각에 점이 없으면" 판정 금지(JWT 는 점을 품는다).
- DASH 는 `media3-exoplayer-dash` 가 없어 후보로 잡지 않고 로그만.
- **추측으로 URL 패턴을 추가하지 말 것** — 관찰한 주소(로그·사용자 제공)에 근거해서만. `dumpMisses`(앞 24개 고정 + 최근) 와 요청 집계표가 무엇을 매칭해야 하는지 말해주게 되어 있다.
- 페이지에서 오는 값(`onStreamBody`·`PBInline`)은 전부 불신 — 스킴·길이·mime·페이지당 개수 재검증, 후보는 보고한 WebView 의 `pageHost` 로.
- `blob:`/MSE/DRM 은 평문 URL 이 없어 구조적으로 추출 불가 — 그런 페이지는 "스트림 못 찾음" 이 정답이다.

### 내장 플레이어 / 다운로드
- 헤더 주입(Referer/Cookie/UA)이 핵심. Referer 는 페이지 URL 이며 **이어보기 키(`resumeKey`)로도 쓰인다** — 캡처한 iframe Referer 를 `VideoPlayerActivity` 에 넘기지 말 것.
- 재생 실패 사다리 `attempt` 0(다운로드본+캐시+이어보기) → 1(**`dataSourceFactory` 자체를** 네트워크로 교체) → 2(이어보기 위치까지 버림). "다른 경로로 재시도" 를 만들 때 바꾼 것이 정말 경로인지 확인할 것(v1.3.77 은 `MediaItem` 만 바꿔 캐시를 그대로 읽었다). 실패는 반드시 `onPlayerError` 로그 + 화면 표시 뒤에만 폴백.
- 다운로드 셋: (1) **`NoOpCacheEvictor` 필수**(축출 캐시는 받은 영상을 지운다), (2) 재생 캐시는 읽기 전용(`setCacheWriteDataSinkFactory(null)` + `FLAG_IGNORE_CACHE_ON_ERROR`), (3) `ResolvingDataSource` 로 요청마다 호스트별 Referer/UA + 라이브 Cookie 재부착(HLS 는 조각 URI 만 보인다).
- HLS 재생은 `DownloadCenter.mediaItemFor(url)`(`streamKeys` 포함) 로 — 없으면 다운로드본과 다른 렌디션을 받아 두 번 받는다. 큐잉 직후 인덱스 공백은 `pendingRequests` 가 메운다. 재생은 `enqueue` 콜백에서 띄운다(HLS `DownloadHelper.prepare` 는 비동기).
- `getScheduler()` 는 `null`(부팅 권한 회피) — 대신 `MainActivity.onStart` 의 `resumeInterrupted` 가 큐 상태를 세어 0이면 아무것도 안 하고, 있으면 포그라운드 서비스로 재개. 일시정지 항목은 건드리지 않는다.
- `DefaultLoadControl` min 30s / max 120s — `minBufferMs >= bufferForPlaybackAfterRebufferMs`, `maxBufferMs >= minBufferMs` 어기면 빌더가 던진다.

### 인라인 플레이어 (사이트 영상 자리 교체)
- 자동 요청은 후보가 없으면 버리지 않고 대기(`streamRevision` 갱신 시 재시도), `createdAt` 30초 마감. 같은 영상 재보고는 원래 `createdAt` 유지. 수동(`manual`)은 `armManual` 3초 창 안에서만 수동으로 친다(창 밖 `manual=true` 는 페이지가 지어낸 값).
- 세션 중 **다른 영상 자동 보고는 대체하지 않고 `deferred` 보류 → `hidden`/`gone`/`notrack` 종료 시 승격**(2초 이내 것만). `endUnusable`/`reportGone` 에서 `deferred` 는 **`end()` 전에** 붙잡을 것. `start()` 는 비운다.
- `NO_TRACK_MS`(1.5초) 타이머는 `reportRect` 가 `isUsable` 검사 **전에** 지운다 — 뒤로 옮기면 정상 세션이 `[notrack]` 으로 끝난다.
- 401/403/410 + `attempt==0` + `!reachedReady` 면 헤더(Referer/Origin) 빼고 `replaceSession` 1회 — `start()` 로 바꾸면 타이머가 재무장되고 보류분이 비워진다. `!reachedReady` 를 빼면 재생 중 토큰 만료가 처음부터 다시 재생된다. 캡처한 Referer/Origin 은 짝 그대로(Referer null 이어도 페이지 주소로 채우지 않는다).
- `onError` 가드는 동등성(`!=`) — `remember(session)` 이 data class 동등성으로 재사용하므로 동일성 비교는 모든 에러를 삼킨다.
- 실패 스트림은 `noteFailed` 로 기억해 자동 재교체 루프를 막는다(수동은 막지 않음).
- rect 는 값이 아니라 람다로 넘기고 `Modifier.layout {}` 안에서만 읽는다(값으로 넘기면 스크롤마다 BrowserScreen 전체 recompose). 서피스는 XML TextureView.
- JS `relayed` 맵은 `Object.create(null)`, id 는 `/^[A-Za-z0-9_-]{1,64}$/`, `gone` 은 보고한 프레임(`contentWindow === ev.source`)만, `__pbInlineCmd` 는 자식이 `ev.source === window.parent` 일 때만.
- 자동 모드의 교체 **시도** 실패는 토스트 없음(모든 영상 페이지에 뜬다); 이미 뜬 오버레이가 사라지는 건 자동이어도 코드 토스트.

### Chromecast
- 주소를 넘기는 방식뿐(탭 미러링 불가). 스니퍼가 못 잡는 사이트·리시버가 403 받는 CDN 은 구조적 한계 — 헤더를 실으려면 커스텀 리시버 앱이 필요(범위 밖).
- **스트림이 잡히는 즉시 자동으로 보내지 말 것** — 사용자가 ⋮/롱프레스 메뉴로 `castNow`.
- 로드 결과는 번호(`loadSeq`)로 추적, 로드당 토스트 1회, `REPLACED` 는 로그만.

### 풀스크린 (`GestureCapturingFrame`)
- raw 터치는 **사이트로 포워드**(`super.dispatchTouchEvent`) — 앱은 사이트가 안 주는 제스처만 얹는다(세로 밝기/볼륨, 2손가락 전환, 1손가락 스와이프 ±10초, 핀치 줌). 엔게이지 순간 자식에 `ACTION_CANCEL`. (v1.3.39~41 의 "raw 터치 완전 소비" 는 iframe 플레이어에서 터치가 먹통이 되어 폐기됨 — 되살리지 말 것.)
- HUD 는 네이티브 `FullscreenGestureHud` — DOM 오버레이는 네이티브 풀스크린에서 안 그려진다. CustomView 는 `addView(view, 0, …)` **인덱스 0**(HUD 가 위, 줌 대상 `getChildAt(0)`).
- 스와이프 시킹은 하단 20%(`SEEK_BAND_MAX_Y_RATIO`) 제외 — 1.0 으로 올리면 사이트 스크러버가 오발화.
- 가장자리 시작(`fromEdge`) 판정은 `ACTION_DOWN` 에서만, 하한(위/아래 48dp, 좌/우 24dp) 0 으로 내리지 말 것.
- 컷아웃 `SHORT_EDGES` 는 풀스크린 동안만(밖에서 두면 주소창이 카메라 구멍 밑으로).
- 영상 크기: **잘림 없는 최대**(contain)가 기본 — `object-fit:cover`/자동 확대 금지(사용자가 명시 거절). `initFullscreenFit` 은 속성 선택자 스타일시트(인라인이면 사이트 리사이즈 핸들러와 싸운다), 점수 0.97 이상이면 손대지 않음.
- 핀치 줌은 View 변환(JS/CSS 아님). 1배 판정에서 "벌어짐 > 이동" 조건을 빼면 2손가락 전환이 줌으로 남는다.
- 회전은 `DETECT_VIDEO_ORIENTATION_JS` + 200ms×8 재프로브(intrinsic 크기 미확정 대비).

### 제스처 JS (`video_gestures.js`, 비풀스크린)
- 싱글탭은 앱이 가로채지 않고 사이트로(컨트롤 레이어). 재생/정지는 중앙 더블탭 → `playPauseAtPoint`(오버레이 요소면 합성 클릭, `looksInteractive` 면 사이트에 맡김). 좌/우 더블탭 ∓/±10초는 **영상 박스 기준**(`rel<0.35`/`>0.65`).
- 작은 인터랙티브 컨트롤(면적 < 영상 절반) 위 탭은 통과. 탭이 아닌 제스처 끝엔 `suppressClick`.
- 롱프레스 취소 리스너는 **`window` 캡처**(document 의 `stopImmediatePropagation` 에 막히지 않게). 롱프레스는 이동 12px 에 취소.
- cross-origin iframe 은 `postMessage` 릴레이(`__pbFs`/`__pbOpenVideo`/`__pbStreamBody`/`__pbPressedAway`) — 직계 자식 프레임 메시지만 받고 모양을 거른다.
- 풀스크린에선 `window.__pb.fsActive` 로 in-document 경로가 빠진다.

### 방문 링크 표시 (프라이버시가 설계 중심)
- 기록 URL 은 절대 페이지로 안 넘긴다 — **64비트 해시만**, `'use strict'` 클로저 안 `{ __proto__: null }` 리터럴. `window` 에 올리지 말 것.
- **해시를 페이지가 패치할 수 있는 내장 함수(`Array.isArray`/`Object.create`/`push`/`Set`/`JSON.stringify` …)에 넘기지 말 것** — 리터럴 문법과 인덱스 대입만.
- `VisitedLinkKeys.kt` ↔ `visited_links.js` 의 `siteKey`/`keyOf`/`hash` 는 **비트 단위로 같아야** 한다(어긋나면 조용히 죽는다). 사이트 키 = 호스트 마지막 숫자 묶음 → `#`, 숫자 라벨에 글자 없으면 null(`163.com`).
- 두 번째 인자 기대 사이트 키가 `location.hostname` 과 다르면 즉시 빠진다(탭 전환 중 `getUrl()` 이 앞서 간다). 재주입 세대 구분은 `window.__pbVisited` 객체 정체성.
- 토글 꺼지면 `flatMapLatest` 로 구독 자체를 끊는다.

### 웹툰 이미지 정렬 / 요소 숨기기 / 링크 롱프레스
- 이미지 정렬: 10~20자리 순수 숫자 파일명만, **`cloneNode` 금지**(lazyload 끊김), 기본값을 "모든 사이트" 로 바꾸지 말 것, 사이트별 기억 키는 `VisitedLinkKeys.siteKey`.
- 요소 숨기기: 적용은 CSS(`adoptedStyleSheets` → `<style>` 폴백 유지), `insertRule` 개별 try/catch, `html`/`body` 매칭 건너뜀. `validSelector` 를 풀지 말 것 — 특히 `,`(`a, html` 이 통과). 새 문서 로드 중(`loading`)엔 `EXIT` JS 를 보내지 말 것(챌린지일 수 있다). 피커·`apply` 는 `pickBlockedReason` 게이트 뒤에서만.
- 링크 롱프레스 좌표: 터치 리스너는 `false` 반환(true 면 페이지 터치가 죽는다), `wv.scale` 나눗셈 유지. `startAt` 은 `linkAt`(링크 그대로) → 없을 때만 `tapAt`(48px 확대) — 되돌리지 말 것. 피커 안 롱프레스 결과에 `grow` 넣지 말 것, `onUp` 의 `if (d.held) return` 유지, `render()` 의 `flashT` 가드 유지, `exit()` 의 타이머 정리 유지.

### 멀티탭
- 탭 전환은 WebView 객체 swap. 닫히면 `destroy()`. 슬라이드 애니메이션은 스와이프만 arm(닫힌 탭을 슬라이드하면 파괴된 WebView 참조).
- `TabWebStateStore` restore 는 try/catch + 손상 블롭 삭제 + URL 로드 폴백, >1.5MB 는 저장 스킵.
- 드래그는 자체 포인터 구현(플랫폼 DnD 는 실기기에서 드롭 실패). `tabDragSource` 는 `combinedClickable` **뒤에** 체이닝. 이동·릴리스는 컨테이너(`tabDragContainer`, Initial 패스 소비)가 소유 — 카드는 lazy 라 자동 스크롤로 dispose 된다.
- 그룹 삭제 시 탭은 살아남고 `groupId` 만 null(사용자 실수 보호 — 바꾸지 말 것). `closeTabs` 의 orphan sanitization(부모 닫힌 자식 `parentTabId` null) 필수. `TabPersistence` 스키마 추가는 `optString().ifBlank { null }` 로 하위 호환.
- 링크 새 탭 토글 게이트: `isForMainFrame && hasGesture && !isRedirect && method=="GET"` + http/https. `GET` 조건을 빼면 챌린지 통과 직후 POST 가 새 탭 GET 으로 열려 캡차가 처음부터.
- 백그라운드 탭은 `TabTitlePrefetcher` 로 제목만 채우고 활성화하지 않는다.

### URL 숫자 복구 / 기타
- 자동 복구는 접속 실패 코드(`-1/-2/-6/-7/-8/-11/-16`)만, HTTP 404/5xx 는 대상 아님. 도메인 숫자 → 경로 → 쿼리 순. 60s 후보 제외 + 2분 5회 예산. 수동(`findAlternative`)은 예산 가드 없음.
- 업데이트: GitHub API 403/429 → `releases/latest` 리다이렉트 `Location` 에서 태그 추출 폴백. 자동 확인은 6시간 1회. 설치는 `PackageInstaller` 세션(실패 시 인텐트 폴백).
- 이어보기: `WatchProgressStore`(SharedPreferences) — Room 테이블 추가 금지(`fallbackToDestructiveMigration` 이 즐겨찾기/기록을 날린다). 새 영속 데이터도 같은 패턴.
- 쿠키 배너 셀렉터 추가는 try/catch 패턴 유지(와일드카드 셀렉터가 throw).

## 종속성

Compose BOM `2024.06.00` · Room `2.6.1`(KSP) · appcompat `1.7.0` · mediarouter `1.7.0` + play-services-cast-framework `21.5.0` · webkit `1.11.0` · media3 `1.4.1`(exoplayer/hls/ui — dash 없음) · OkHttp `4.12.0` · DataStore `1.1.1`

## 작업 시 주의사항

- **버전 bump:** 기능 추가/수정 commit 과 함께 `versionCode`/`versionName` 증가(자체 업데이트 필수). 태그 `v1.3.x` push 가 릴리스.
- **commit:** `feat(scope):` / `fix(scope):`. scope 는 `ui`, `player`, `sni`, `adblock`, `cookies`, `cast`, `tabs`, `debug` 등.
- **문서 동기화:** 기능 추가/제거 시 `README.md` "주요 기능" + 이 문서의 디렉터리 구조/규칙을 같은 commit 에. 조사 서사가 길어지면 `docs/HISTORY.md` 에 쓰고 여기엔 규칙만.
- **JS 자산:** WebView 컨텍스트 실행 — `console.log` 남기지 말 것.
- **광고 차단:** 새 도메인은 `AdBlocker.BLOCKED_HOSTS` 에 host suffix(endsWith 매칭).
- **SNI:** KT/SKT DPI 정책이 자주 바뀌어 fragmentation 파라미터 조정이 필요할 수 있음. `DebugLog` 로 진단.
