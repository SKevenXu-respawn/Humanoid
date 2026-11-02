package com.skevenxu.humanoid.ai;

import com.skevenxu.humanoid.ai.secret.SecretService;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 用数据库中的 DeepSeek Key 动态构建 ChatModel；Key 变更后下一次调用自动重建。
 * 注册为 @Primary，使所有 ChatClient.Builder 都使用它。
 */
@Component
@Primary
public class DynamicDeepSeekChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DynamicDeepSeekChatModel.class);

    private final DeepSeekChatProperties chatProperties;
    private final DeepSeekConnectionProperties connectionProperties;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final SecretService secretService;
    private final RestClient.Builder restClientBuilder;
    private final WebClient.Builder webClientBuilder;

    private volatile DeepSeekChatModel delegate;
    private volatile String delegateKey;

    public DynamicDeepSeekChatModel(DeepSeekChatProperties chatProperties,
                                    DeepSeekConnectionProperties connectionProperties,
                                    ToolCallingManager toolCallingManager,
                                    ObjectProvider<RetryTemplate> retryTemplateProvider,
                                    ObjectProvider<ObservationRegistry> observationRegistryProvider,
                                    SecretService secretService,
                                    @Value("${humanoid.ai.http-connect-timeout-seconds:10}") int connectTimeoutSeconds,
                                    @Value("${humanoid.ai.http-read-timeout-seconds:180}") int readTimeoutSeconds) {
        this.chatProperties = chatProperties;
        this.connectionProperties = connectionProperties;
        this.toolCallingManager = toolCallingManager;
        this.retryTemplate = retryTemplateProvider.getIfAvailable(RetryTemplate::new);
        this.observationRegistry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
        this.secretService = secretService;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(10, readTimeoutSeconds)));
        this.restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(1, connectTimeoutSeconds) * 1000)
                .responseTimeout(Duration.ofSeconds(Math.max(10, readTimeoutSeconds)));
        this.webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        log.info("DeepSeek HTTP 超时: connect={}s read={}s", connectTimeoutSeconds, readTimeoutSeconds);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate().stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate().getOptions();
    }

    @Override
    public ChatOptions getOptions() {
        return delegate().getOptions();
    }

    private DeepSeekChatModel delegate() {
        String key = secretService.deepseekKey();
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("未配置 DeepSeek API Key，请在管理端密钥配置中设置");
        }
        DeepSeekChatModel current = delegate;
        if (current != null && key.equals(delegateKey)) {
            return current;
        }
        synchronized (this) {
            if (delegate != null && key.equals(delegateKey)) {
                return delegate;
            }
            DeepSeekApi.Builder api = DeepSeekApi.builder().apiKey(key)
                    .restClientBuilder(restClientBuilder)
                    .webClientBuilder(webClientBuilder);
            if (StringUtils.hasText(connectionProperties.getBaseUrl())) {
                api.baseUrl(connectionProperties.getBaseUrl());
            }
            if (StringUtils.hasText(chatProperties.getCompletionsPath())) {
                api.completionsPath(chatProperties.getCompletionsPath());
            }
            if (StringUtils.hasText(chatProperties.getBetaPrefixPath())) {
                api.betaPrefixPath(chatProperties.getBetaPrefixPath());
            }
            DeepSeekChatModel model = DeepSeekChatModel.builder()
                    .deepSeekApi(api.build())
                    .options(chatProperties.toOptions())
                    .toolCallingManager(toolCallingManager)
                    .retryTemplate(retryTemplate)
                    .observationRegistry(observationRegistry)
                    .build();
            delegate = model;
            delegateKey = key;
            log.info("DeepSeek ChatModel 已按数据库密钥重建");
            return model;
        }
    }
}
