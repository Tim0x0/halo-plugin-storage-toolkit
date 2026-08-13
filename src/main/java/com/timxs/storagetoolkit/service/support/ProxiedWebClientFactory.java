package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.service.SettingsManager;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.InvalidUrlException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder.ParserType;
import org.springframework.web.util.UriUtils;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一构建"代理感知"的 WebClient，并按配置维度缓存。
 * <p>
 * 出站 HTTP 一律走 reactor-netty 而非 HttpURLConnection：下游取消时会真正关闭 channel。
 * 阻塞式 HttpURLConnection 在响应体读取阶段无法被外部中断——JDK 的 {@code disconnect()}
 * 对 keep-alive 上正在读取的连接只调 {@code closeIdleConnection()}，是空操作，
 * 线程只能挂到读超时为止。
 * <p>
 * 超时语义：连接超时用 {@code CONNECT_TIMEOUT_MILLIS}；{@code responseTimeout} 是
 * "两次网络读之间"的空闲上限（底层为 Netty {@code ReadTimeoutHandler}，请求发出后加入
 * pipeline、响应完整接收后移除），对应 {@code HttpURLConnection.setReadTimeout}，
 * 不是整体时长上限，因此大文件不会被误判为超时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxiedWebClientFactory {

    /**
     * 连接池上限。
     * <p>
     * 全局共用一个 {@link ConnectionProvider}，所以要覆盖三个功能并发数上限之**和**
     * （断链检测 20 + 重复检测 10 + 批量处理 10 = 40），而不是其中最大的那个——
     * 它们打的通常是同一个主机（用户自己的站点），会挤进同一个池。
     * 取 64 是按最坏情况留足余量：连接是懒建的，这里只是上限，取宽不产生任何开销。
     */
    private static final int MAX_CONNECTIONS = 64;

    /** 空闲连接淘汰时间：默认池不淘汰，会一直复用已被代理单方面关闭的连接 */
    private static final Duration MAX_IDLE_TIME = Duration.ofSeconds(10);

    /**
     * 取连接的排队上限。池上限已大于所有功能并发之和，实际打不满，此值不会触发；
     * 即便触发，让调用方的 {@code .timeout(...)} 先响也更准确——排队超时抛的是
     * {@code PoolAcquireTimeoutException}，会被归类成"连接失败"而非"超时"。
     */
    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);

    /** 响应体均以 DataBuffer 方式处理，不走内存编解码，此值仅为兜底 */
    private static final int MAX_IN_MEMORY_BYTES = 1024 * 1024;

    private final ProxyRouter proxyRouter;

    /**
     * 全局共用一个连接池（Spring Boot 的 {@code ReactorResourceFactory} 也是这个模式：
     * 一个 ConnectionProvider 供全应用的 WebClient 共用）。
     * <p>
     * 共用是安全的：reactor-netty 内部按 {@code (远端地址, TransportConfig.channelHash())}
     * 分池，而 {@code ClientTransportConfig.channelHash()} 已把 {@code proxyProvider}
     * 折进 hash，走代理与直连的连接天然分属不同池，不会互相串。
     * {@code maxConnections} 也是按池计的，并发上限语义不受影响。
     */
    private final ConnectionProvider connectionProvider = ConnectionProvider.builder("storage-toolkit")
        .maxConnections(MAX_CONNECTIONS)
        .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
        .maxIdleTime(MAX_IDLE_TIME)
        .build();

    private record ClientKey(int timeoutSeconds, String proxyHost, int proxyPort,
                             String nonProxyHosts, boolean followRedirect) {
    }

    /** 只缓存 WebClient：它不持有 OS 资源，连接池由上面的单例持有 */
    private final ConcurrentHashMap<ClientKey, WebClient> cache = new ConcurrentHashMap<>();

    /**
     * 获取（或创建）一个配置好超时与代理的 WebClient。
     *
     * @param followRedirect 下载需要跟随 3xx（存储策略常以重定向返回真实地址）；
     *                       断链检测需关闭，因为 3xx 本身即视为链接有效
     */
    public WebClient get(int timeoutSeconds, SettingsManager.ProxySettings proxySettings,
                         boolean followRedirect) {
        ClientKey key;
        if (proxySettings != null && proxySettings.isEffective()) {
            key = new ClientKey(timeoutSeconds, proxySettings.host(), proxySettings.port(),
                proxyRouter.nonProxyHostsRegex(proxySettings), followRedirect);
        } else {
            key = new ClientKey(timeoutSeconds, "", 0, "", followRedirect);
        }
        return cache.computeIfAbsent(key, this::create);
    }

    /**
     * 规范化 URL：先解码再编码，避免已编码的 URL（如 %E4%B8%AD）被双重编码，
     * 同时让含中文等非 ASCII 字符的裸 URL 变成合法请求目标。
     * <p>
     * 与 Halo 核心 {@code HaloUtils.safeToUri} 保持同一套语义——附件 permalink 由 Halo
     * 生成、也由 Halo 用该方法解析，我们解析出不同结果就等于去请求站点实际不提供的地址。
     * （该类在 application 模块，未导出到插件 api，只能自行实现。）
     * <p>
     * 返回 {@link URI} 而非字符串，调用方用 {@code uri(URI)} 可绕过
     * WebClient 默认的 UriBuilderFactory，不会再编码一次。
     */
    public static URI normalizeUri(String url) {
        String decoded = UriUtils.decode(url, StandardCharsets.UTF_8);
        UriComponentsBuilder builder;
        try {
            builder = UriComponentsBuilder.fromUriString(decoded);
        } catch (InvalidUrlException e) {
            // Spring 7 起默认用严格 RFC 3986 解析器，会拒掉浏览器能正常打开的地址
            // （路径含 % 的 50%25off.jpg 报 Bad path、中文域名报 Bad authority）。
            // WHAT_WG 是 Spring 为"用户手打的、不合 RFC 的 URL"提供的浏览器语义解析器，
            // 断链检测扫的正是这类内容。
            builder = UriComponentsBuilder.fromUriString(decoded, ParserType.WHAT_WG);
        }
        return builder.build(false).encode().toUri();
    }

    private WebClient create(ClientKey k) {
        HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                TimeoutUtils.connectTimeoutMillis(k.timeoutSeconds()))
            .responseTimeout(Duration.ofSeconds(k.timeoutSeconds()))
            .followRedirect(k.followRedirect());

        if (!k.proxyHost().isEmpty() && k.proxyPort() > 0) {
            httpClient = httpClient.proxy(proxy ->
                proxy.type(ProxyProvider.Proxy.HTTP)
                    .host(k.proxyHost())
                    .port(k.proxyPort())
                    // 代理握手用完整超时，不是「总超时 1/3」：
                    // 该超时覆盖「连上代理 + 发 CONNECT + 代理拨通上游 + 回 200」，
                    // 其中"代理拨通上游"不受本地控制，链式代理可能要数秒到数十秒，
                    // 套用为直连握手设计的 1/3 会把慢代理误判成断链。
                    // 取完整值也让用户配置真正作用于代理这一跳——reactor-netty 的默认
                    // 10 秒是写死的，而 OkHttp/Apache 根本没有独立的代理握手超时，
                    // CONNECT 本就由用户配置的 connect/read 超时覆盖。
                    .connectTimeoutMillis(k.timeoutSeconds() * 1000L)
                    // 本站/内网/排除名单命中的 host 直连
                    .nonProxyHosts(k.nonProxyHosts())
            );
            log.info("创建带代理的 WebClient: proxy={}:{}, timeout={}s, followRedirect={}, 直连规则: {}",
                k.proxyHost(), k.proxyPort(), k.timeoutSeconds(), k.followRedirect(),
                k.nonProxyHosts());
        }

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
            .build();
    }

    @PreDestroy
    void shutdown() {
        // 不能 block()：插件停用可能从 Netty event loop 线程触发，
        // block() 会抛 IllegalStateException 导致连接池实际没被释放
        connectionProvider.disposeLater()
            .onErrorComplete()
            .subscribe();
        cache.clear();
    }
}
