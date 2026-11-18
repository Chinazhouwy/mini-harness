package com.chinazhouwy.miniharness.agentscope.study;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.util.JsonUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** 学习用仓库适配器：只处理 SKILL.md，附属资源、认证和缓存策略由后续实际接口另行实现。 */
final class RemoteSkillRepositories {
    private RemoteSkillRepositories() {}

    /** 统一只读接口的样板代码，让两个示例集中展示从哪里取技能。 */
    abstract static class ReadOnlyRepository implements AgentSkillRepository {
        private final String type;
        private final String location;
        private final String source;

        ReadOnlyRepository(String type, String location, String source) {
            this.type = type;
            this.location = location;
            this.source = source;
        }

        AgentSkill parse(String expectedName, String markdown) {
            if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("技能不存在：" + expectedName);
            var parsed = MarkdownSkillParser.parse(markdown);
            if (!expectedName.equals(parsed.getMetadata().get("name"))) {
                throw new IllegalArgumentException("技能名称与仓库条目不一致：" + expectedName);
            }
            return AgentSkill.builder().metadata(parsed.getMetadata()).skillContent(parsed.getContent())
                    .source(source).build();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            // 当前接口要求返回完整 AgentSkill；此处可能已经拉取正文，但模型最初仍只看到技能目录。
            return getAllSkillNames().stream().map(this::getSkill).toList();
        }

        @Override public boolean skillExists(String name) { return getAllSkillNames().contains(name); }
        @Override public String getSource() { return source; }
        @Override public boolean isWriteable() { return false; }
        @Override public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo(type, location, false);
        }
        @Override public boolean save(List<AgentSkill> skills, boolean force) {
            throw new UnsupportedOperationException("本示例仓库只读");
        }
        @Override public boolean delete(String name) { throw new UnsupportedOperationException("本示例仓库只读"); }
        @Override public void setWriteable(boolean value) {
            if (value) throw new UnsupportedOperationException("本示例仓库不能开启写入");
        }
    }

    /** 自定义 HTTP 协议：GET skills 返回名称数组，GET skills/{name} 返回包含 frontmatter 的 Markdown。 */
    static final class HttpSkillRepository extends ReadOnlyRepository {
        private final URI baseUri;
        private final HttpClient client;

        HttpSkillRepository(URI baseUri, String source) {
            super("http", baseUri.toString(), source);
            // 一个实例绑定一个授权范围；真实服务的 baseUri 应指向已鉴权的租户／团队路径。
            if (!baseUri.toString().endsWith("/")) throw new IllegalArgumentException("baseUri 应以 / 结尾");
            this.baseUri = baseUri;
            this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }

        @Override public List<String> getAllSkillNames() {
            return Arrays.asList(JsonUtils.getJsonCodec().fromJson(get("skills"), String[].class));
        }
        @Override public AgentSkill getSkill(String name) {
            // 本示例采用常见的短横线技能名，避免名称被解释成相对路径。
            if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IllegalArgumentException("非法技能名");
            return parse(name, get("skills/" + URLEncoder.encode(name, StandardCharsets.UTF_8)));
        }

        private String get(String path) {
            try {
                System.out.println("[HTTP 获取技能] GET " + baseUri.resolve(path));
                var request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) throw new IllegalStateException("技能服务返回 " + response.statusCode());
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("获取技能被中断", e);
            } catch (IOException e) {
                throw new IllegalStateException("获取技能失败", e);
            }
        }
        @Override public void close() { client.close(); }
    }

    /** 抽出 ConfigService.getConfig 的形状，测试可以替换它的返回值，无需启动 Nacos。 */
    @FunctionalInterface
    interface ConfigReader {
        String getConfig(String dataId, String group) throws Exception;
    }

    /** 普通 Nacos 配置项方案；与官方 NacosSkillRepository 对接 AI Skill ZIP 服务的方案不同。 */
    static final class NacosConfigSkillRepository extends ReadOnlyRepository {
        private final String group;
        private final List<String> names;
        private final ConfigReader reader;

        NacosConfigSkillRepository(String namespace, String group, List<String> names, ConfigReader reader) {
            super("nacos-config", "namespace=" + namespace + ",group=" + group, "nacos-config-" + namespace + "-" + group);
            this.group = group;
            this.names = List.copyOf(names);
            this.reader = reader;
            // namespace 是仓库说明；真正的隔离由传入的 ConfigService 所绑定的 namespace 和服务端权限保证。
        }
        @Override public List<String> getAllSkillNames() { return names; }
        @Override public AgentSkill getSkill(String name) {
            if (!names.contains(name)) throw new IllegalArgumentException("技能不在当前仓库清单：" + name);
            String dataId = "skills." + name + ".md";
            try {
                System.out.println("[配置中心获取技能] dataId=" + dataId + " group=" + group);
                return parse(name, reader.getConfig(dataId, group));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("读取技能配置被中断", e);
            } catch (Exception e) {
                throw new IllegalStateException("读取技能配置失败：" + dataId, e);
            }
        }
    }
}
