package com.chinazhouwy.miniharness.finance.market.verification;

import com.chinazhouwy.miniharness.finance.market.execution.LeverageDashboardTaskExecutor;
import com.chinazhouwy.miniharness.harness.domain.TaskNode;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionResult;
import com.chinazhouwy.miniharness.harness.verification.TaskVerifier;
import com.chinazhouwy.miniharness.harness.verification.VerifierResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 检查金融任务确实产生了可读取的 Artifact 和最小报告结构。 */
public final class LeverageDashboardTaskVerifier implements TaskVerifier {

    @Override
    public VerifierResult verify(TaskNode task, TaskExecutionResult result) {
        if (!result.success()) {
            String reason = result.error().isBlank() ? result.summary() : result.error();
            return VerifierResult.retry(reason, List.of("task=" + task.id(), "executorSuccess=false"));
        }
        if (result.artifacts().isEmpty()) {
            return VerifierResult.retry("金融任务没有产生 Artifact", List.of("task=" + task.id()));
        }

        for (var artifact : result.artifacts()) {
            Path path = Path.of(artifact.path());
            if (!Files.isRegularFile(path)) {
                return VerifierResult.retry(
                        "Artifact 文件不存在",
                        List.of("task=" + task.id(), "path=" + path)
                );
            }
        }

        if (LeverageDashboardTaskExecutor.WRITE_REPORT.equals(task.id())) {
            Path reportPath = Path.of(result.artifacts().getFirst().path());
            try {
                String report = Files.readString(reportPath, StandardCharsets.UTF_8);
                List<String> requiredSections = List.of(
                        "# 公开可观测 A 股杠杆温度计",
                        "## 最新指标",
                        "## 评分",
                        "## 数据来源",
                        "## 风险与限制"
                );
                List<String> missing = requiredSections.stream()
                        .filter(section -> !report.contains(section))
                        .toList();
                if (!missing.isEmpty()) {
                    return VerifierResult.retry(
                            "报告缺少必要章节",
                            List.of("missing=" + String.join(",", missing))
                    );
                }
            } catch (IOException exception) {
                return VerifierResult.retry(
                        "报告无法读取",
                        List.of("path=" + reportPath, "error=" + exception.getClass().getSimpleName())
                );
            }
        }

        return VerifierResult.pass(
                "金融 Artifact 通过最小结构验证",
                List.of("task=" + task.id(), "artifactCount=" + result.artifacts().size())
        );
    }
}
