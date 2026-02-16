package com.timxs.storagetoolkit.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.model.ReferenceReplacementTask;
import com.timxs.storagetoolkit.model.ReplaceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;
import org.springframework.data.domain.Sort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ConfigMap 内容更新处理器
 * 处理系统设置、插件设置、主题设置中的 URL 替换
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigMapContentUpdateHandler implements ContentUpdateHandler {

    private final ReactiveExtensionClient client;
    private static final ObjectMapper objectMapper = JsonUtils.mapper();

    @Override
    public String getSourceType() {
        return "ConfigMap";
    }

    @Override
    public Mono<ReplaceResult> replaceUrls(String sourceName, ReferenceReplacementTask task, ReferenceReplacementResult result) {
        return client.fetch(ConfigMap.class, sourceName)
            .flatMap(configMap -> {
                Map<String, String> data = configMap.getData();
                if (data == null || data.isEmpty()) {
                    return Mono.just(ReplaceResult.empty());  // 没有数据
                }

                ReplaceResult replaceResult = ReplaceResult.builder().build();
                Map<String, String> newData = new HashMap<>();

                for (Map.Entry<String, String> entry : data.entrySet()) {
                    String groupKey = entry.getKey();
                    String jsonValue = entry.getValue();
                    String newValue = jsonValue;

                    if (!StringUtils.hasText(jsonValue)) {
                        newData.put(groupKey, jsonValue);
                        continue;
                    }

                    // 尝试解析为 JSON
                    try {
                        JsonNode rootNode = objectMapper.readTree(jsonValue);
                        if (rootNode.isObject()) {
                            ObjectNode objectNode = (ObjectNode) rootNode;
                            Set<String> jsonReplacedUrls = processJsonNode(objectNode, task.getUrlMapping());
                            if (!jsonReplacedUrls.isEmpty()) {
                                newValue = objectMapper.writeValueAsString(objectNode);
                                jsonReplacedUrls.forEach(replaceResult::addReplaced);
                            }
                        } else {
                            // 非对象类型，直接替换
                            for (Map.Entry<String, String> urlEntry : task.getUrlMapping().entrySet()) {
                                if (UrlReplacer.containsUrl(jsonValue, urlEntry.getKey())) {
                                    newValue = UrlReplacer.replaceUrl(newValue, urlEntry.getKey(), urlEntry.getValue());
                                    replaceResult.addReplaced(urlEntry.getKey());
                                }
                            }
                        }
                    } catch (Exception e) {
                        // JSON 解析失败，直接作为字符串替换
                        for (Map.Entry<String, String> urlEntry : task.getUrlMapping().entrySet()) {
                            if (UrlReplacer.containsUrl(jsonValue, urlEntry.getKey())) {
                                newValue = UrlReplacer.replaceUrl(newValue, urlEntry.getKey(), urlEntry.getValue());
                                replaceResult.addReplaced(urlEntry.getKey());
                            }
                        }
                    }

                    newData.put(groupKey, newValue);
                }

                if (!replaceResult.hasReplaced()) {
                    return Mono.just(ReplaceResult.empty());  // 未找到匹配内容
                }

                configMap.setData(newData);

                return client.update(configMap)
                    .thenReturn(replaceResult)  // 返回替换结果
                    .doOnSuccess(v -> {
                        log.debug("ConfigMap {} 引用替换成功，替换了 {} 个 URL", sourceName, replaceResult.getReplaced().size());
                        result.incrementTypeCount(getSourceType());
                    });
            })
            .retryWhen(RetryUtils.optimisticLockRetry())
            .onErrorResume(e -> {
                log.error("ConfigMap {} 替换失败（重试耗尽）: {}", sourceName, e.getMessage());
                ReplaceResult errorResult = ReplaceResult.builder().build();
                for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                    errorResult.addError(entry.getKey(), e.getMessage());
                }
                return Mono.just(errorResult);
            })
            .defaultIfEmpty(ReplaceResult.empty());  // 实体不存在
    }

    @Override
    public Mono<Set<String>> getAllSourceNames() {
        return client.listAll(ConfigMap.class, ListOptions.builder().build(), Sort.unsorted())
            .map(configMap -> configMap.getMetadata().getName())
            .collect(Collectors.toSet());
    }

    /**
     * 递归处理 JSON 节点，替换其中的 URL
     *
     * @param node JSON 节点
     * @param urlMapping URL 映射
     * @return 被替换的 URL 集合
     */
    private Set<String> processJsonNode(ObjectNode node, Map<String, String> urlMapping) {
        Set<String> replacedUrls = new java.util.HashSet<>();

        node.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldValue = entry.getValue();

            if (fieldValue.isTextual()) {
                String text = fieldValue.asText();
                if (StringUtils.hasText(text)) {
                    String newText = text;
                    for (Map.Entry<String, String> urlEntry : urlMapping.entrySet()) {
                        if (UrlReplacer.containsUrl(text, urlEntry.getKey())) {
                            newText = UrlReplacer.replaceUrl(newText, urlEntry.getKey(), urlEntry.getValue());
                            replacedUrls.add(urlEntry.getKey());
                        }
                    }
                    if (!newText.equals(text)) {
                        node.put(fieldName, newText);
                    }
                }
            } else if (fieldValue.isObject()) {
                replacedUrls.addAll(processJsonNode((ObjectNode) fieldValue, urlMapping));
            } else if (fieldValue.isArray()) {
                // 处理数组
                for (int i = 0; i < fieldValue.size(); i++) {
                    JsonNode arrayItem = fieldValue.get(i);
                    if (arrayItem.isTextual()) {
                        String text = arrayItem.asText();
                        if (StringUtils.hasText(text)) {
                            String newText = text;
                            for (Map.Entry<String, String> urlEntry : urlMapping.entrySet()) {
                                if (UrlReplacer.containsUrl(text, urlEntry.getKey())) {
                                    newText = UrlReplacer.replaceUrl(newText, urlEntry.getKey(), urlEntry.getValue());
                                    replacedUrls.add(urlEntry.getKey());
                                }
                            }
                            if (!newText.equals(text)) {
                                // 替换数组元素需要特殊处理
                                ((com.fasterxml.jackson.databind.node.ArrayNode) fieldValue).set(i, newText);
                            }
                        }
                    } else if (arrayItem.isObject()) {
                        replacedUrls.addAll(processJsonNode((ObjectNode) arrayItem, urlMapping));
                    }
                }
            }
        });

        return replacedUrls;
    }

}
