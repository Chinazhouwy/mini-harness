package com.chinazhouwy.feishubot;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 统一项目根目录：默认从 class/jar 位置向上找 .git，也可通过 -Dminiharness.home 覆盖。 */
public final class ProjectPaths {
    public static final String HOME_PROPERTY = "miniharness.home";

    private ProjectPaths() {
    }

    public static Path home() {
        String configured = System.getProperty(HOME_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path current = codeLocation();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        // 找不到 .git 时退回当前目录，至少与启动脚本的 cd 行为一致。
        return Path.of("").toAbsolutePath().normalize();
    }

    private static Path codeLocation() {
        try {
            return Paths.get(ProjectPaths.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return Path.of("").toAbsolutePath().normalize();
        }
    }
}
