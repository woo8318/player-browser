# Player Browser

URL 입력으로 웹을 탐색하고, 동영상 제스처 컨트롤·광고 차단·SNI 우회·Chromecast 송출을 갖춘 안드로이드 브라우저입니다. WebView 기반 단일 액티비티 + Jetpack Compose UI.

## 주요 기능

### 브라우징
- **상하단 분리 레이아웃** — 상단: 주소창 + 즐겨찾기 + Cast + 메뉴, 하단: 뒤로/앞으로/새로고침/홈/탭 (Opera 스타일)
- **멀티탭** — 탭별 WebView 인스턴스, 세션 영속화 (탭 + 그룹 + 부모 관계 모두 SharedPreferences로 저장). 앱을 껐다 켜도 각 탭의 **뒤로/앞으로 히스토리**까지 유지해 뒤로가기가 방문한 페이지들을 되짚습니다
- **스와이프로 탭 전환 (Chrome/Opera 스타일)** — 상단 주소창 바 또는 하단 내비 바를 좌/우로 스와이프하면 인접 탭으로 전환 (스위처를 열 필요 없음). 좌 스와이프 → 다음 탭, 우 스와이프 → 이전 탭. 전환 시 콘텐츠가 가로로 슬라이드되는 애니메이션 + 짧은 햅틱. 단, 새 창(`window.open` / `target=_blank`)으로 열린 탭에서 우 스와이프하면 그 창을 띄운 부모 탭으로 복귀 (인접 탭 대신). 부모가 없으면 양 끝에서 멈춤(no-wrap)
- **검색어/URL 자동 인식** — URL이 아니면 Google 검색으로 폴백
- **즐겨찾기 / 방문 기록** — 방문한 URL은 즐겨찾기 목록에서 ✓ 표시
- **새 창 링크 → 부모 탭 복귀 (Opera 스타일)** — `target="_blank"` / `window.open()`이 띄운 자식 탭에서 뒤로가기 누르면 자식 탭을 닫고 원래 부모 탭으로 복귀
- **링크를 항상 새 탭에서 열기 (설정 토글, 기본 꺼짐)** — 켜면 새 창/팝업 링크가 아니어도 페이지 안의 일반 링크 탭이 현재 탭을 바꾸지 않고 새 탭(현재 탭의 자식)에서 열림. 뒤로 가기를 하면 원래 탭으로 복귀. 주소창 입력·리다이렉트에는 영향 없음
- **링크 롱프레스 메뉴** — 페이지의 링크를 길게 누르면 "새 탭에서 열기 / 백그라운드 탭으로 열기 / 링크 주소 복사" 메뉴가 뜸. 전역 토글을 켜고 끌 필요 없이 원하는 링크만 새 탭에서 열 수 있음. "백그라운드 탭"은 현재 페이지에 머문 채 새 탭을 뒤에 쌓음 (탭이 열릴 때 페이지 제목을 미리 가져와 탭 목록에서 뭐가 열렸는지 바로 구분 가능)
- **탭 갤러리 (썸네일 미리보기, Opera/삼성인터넷 스타일)** — 탭 스위처가 각 탭을 실제 페이지 썸네일이 채워진 카드로 보여줌. 보던 탭은 스위처를 열거나 스와이프로 전환하는 순간 현재 화면이 캡처되어 카드에 반영됨 (아직 캡처되지 않은 탭은 도메인 플레이스홀더). 활성 탭은 강조 테두리/그림자로 구분
- **탭 그룹화** — 탭 스위처에서 색상 라벨이 붙는 그룹을 만들어 탭을 묶고, 그룹 단위로 일괄 닫기 / 이름 변경 / 해제. 그룹 섹션 헤더는 색상 칩 + 탭 개수 배지로 구분되어 보기 편함. 자식 탭은 부모의 그룹을 자동으로 상속
- **그룹 안에서 바로 새 탭** — 그룹 헤더의 ＋버튼을 누르면 그 그룹 소속으로 새 탭이 바로 열림 (전체에서 만들고 그룹으로 옮기는 번거로움 없음)
- **그룹 순서 변경** — 그룹 헤더를 길게 눌러 끌어서 원하는 위치(다른 그룹 헤더의 위/아래 절반)에 드롭하면 그룹 섹션이 통째로 이동. 헤더 우측 ⋮ 메뉴의 "위로 이동" / "아래로 이동"도 그대로 사용 가능 (변경된 순서는 영속화)
- **탭 멀티 선택 → 일괄 닫기 / 그룹 이동** — 탭 카드를 길게 누르면 선택 모드 진입, 상단 액션 바에서 전체 선택 / 그룹으로 이동 / 일괄 닫기
- **탭 카드 메뉴로 1탭 그룹 이동** — 탭 카드 우측 ⋮ 메뉴에서 "그룹으로 이동..." / "그룹에서 빼기" 로 단일 탭을 다른 그룹으로 옮기거나 그룹 밖으로 빼기 (멀티 선택 모드를 거치지 않고 바로 처리)
- **드래그로 탭 그룹 이동 · 탭 사이 재정렬** — 탭 카드를 길게 눌렀다가 그대로 끌어서 그룹 헤더나 그룹 영역에 드롭하면 그 그룹으로 이동, 다른 탭 위에 드롭하면 그 탭 앞/뒤(좌/우 절반)로 **탭 사이 삽입(재정렬)**, 드래그 중 나타나는 "그룹 없음" 섹션에 드롭하면 그룹에서 빼기. 손가락을 따라다니는 미리보기 배지와 삽입 위치 표시 바로 착지 지점을 안내. 멀티 선택 상태에서 끌면 선택된 탭 전체가 함께 이동하고, 화면 가장자리로 끌면 자동 스크롤 (스크롤로 원래 카드가 화면 밖으로 나가도 드래그가 끊기지 않음)
- **활성 탭으로 자동 스크롤** — 탭 스위처를 열면 지금 보고 있던 탭 위치로 자동 스크롤되어 목록이 길어도 바로 찾을 수 있음
- **외부 브라우저로 열기** — 메뉴에서 현재 URL을 시스템 브라우저로 전달
- **자체 업데이트** — GitHub Releases에서 새 버전 감지 후 인앱 다운로드/설치

### 동영상 제스처 (`<video>` 위에서 동작)
- **1손가락 좌/우 스와이프** — -10초 / +10초 시킹
- **사이드 인식 더블탭** — 좌측: -10초, 우측: +10초, 중앙: 재생/일시정지
- **드래그 시킹 (수평)** — 손가락 위치에 비례해 정밀 시킹
- **2손가락 좌/우 스와이프** — 이전 / 다음 `<video>` 요소로 전환
- **세로 드래그 (풀스크린)** — 좌측: 화면 밝기, 우측: 시스템 볼륨 (MX Player / VLC 스타일)
- **풀스크린 가로/세로 자동 회전** — 영상 비율 감지로 결정
- **이어보기 (보던 곳부터 재생)** — 페이지별로 동영상 재생 위치를 기록해 두고, 다음에 같은 페이지를 방문하면 그 지점부터 자동 재생 (진입 시 토스트로 안내). 90초 이상 영상만 대상이고 시작·끝 근처는 제외, 끝까지 본 영상은 기록 삭제. 설정에서 토글 가능.
- **iframe / cross-origin 영상도 지원** — `IframeScriptInjector`가 자식 프레임 HTML에 제스처 JS 주입

### 내장 동영상 플레이어 (Media3 / ExoPlayer)
- **플레이어로 재생** — 페이지에서 추출된 스트림(`.m3u8` / `.mp4` / `.webm`)을 **앱 내장 네이티브 플레이어**로 띄움. 사이트 플레이어 위 오버레이/레이아웃과의 제스처 충돌 없이 깔끔하게 재생/제어
- **화면 내 재생 버튼 (v1.3.45)** — 재생 가능한 스트림이 감지되면 화면 우하단에 "▶ 플레이어로 재생" 버튼이 자동으로 떠서 메뉴를 열지 않고 바로 실행. 필요 없으면 × 로 닫을 수 있고 페이지를 옮기면 다시 표시(상단 ⋮ 메뉴 항목도 그대로 유지)
- **영상 롱프레스 → 메뉴로 외부 플레이어 연결 (v1.3.50, v1.3.51)** — 한 페이지에 영상이 여러 개일 때 화면 버튼만으로는 어떤 영상이 열리는지 알 수 없어서, 원하는 `<video>`를 **길게 누르면** 그 영상에 대한 메뉴("외부 플레이어로 재생 / 영상 주소 복사")가 떠서 선택. 그 영상의 실제 소스를 읽어 재생하고, `blob:`/MSE라 URL이 없으면 감지된 네트워크 스트림으로 폴백. 재생할 스트림을 못 찾으면 메뉴 대신 안내만 뜸(v1.3.51 — 확인 없이 바로 실행되던 동작을 메뉴로 바꿈)
- **헤더 주입** — 페이지의 Referer / Cookie / User-Agent를 그대로 실어 보내 핫링크·서명 차단이 걸린 CDN도 재생
- **플레이어 제스처** — 기본 컨트롤(재생/정지·스크러버·±10초 버튼) + 단일탭 컨트롤 토글, 좌/우 더블탭 ∓/±10초, 중앙 더블탭 재생/정지, 세로 드래그로 밝기(좌)·볼륨(우). 영상 비율로 화면 자동 회전
- **이어보기 연동** — WebView에서 보던 위치를 그대로 이어받아 재생(같은 이어보기 저장소 공유)
- **스트림 감지 범위 (v1.3.45)** — 확장자가 URL 중간·쿼리 파라미터에 있는 스트림(`/play?file=video.mp4` 등)도 잡도록 감지를 확대. `blob:` / MSE / DRM / 토큰 스트림은 평문 URL이 없어 여전히 추출 불가 → 그런 페이지는 버튼이 안 뜨고 WebView 재생 유지
- **한계** — 스트림은 영상을 잠깐 재생해 요청이 잡힌 뒤에 버튼/메뉴가 활성화됨

### 캐스트 / 미러링
- **Chromecast 송출** — 툴바 Cast 버튼으로 현재 페이지의 HLS(`.m3u8`) / MP4 / WebM 스트림을 디스커버된 리시버로 송출
- **VideoStreamSniffer** — `shouldInterceptRequest`에서 동영상 URL을 자동 캡처해 호스트별로 보관

### 네트워크 / 프라이버시
- **광고 차단** — 약 50개 광고/트래커 도메인 (Google Ads, DoubleClick, Taboola, Outbrain, Criteo, ExoClick 등) + URL 패턴(`/ads/`, `/pagead/`, `adsbygoogle` 등) 매칭으로 서브요청을 빈 204 응답으로 차단. CSS 셀렉터로 동일 도메인 광고 슬롯도 숨김. 설정에서 끌 수 있음.
- **쿠키 동의 배너 자동 거부** — OneTrust / Cookiebot / Quantcast / Didomi / Sourcepoint / TrustArc 등 주요 GDPR·CCPA 컨센트 플랫폼의 "모두 거부" 버튼을 자동 클릭, 실패 시 배너 자체를 CSS로 숨겨 콘텐츠 가림과 스크롤 락을 같이 해제. 설정에서 토글 가능.
- **자동 SNI 우회** — DoH로 DNS 우회 + TLS ClientHello 단편화로 단순 패턴 매칭 DPI 회피 (KT/SKT의 일부 차단 사이트 접근)
- **프라이빗 DNS (DoH)** — DNS 조회를 ISP 대신 선택한 DNS-over-HTTPS 제공자(Cloudflare / Google / Quad9 / AdGuard, 또는 커스텀 URL)로 암호화. 켜면 SNI 우회와 별개로 모든 페이지에 적용되며, AdGuard 선택 시 DNS 단에서 광고·추적 도메인도 차단. RFC 8484 wire-format이라 임의의 DoH 엔드포인트(NextDNS 등) 지원. 설정에서 토글.
- **캡차 무한 반복 해결 (v1.3.56)** — "사람인지 확인"을 통과해도 같은 화면만 계속 뜨던 사이트 대응. Cloudflare가 발급하는 통과 쿠키(`cf_clearance`)는 접속에 쓴 TLS 지문에 묶여 있는데, 챌린지를 푸는 요청과 통과 후 페이지를 받는 요청이 서로 다른 경로로 나가면 쿠키가 무효 처리돼 캡차가 다시 뜬다. 이제 챌린지를 내려준 사이트는 그 호스트 전체를 브라우저 기본 경로로 돌려 흐름이 한 경로에서 끝나게 한다. 그 사이트가 접속 자체에 실패하면 자동으로 원래 우회 경로로 되돌린다.
- **첫 로드 실패 개선 (v1.3.55)** — 차단 사이트가 "한 번에 안 열리고 새로고침하면 열리던" 문제를 두 갈래로 손봄. (1) DoH 조회 결과를 TTL 캐시 + 동시 조회 합류 + 리졸버 커넥션 공유 — 예전엔 커넥션을 새로 열 때마다 DNS 질의를 2번(A/AAAA) 새로 날려, 웹툰 페이지처럼 이미지가 한 CDN에 몰리면 DNS 요청 수십 개가 동시에 터져 로드가 밀렸음. (2) 메인 프레임 요청이 실패하면 즉시 한 번 재시도 — ClientHello 단편화가 DPI를 뚫는지는 확률적이라, 한 번 실패로 포기하면 WebView가 단편화 없는 경로로 떨어져 그대로 차단당했음.
- **URL 숫자 자동 복구** — 접속 자체가 안 되는 페이지의 URL에 숫자가 있으면(예: `newtoki123.com`), 다음 번호들(`+1~+10`, `-1~-3`)을 백그라운드로 확인해 살아있는 가장 가까운 주소로 자동 이동. 도메인 끝 숫자가 주기적으로 바뀌는 사이트 대응. 도메인에 숫자가 없으면 경로/쿼리의 마지막 숫자로 폴백.
- **URL 숫자 수동 복구** — 페이지가 정상 로드됐지만(예: 404 안내 페이지가 200으로 뜨거나 내용이 바뀐 경우) 새 주소를 직접 찾고 싶을 때, 상단 ⋮ 메뉴 → **주소 복구 (URL 찾기)**로 현재 주소 기준 숫자 후보를 즉석에서 확인해 살아있는 주소로 이동. 자동 복구가 발동하지 않는 상황을 보완.
- **캡차("사람인지 확인") 흐름 보호** — Cloudflare Turnstile / hCaptcha / reCAPTCHA / DataDome 등 안티봇 챌린지 요청은 iframe 스크립트 주입도 SNI 우회 가로채기도 하지 않고 WebView 네이티브 로더에 그대로 맡긴다. 예전엔 챌린지 iframe을 앱이 다시 받아 CSP를 벗기고 스크립트를 끼워 넣었고, SNI 우회 시엔 챌린지 GET만 가로채고 검증 POST는 네이티브로 나가 커넥션이 갈라지는 바람에 체크를 해도 확인 화면만 무한 반복됐음. 통과 쿠키(`cf_clearance` 등)는 `CookieManager.flush()`로 디스크에 확정해 앱을 껐다 켜도 유지. 캡차가 감지되면 디버그 로그에 종류·주소·설정 상태가 남고, 같은 주소에서 3회 이상 반복되면 "루프 의심"으로 기록.
- **HTTP/HTTPS 프록시** — 인증 포함 외부 프록시 경유 (WebView 트래픽)
- **크래시 로깅** — `Thread.setDefaultUncaughtExceptionHandler` + `WebViewClient.onRenderProcessGone`으로 메인 프로세스/WebView 렌더러 충돌을 `filesDir/crashes/`에 영구 저장. 디버그 로그 화면에서 조회/복사.
- **인앱 디버그 로그** — `DebugLog`가 SNI 우회 / 광고 차단 / 캐스트 / 캡차 감지 이벤트 등을 메모리 링버퍼 + 화면에 표시

## 갤럭시 S24에 설치하기

### 1. GitHub Actions로 APK 빌드
- 이 저장소를 GitHub에 push하거나 `v1.x.x` 태그를 push하면 `.github/workflows/android.yml` 워크플로우가 자동 실행됩니다.
- GitHub 저장소 → **Releases** 탭 → 최신 릴리스의 `app-debug.apk` 다운로드 (태그 push 시 자동 첨부, 최신 3개만 유지)
- 또는 **Actions** 탭 → 워크플로우 실행 → **Artifacts** 섹션의 `player-browser-debug-apk`

### 2. S24에 설치
1. 다운로드한 `app-debug.apk`를 S24로 전송 (USB / 메신저 / 클라우드).
2. **설정 → 보안 및 개인정보 보호 → 출처를 알 수 없는 앱 설치** 에서 사용하는 파일 관리자(또는 브라우저)에 권한 허용.
3. 파일 관리자에서 APK 탭 → 설치.
4. 첫 실행 시 보안 경고가 뜨면 "그래도 설치" 선택.

> 디버그 빌드는 디버그 키로 서명됩니다. 정식 배포는 release 빌드 + 자체 키스토어가 필요합니다.

### 3. 인앱 업데이트
설치 후에는 메뉴 → "업데이트 확인"으로 새 버전을 받을 수 있습니다. 자동으로도 GitHub Releases를 폴링하므로 새 태그가 push되면 다이얼로그가 뜹니다.

## 로컬 빌드 (선택)

Android Studio Hedgehog 이상 + JDK 17 필요. 로컬에 gradle wrapper가 없으면 한 번 생성:

```bash
gradle wrapper --gradle-version 8.7 --distribution-type bin
./gradlew assembleDebug
# APK 위치: app/build/outputs/apk/debug/app-debug.apk
```

## 프로젝트 구조

```
app/src/main/
  AndroidManifest.xml
  assets/video_gestures.js              # WebView에 주입되는 제스처 JS
  java/com/playerbrowser/app/
    MainActivity.kt                     # 단일 액티비티 + Compose
    PlayerBrowserApp.kt                 # Application — CrashRecorder / 프록시 / SNI / AdBlock 초기화
    cast/                               # Chromecast 송출
      CastOptionsProvider.kt
      CastSessionBridge.kt
      VideoStreamSniffer.kt             # 페이지 내 동영상 URL 캡처
    data/                               # Room DB + 탭/이어보기 영속화
      AppDatabase.kt / Bookmark*.kt / HistoryEntry*.kt / BrowserRepository.kt
      TabPersistence.kt
      TabWebStateStore.kt               # 탭별 WebView 뒤로/앞으로 히스토리 저장 (saveState/restoreState Bundle)
      WatchProgressStore.kt             # 동영상 이어보기 위치 저장 (SharedPreferences JSON)
    network/                            # 네트워크 인터셉트 / 프록시 / 진단
      AdBlocker.kt / AdBlockSwitch.kt   # 광고 차단
      CookieBannerKiller.kt / CookieBannerSwitch.kt  # 쿠키 동의 배너 자동 거부
      ChallengeDetector.kt              # 캡차/안티봇 챌린지 가로채기 금지 목록 + 감지 로그
      CookieFlusher.kt                  # CookieManager.flush() 디바운서 (통과 쿠키 영속화)
      ResumeSwitch.kt                   # 이어보기 토글
      LinkNewTabSwitch.kt               # 링크 항상 새 탭 열기 토글 (shouldOverrideUrlLoading hot path)
      SniBypassClient.kt / SniBypassSwitch.kt / FragmentingSocketFactory.kt / DohClient.kt
      ProxyManager.kt / NetworkSettings*.kt
      CrashRecorder.kt                  # 충돌 영속화
      DebugLog.kt                       # 인앱 로그 링버퍼
    ui/                                 # Jetpack Compose 화면
      RootNavigation.kt
      BrowserScreen.kt / BrowserWebView.kt / BrowserViewModel.kt
      TabSwitcher.kt                    # 탭 갤러리/스위처 (썸네일 카드 + 그룹(헤더 ＋버튼 새 탭) + 그룹 순서 변경 + 멀티 선택 + 드래그 그룹 이동·탭 사이 재정렬)
      TabThumbnailStore.kt              # 탭별 WebView 썸네일 메모리 캐시 (갤러리 카드 미리보기)
      TabDragAndDrop.kt                 # 탭 카드/그룹 헤더 포인터 드래그 → 그룹 이동 + 탭 사이 재정렬 + 그룹 순서변경 (상태 + 소스/드롭영역/컨테이너 모디파이어)
      BookmarksScreen.kt / HistoryScreen.kt
      SettingsScreen.kt / SettingsViewModel.kt
      DebugLogScreen.kt                 # 로그 + 충돌 기록 뷰어
      UpdateDialog.kt / ErrorPage.kt
    update/                             # 자체 업데이트 (GitHub Releases)
      UpdateClient.kt / UpdateInstaller.kt / UpdateModels.kt / Version.kt
    web/                                # WebView 유틸
      UrlUtils.kt / WebAssetLoader.kt / IframeScriptInjector.kt
      ResumeBridge.kt                   # window.PBResume — 이어보기 JS↔Kotlin 브리지
.github/workflows/android.yml           # APK 빌드 + 릴리스 + 오래된 릴리스 정리
```

## 동영상 제스처 동작 원리

- `BrowserWebView`의 `onPageFinished`에서 `video_gestures.js`를 페이지에 주입.
- 페이지의 `<video>` 요소 위 터치 이벤트(`touchstart` / `touchmove` / `touchend`)를 가로채 손가락 개수·방향·시작 위치로 동작 분기.
- 네이티브 풀스크린에서는 `GestureCapturingFrame`이 raw 터치를 사이트(WebView)로 다시 흘려 사이트 자체 컨트롤·탭·네이티브 컨트롤 바가 동작하게 하고, 앱은 사이트가 제공하지 않는 **세로 밝기/볼륨 + 2손가락 영상 전환**만 얹습니다(v1.3.42). 풀세트 제스처(±10초 더블탭 등)가 필요하면 메뉴 → "플레이어로 재생"의 **내장 Media3 플레이어**를 사용하세요.
- 시킹/전환 시 화면 상단/측면에 토스트 또는 진행바 표시.
- iframe (cross-origin 포함) 내부 `<video>`도 `IframeScriptInjector`가 HTML 응답에 `<script>`를 prepend해 지원.

YouTube 등 자체 제스처가 강한 사이트는 일부 동작이 충돌할 수 있어, 일반 `<video>`를 노출하는 사이트에서 가장 잘 작동합니다.

## 광고 차단 동작 원리

- `BrowserWebView.shouldInterceptRequest`에서 가장 먼저 `AdBlocker.intercept()` 호출.
- 매칭 시 빈 `204 No Content` 응답 반환 → 광고 페이로드 다운로드 자체를 막아 트래픽/배터리/렌더링 시간 절약.
- 메인 프레임 요청은 절대 차단하지 않음 (실수로 광고 URL을 직접 입력했을 때 빈 페이지가 되는 사고 방지).
- `onPageFinished`에서 동일 도메인 광고 슬롯을 가리는 CSS를 한 번 inject.
- 광고 차단을 감지해 콘텐츠를 막는 사이트는 설정에서 토글로 즉시 끌 수 있음.

## 크래시 / 디버그

- 메인 프로세스 충돌 → `CrashRecorder`가 `filesDir/crashes/crash-{시각}.txt`로 스택트레이스 저장 (최대 20개 보관)
- WebView 렌더러 충돌 → `onRenderProcessGone`이 잡고 `true` 반환해 앱 살아남기 + 에러 페이지 + 토스트 + 기록
- 메뉴 → 설정 → 디버그 로그에서 충돌 목록과 풀 스택트레이스 조회 / 클립보드 복사 가능
- 디버그 로그는 `DebugLog` 링버퍼로 SNI 우회 / 광고 차단 / 캐스트 등 이벤트 실시간 표시
