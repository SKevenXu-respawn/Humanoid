package com.skevenxu.humanoid.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端在创建时会同步连接服务器，若 Playwright 未就绪会导致整个应用启动失败。
 * 这里把 MCP 相关 Bean 标记为懒加载，由 AiChatService 在收集工具时按需初始化并捕获异常。
 */
@Component
public class McpLazyConfig implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        for (String name : beanFactory.getBeanDefinitionNames()) {
            if (name.startsWith("mcp")) {
                beanFactory.getBeanDefinition(name).setLazyInit(true);
            }
        }
    }
}
