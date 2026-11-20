package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekWebToolsTest {

    @Test
    void registersWebSearchAndWebFetch() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DeepSeekWebTools("test-key"));

        assertThat(toolkit.getToolNames()).contains("web_search", "web_fetch");
    }

    @Test
    void validatesInputsWithoutNetwork() {
        DeepSeekWebTools tools = new DeepSeekWebTools("test-key");
        assertThat(tools.webSearch("  ", 5).getState().name()).isEqualTo("ERROR");
        assertThat(tools.webFetch("ftp://example.com", 100).getState().name()).isEqualTo("ERROR");
    }
}
