package com.playerbrowser.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import com.playerbrowser.app.network.DohProvider
import com.playerbrowser.app.network.NetworkSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenDebugLog: () -> Unit
) {
    val saved by viewModel.settings.collectAsState()
    val event by viewModel.event.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var enabled by remember(saved.proxyEnabled) { mutableStateOf(saved.proxyEnabled) }
    var host by remember(saved.proxyHost) { mutableStateOf(saved.proxyHost) }
    var portText by remember(saved.proxyPort) {
        mutableStateOf(if (saved.proxyPort == 0) "" else saved.proxyPort.toString())
    }
    var username by remember(saved.proxyUsername) { mutableStateOf(saved.proxyUsername) }
    var password by remember(saved.proxyPassword) { mutableStateOf(saved.proxyPassword) }

    var dohProviderKey by remember(saved.dohProvider) { mutableStateOf(saved.dohProvider) }
    var dohCustomUrl by remember(saved.dohCustomUrl) { mutableStateOf(saved.dohCustomUrl) }

    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        if (e is ProxyApplyEvent.Message) {
            snackbarHostState.showSnackbar(e.text)
        }
        viewModel.consumeEvent()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle("프록시 (HTTP/HTTPS)")
            Text(
                text = "해외 HTTP/HTTPS 프록시를 경유해 WebView 트래픽을 보냅니다. " +
                    "Opera Browser의 우회 방식과 유사하게, ISP의 SNI 차단을 피할 수 있습니다. " +
                    "신뢰할 수 있는 프록시만 사용하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!viewModel.proxySupported) {
                Text(
                    text = "이 기기의 WebView는 프록시 설정을 지원하지 않습니다. " +
                        "WebView를 최신 버전으로 업데이트하세요.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("프록시 사용", modifier = Modifier.padding(end = 12.dp))
                Spacer(modifier = Modifier.fillMaxWidth(0.6f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = viewModel.proxySupported
                )
            }

            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Host (예: proxy.example.com)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = portText,
                onValueChange = { input -> portText = input.filter { it.isDigit() }.take(5) },
                label = { Text("Port (1-65535)") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username (선택)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (선택)") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    viewModel.saveAndApply(
                        NetworkSettings(
                            proxyEnabled = enabled,
                            proxyHost = host,
                            proxyPort = portText.toIntOrNull() ?: 0,
                            proxyUsername = username,
                            proxyPassword = password
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("저장 및 적용") }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Text(
                text = "참고: 인증이 있는 프록시는 사이트 첫 요청 시 한 번 인증창이 뜰 수 있습니다. " +
                    "SOCKS 프록시는 지원하지 않습니다. " +
                    "프록시 변경 후에는 새로 로드되는 페이지부터 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            SectionTitle("자동 SNI 우회 (실험적)")
            Text(
                text = "별도 설정 없이 일부 한국 ISP의 SNI 차단을 자동 우회합니다. " +
                    "DoH(Cloudflare)로 DNS를 우회하고, TLS ClientHello를 단편화해 단순 패턴 매칭 DPI를 피합니다. " +
                    "통신사·사이트에 따라 효과 차이가 있으며, 일부 사이트는 호환성 문제로 깨질 수 있습니다. " +
                    "문제가 있으면 끄세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("자동 SNI 우회 사용", modifier = Modifier.padding(end = 12.dp))
                Spacer(modifier = Modifier.fillMaxWidth(0.6f))
                Switch(
                    checked = saved.sniBypassEnabled,
                    onCheckedChange = { viewModel.setSniBypassEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            SectionTitle("프라이빗 DNS (DoH)")
            Text(
                text = "DNS 조회를 ISP 대신 선택한 DNS-over-HTTPS 제공자로 암호화해 보냅니다. " +
                    "켜면 SNI 우회와 별개로 모든 페이지(메인 주소)에 적용됩니다. " +
                    "AdGuard를 고르면 DNS 단에서 광고·추적 도메인도 차단됩니다. " +
                    "참고: Android 제약상 CDN 등 다른 호스트의 일부 하위 리소스는 시스템 DNS를 " +
                    "탈 수 있습니다. 제공자 설정은 SNI 우회의 DNS에도 함께 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("프라이빗 DNS 사용 (모든 페이지)", modifier = Modifier.padding(end = 12.dp))
                Spacer(modifier = Modifier.fillMaxWidth(0.4f))
                Switch(
                    checked = saved.privateDnsEnabled,
                    onCheckedChange = { viewModel.setPrivateDnsEnabled(it) }
                )
            }

            DohProvider.entries.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = dohProviderKey == provider.key,
                            onClick = { dohProviderKey = provider.key }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = dohProviderKey == provider.key,
                        onClick = { dohProviderKey = provider.key }
                    )
                    Text(provider.label, modifier = Modifier.padding(start = 4.dp))
                }
            }

            if (dohProviderKey == DohProvider.CUSTOM.key) {
                OutlinedTextField(
                    value = dohCustomUrl,
                    onValueChange = { dohCustomUrl = it.trim() },
                    label = { Text("DoH URL (예: https://dns.nextdns.io/abc123)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = { viewModel.setDohProvider(dohProviderKey, dohCustomUrl) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("DNS 제공자 적용") }
            Text(
                text = "DNS 변경 후에는 새로 로드되는 페이지부터 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            SectionTitle("광고 차단")
            Text(
                text = "주요 광고/추적 도메인 (Google Ads, DoubleClick, Taboola, " +
                    "Outbrain, Criteo 등) 으로 가는 서브요청을 네트워크 단에서 차단합니다. " +
                    "트래픽·배터리·렌더링 시간을 줄여 페이지 로딩이 빨라집니다. " +
                    "일부 사이트는 광고 차단을 감지해 콘텐츠를 막을 수 있으니, " +
                    "그런 사이트에서는 잠시 끄세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("광고 차단 사용", modifier = Modifier.padding(end = 12.dp))
                Spacer(modifier = Modifier.fillMaxWidth(0.6f))
                Switch(
                    checked = saved.adBlockEnabled,
                    onCheckedChange = { viewModel.setAdBlockEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            SectionTitle("쿠키 동의 배너 자동 거부")
            Text(
                text = "GDPR/CCPA 쿠키 동의 배너 (OneTrust, Cookiebot, Quantcast, " +
                    "Didomi, TrustArc 등) 를 자동으로 \"모두 거부\"로 닫습니다. " +
                    "버튼 클릭이 실패하면 배너 자체를 숨겨 콘텐츠 가림을 막습니다. " +
                    "일부 사이트가 배너 동작에 의존하면 깨질 수 있으니, 그때만 끄세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("쿠키 배너 자동 거부", modifier = Modifier.padding(end = 12.dp))
                Spacer(modifier = Modifier.fillMaxWidth(0.6f))
                Switch(
                    checked = saved.cookieBannerEnabled,
                    onCheckedChange = { viewModel.setCookieBannerEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            SectionTitle("JS 환경 위장")
            Text(
                text = "이 브라우저가 앱 안의 WebView라는 것을 자바스크립트에서 알아볼 수 없게, " +
                    "진짜 Chrome에는 있고 WebView에는 없는 항목들을 채워 넣습니다. " +
                    "일부 사이트의 \"사람인지 확인\"이 무한 반복될 때, 이 위장이 오히려 " +
                    "원인일 수 있으므로 꺼서 비교해 보세요. 바꾼 뒤에는 새 탭에서 열어야 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("JS 환경 위장 사용", modifier = Modifier.padding(end = 12.dp))
                Spacer(modifier = Modifier.fillMaxWidth(0.6f))
                Switch(
                    checked = saved.jsEnvSpoofEnabled,
                    onCheckedChange = { viewModel.setJsEnvSpoofEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            SectionTitle("이어보기")
            Text(
                text = "동영상을 보던 위치를 페이지별로 기억했다가, 같은 페이지를 다시 열면 " +
                    "그 지점부터 자동으로 이어서 재생합니다. (90초 이상 길이의 영상만 대상, " +
                    "거의 끝까지 본 영상은 제외) 진행 위치는 기기에만 저장됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("이어보기 사용", modifier = Modifier.padding(end = 12.dp))
                Spacer(modifier = Modifier.fillMaxWidth(0.6f))
                Switch(
                    checked = saved.resumePlaybackEnabled,
                    onCheckedChange = { viewModel.setResumePlaybackEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            SectionTitle("링크를 새 탭에서 열기")
            Text(
                text = "페이지 안의 링크를 누를 때 현재 탭을 바꾸지 않고 항상 새 탭에서 엽니다. " +
                    "(새 창/팝업으로 열리는 링크가 아니어도 적용) 새 탭은 현재 탭의 자식으로 " +
                    "열려서 뒤로 가기를 하면 원래 탭으로 돌아옵니다. 주소창 입력·페이지 내 " +
                    "리다이렉트에는 영향이 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("링크 항상 새 탭에서 열기", modifier = Modifier.padding(end = 12.dp))
                Spacer(modifier = Modifier.fillMaxWidth(0.6f))
                Switch(
                    checked = saved.openLinksInNewTab,
                    onCheckedChange = { viewModel.setOpenLinksInNewTab(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            SectionTitle("디버그")
            Text(
                text = "SNI 우회·네트워크 인터셉트 동작 로그를 인앱에서 확인합니다. " +
                    "사이트 로드에 문제가 있을 때 어디서 막혔는지 빠르게 추적할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onOpenDebugLog,
                modifier = Modifier.fillMaxWidth()
            ) { Text("디버그 로그 보기") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}
