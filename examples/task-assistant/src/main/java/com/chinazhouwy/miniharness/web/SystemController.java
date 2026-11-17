package com.chinazhouwy.miniharness.web;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 系统信息：引擎规模统计与模式标记，供前端与运维检查。 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final HarnessEngine engine;
    private final String modelMode;

    public SystemController(HarnessEngine engine,
                            @Value("${miniharness.model.mode:auto}") String modelMode) {
        this.engine = engine;
        this.modelMode = modelMode;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("app", "task-assistant (MiniHarness v0.1)");
        info.put("modelMode", modelMode);
        info.put("tasks", engine.tasks().findAll().size());
        info.put("runs", engine.runs().findAll().size());
        info.put("events", engine.eventLog().size());
        info.put("experiences", engine.experiences().all().size());
        info.put("candidates", engine.candidates().all().size());
        info.put("skills", engine.skills().all().size());
        info.put("workflows", engine.workflows().knownWorkflowNames());
        Map<String, Object> workflows = new LinkedHashMap<>();
        for (String name : engine.workflows().knownWorkflowNames()) {
            workflows.put(name, engine.workflows().publishedVersion(name).orElse(null));
        }
        info.put("publishedWorkflows", workflows);
        info.put("disclaimer", "金融数据为合成数据；不接入真实行情与交易");
        return info;
    }
}
