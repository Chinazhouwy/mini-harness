package com.chinazhouwy.miniharness.agentscope;

import com.openai.models.models.Model;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Path;

public class FirstAgent {


        public static void main(String[] args) {
               HarnessAgent.Builder agentBuilder =  HarnessAgent.builder()
                        .name("first-name")
                        .agentId("first-agentId")
                        .sysPrompt("you are a helper")

                        .model(Model.builder().build().url())
                        .workspace(Path.of("./MiniHarness/data/workspace"));
        }

    
}
