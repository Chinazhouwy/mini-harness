package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Context &amp; AgentState 页演示：状态持久化。
 * state 存储就是普通文件：{store}/{userId}/{sessionId}/agent_state.json
 */
@org.junit.jupiter.api.Tag("live")
public class StateTest {

    private static OpenAIChatModel model;
    private static final Path STORE = Path.of("./data/.agentscope-state");
    /** 固定 sessionId，方便去磁盘上找对应的文件 */
    private static final String SESSION = "sStateDemo01";

    // JUnit 在本类测试开始前执行一次初始化；沿用根 config 的本地配置，不把密钥写到代码或日志。
    @BeforeAll
    static void before() throws IOException {
        Properties props = ModelConfig.loadLocalProperties();
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                // false 选择框架的非原生结构化输出路径；普通问答仍正常使用该模型配置。
                .nativeStructuredOutput(false)
                // 这里控制模型响应的流式接收；外层 agent.call 仍可聚合为一个最终 Msg，事件流用 streamEvents 观察。
                .stream(true)
                .build();
    }

    /**
     * 把会话状态落盘后逐字段查看。
     * <p>关键点：stateStore 指定 JsonFileAgentStateStore，userId/sessionId 决定目录；AgentState 不只有聊天文字。
     * <p>观察：查看 context、summary、权限与执行字段；方法会清理本例固定会话目录后重建。
     */
    @Test
    @DisplayName("State 1：跑一次对话，打开磁盘上的 state 文件看里面存了什么")
    public void test01WhatIsStored() throws Exception {
        // 0. 先删掉旧会话目录，保证本次从零开始（state 就是普通文件，随便管）
        Path sessionDir = STORE.resolve("zhouwy").resolve(SESSION);
        if (Files.exists(sessionDir)) {
            try (Stream<Path> paths = Files.walk(sessionDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        // 1. 同一会话连聊两轮，第二轮故意只用代词，逼模型去记忆里找我叫什么、喜欢几
        try (ReActAgent agent = ReActAgent.builder()
                .name("state-demo")
                .sysPrompt("you are a helper")
                .model(model)
                // 文件 Store 按调用的 userId/sessionId 保存和读取状态；新 Java 对象不等于新会话。
                .stateStore(new JsonFileAgentStateStore(STORE))
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId(SESSION).build();

            agent.call(List.of(new UserMessage("我叫周伟，我最喜欢的数字是 7")), ctx).block();
            agent.call(List.of(new UserMessage("我最喜欢的数字乘以 6 等于几？")), ctx).block();
        }

        // 2. 看磁盘结构：{store}/{userId}/{sessionId}/
        System.out.println("== 磁盘结构（" + STORE.relativize(sessionDir) + "）：");
        try (Stream<Path> paths = Files.walk(sessionDir)) {
            paths.forEach(p -> System.out.println("   " + STORE.relativize(p)));
        }

        // 3. 打开 agent_state.json，看每个字段装的什么
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(sessionDir.resolve("agent_state.json").toFile());
        System.out.println("== agent_state.json 的字段：");
        System.out.println("   user_id    = " + root.path("user_id").asText());
        System.out.println("   session_id = " + root.path("session_id").asText());
        System.out.println("   cur_iter   = " + root.path("cur_iter").asInt());
        System.out.println("   summary    = \"" + root.path("summary").asText() + "\"");
        System.out.println("   permission_context.mode = " + root.path("permission_context").path("mode").asText());

        JsonNode context = root.path("context");
        System.out.println("   context（记忆里的消息，共 " + context.size() + " 条）：");
        for (JsonNode m : context) {
            for (JsonNode b : m.path("content")) {
                String type = b.path("type").asText();
                String text = b.path("text").asText("").replace("\n", " ");
                if (text.isBlank()) {
                    System.out.println("     [" + m.path("role").asText() + "] <" + type + ">");
                } else {
                    System.out.println("     [" + m.path("role").asText() + "] <" + type + "> " + text);
                }
            }
        }
    }

    // ================= State 2：自定义 Redis 存储 =================

    /**
     * 最小可用的 Redis 版 AgentStateStore。
     * 存储模型：每个 (userId, sessionId, key) 对应一个 Redis String，值为 JSON 字符串。
     * 序列化用框架自己的 JsonUtils.getJsonCodec()，存的内容和 JsonFileAgentStateStore 完全一致。
     * 注意：demo 直连 localhost:6379；生产环境 KEYS 要换成 SCAN，连接要换成连接池。
     */
    public static class RedisAgentStateStore implements AgentStateStore {

        private final Jedis jedis;
        private final JsonCodec codec = JsonUtils.getJsonCodec();
        private static final String PREFIX = "miniharness:state:";

        public RedisAgentStateStore() {
            this.jedis = new Jedis("localhost", 6379);
        }

        // Redis 键由 userId/sessionId/状态键组成；这些标识来自应用，示例用冒号拼接只为说明存储映射。
        private String k(String userId, String sessionId, String key) {
            return PREFIX + userId + ":" + sessionId + ":" + key;
        }

        @Override
        public void save(String userId, String sessionId, String key, State state) {
            jedis.set(k(userId, sessionId, key), codec.toJson(state));
        }

        @Override
        public void save(String userId, String sessionId, String key, List<? extends State> states) {
            jedis.set(k(userId, sessionId, key), codec.toJson(states));
        }

        @Override
        public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
            String json = jedis.get(k(userId, sessionId, key));
            return json == null ? Optional.empty() : Optional.of(codec.fromJson(json, type));
        }

        @Override
        public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
            String json = jedis.get(k(userId, sessionId, key));
            if (json == null) {
                return List.of();
            }
            // 两步反序列化：先拿到原始 List（元素是 Map），再把每个转回具体类型
            List<?> raw = codec.fromJson(json, List.class);
            return raw.stream()
                    .map(o -> codec.convertValue(o, type))
                    .toList();
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return !jedis.keys(PREFIX + userId + ":" + sessionId + ":*").isEmpty();
        }

        @Override
        public void delete(String userId, String sessionId) {
            jedis.keys(PREFIX + userId + ":" + sessionId + ":*").forEach(jedis::del);
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            Set<String> out = new HashSet<>();
            // miniharness:state:zhouwy:sStateRedis01:agent_state → 第 4 段是 sessionId
            for (String full : jedis.keys(PREFIX + userId + ":*")) {
                out.add(full.split(":")[3]);
            }
            return out;
        }
    }

    /**
     * 观察替换 AgentStateStore 对会话接续的影响。
     * <p>关键点：自定义 Store 把状态序列化到 Redis；重建 Agent 后仍按用户、会话、状态键读取。
     * <p>观察：需本机 Redis；示例使用 KEYS 和直接连接，未演示跨进程重启或生产连接管理。
     */
    @Test
    @DisplayName("State 5：自定义 Redis 存储——换掉 JsonFile，业务代码零改动（需本机 Redis localhost:6379）")
    public void test05RedisStore() {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("zhouwy").sessionId("sStateRedis01").build();

        // 和 State 1 完全相同的两轮对话，唯一区别：.stateStore(...) 换成 Redis 版
        try (ReActAgent agent = ReActAgent.builder()
                .name("redis-demo")
                .sysPrompt("you are a helper")
                .model(model)
                .stateStore(new RedisAgentStateStore())     // ← 唯一改动
                .build()) {
            agent.call(List.of(new UserMessage("我叫周伟，我最喜欢的数字是 7")), ctx).block();
        }

        // 重建 Agent 实例，从 Redis 恢复；这里没有实际重启进程。
        try (ReActAgent agent = ReActAgent.builder()
                .name("redis-demo-2")
                .sysPrompt("you are a helper")
                .model(model)
                .stateStore(new RedisAgentStateStore())
                .build()) {
            Msg ret = agent.call(List.of(new UserMessage("我最喜欢的数字乘以 6 等于几？")), ctx).block();
            System.out.println("重启后回答: " + ret.getTextContent());
        }

        // 看看 Redis 里实际存了什么
        try (Jedis j = new Jedis("localhost", 6379)) {
            System.out.println("== Redis 里的 key：");
            for (String key : j.keys("miniharness:state:*")) {
                String v = j.get(key);
                System.out.println("   " + key + "（" + v.length() + " 字符）");
            }
        }
    }

    // ================= State 2：重建实例后恢复会话 =================

    /**
     * 独立验证关闭旧实例后从文件恢复会话。
     * <p>关键点：先存后读，不依赖其他测试；新 Store/RuntimeContext/Agent 只沿用目录及用户、会话标识。
     * <p>观察：落盘两条消息，新实例答出原任务后变四条；临时目录自动清理，没有重启 JVM。
     */
    @Test
    @DisplayName("State 2：关闭旧 Agent 后，新实例从文件恢复同一会话")
    public void test02SessionRecovery(@TempDir Path stateRoot) throws Exception {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("study-user").sessionId("study-session-001").build();

        // 单独运行即可：先保存，关闭旧实例，不依赖另一个测试留下的文件。
        try (ReActAgent agent = ReActAgent.builder()
                .name("session-study").sysPrompt("请用中文简短回答。")
                .model(model).stateStore(new JsonFileAgentStateStore(stateRoot)).build()) {
            Msg answer = agent.call(List.of(new UserMessage(
                    "今天的学习任务是阅读 ToolCallingAdvisor 源码。请记住，只回复已记住。")), ctx)
                    .block(Duration.ofSeconds(60));
            System.out.println("[AgentScope 首次回答] " + answer.getTextContent());
        }

        // 重新创建 Store 但沿用同一磁盘目录；下方直接读 State 是确认落盘内容，不依赖模型是否碰巧答对。
        var restoredStore = new JsonFileAgentStateStore(stateRoot);
        AgentState saved = restoredStore.get("study-user", "study-session-001", "agent_state", AgentState.class)
                .orElseThrow();
        System.out.println("[文件中的会话] userId=" + saved.getUserId() + "，sessionId=" + saved.getSessionId());
        System.out.println("[重建存储后读取的消息数] " + saved.getContext().size());

        // 新实例只复用文件目录和 userId/sessionId；本例没有实际重启 JVM。
        // 连调用上下文也重新创建，避免把旧对象中框架注入的瞬时状态误当成文件恢复。
        RuntimeContext restoredContext = RuntimeContext.builder()
                .userId("study-user").sessionId("study-session-001").build();
        try (ReActAgent agent = ReActAgent.builder()
                .name("session-study").sysPrompt("请用中文简短回答。")
                .model(model).stateStore(restoredStore).build()) {
            Msg answer = agent.call(List.of(new UserMessage("我刚才说的学习任务是什么？")), restoredContext)
                    .block(Duration.ofSeconds(60));
            System.out.println("[AgentScope 重建后回答] " + answer.getTextContent());
            System.out.println("[接续后的消息数] " + agent.getAgentState("study-user", "study-session-001").getContext().size());
        }
    }

    // ================= State 3：userId / sessionId 的隔离 =================

    /**
     * 观察用户与会话两个维度如何组成状态槽。
     * <p>关键点：同用户不同 session、不同用户相同 session 都应隔离；Agent 对象名不是本例状态槽的唯一标识。
     * <p>观察：对照目录和再次提问的代号；固定目录可能含上次演示的数据。
     */
    @Test
    @DisplayName("State 3：三个会话目录互不相干——test06 坑的存储层解释")
    public void test03Isolation() throws Exception {
        // 三个人设写进三个不同的会话目录（同 user 不同 session / 不同 user 同 session 各占一个）
        String[][] chats = {
                {"zhouwy", "sIsoA", "Alpha"},
                {"zhouwy", "sIsoB", "Beta"},
                {"wang",   "sIsoA", "Gamma"}};

        for (String[] c : chats) {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(c[0]).sessionId(c[1]).build();
            try (ReActAgent agent = ReActAgent.builder()
                    .name("iso-demo").sysPrompt("you are a helper")
                    .model(model)
                    .stateStore(new JsonFileAgentStateStore(STORE))
                    .build()) {
                agent.call(List.of(new UserMessage("记住：我的代号是 " + c[2])), ctx).block();
            }
        }

        // 磁盘：三个目录各存各的
        System.out.println("== 磁盘上的会话目录：");
        try (Stream<Path> paths = Files.walk(STORE, 2)) {
            paths.filter(p -> p.getFileName().toString().startsWith("sIso"))
                    .sorted().forEach(p -> System.out.println("   " + STORE.relativize(p)));
        }

        // 回到各自目录里问：记得谁？——只能记得自己目录里的
        askCodeName("zhouwy", "sIsoA");   // 应该只记得 Alpha，不知道 Beta/Gamma
        askCodeName("zhouwy", "sIsoB");   // 应该只记得 Beta
    }

    private void askCodeName(String user, String session) throws Exception {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(user).sessionId(session).build();
        try (ReActAgent agent = ReActAgent.builder()
                .name("iso-ask").sysPrompt("you are a helper")
                .model(model)
                .stateStore(new JsonFileAgentStateStore(STORE))
                .build()) {
            Msg ret = agent.call(List.of(new UserMessage("我的代号是什么？只回答代号")), ctx).block();
            System.out.println("(" + user + ", " + session + ") 记得的代号: " + ret.getTextContent());
        }
    }

    // ================= State 4：summary 字段什么时候被写 =================

    /** 结构化输出用的 record（test06 同款场景） */
    public record WeatherResponse(String location, String temperature, String condition) {}

    /**
     * 观察结构化输出过程中持久化状态的变化。
     * <p>关键点：nativeStructuredOutput=false 使用框架的合成工具路径，目标 record 决定结构化字段。
     * <p>观察：检查当前状态 JSON 的 summary/context；该字段的变化不等于所有对话都启用了长期记忆摘要。
     */
    @Test
    @DisplayName("State 4：summary 字段——结构化兜底调用前，框架清残留并写摘要")
    public void test04SummaryField() throws Exception {
        String session = "sSummaryDemo01";
        Path sessionDir = STORE.resolve("zhouwy").resolve(session);
        if (Files.exists(sessionDir)) {
            try (Stream<Path> paths = Files.walk(sessionDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        RuntimeContext ctx = RuntimeContext.builder()
                .userId("zhouwy").sessionId(session).build();

        // model 配的是 nativeStructuredOutput(false) → 结构化调用走 generate_response 兜底，
        // 而 fallback 调用前框架会先清掉历史里的结构化残留（compressStructuredOutputContext）
        try (ReActAgent agent = ReActAgent.builder()
                .name("summary-demo").sysPrompt("you are a helper")
                .model(model)
                .stateStore(new JsonFileAgentStateStore(STORE))
                .build()) {

            Msg r1 = agent.call(List.of(new UserMessage("上海今天天气怎么样？")), WeatherResponse.class, ctx).block();
            System.out.println("第 1 次结构化返回: " + r1.getStructuredData(WeatherResponse.class));
            Msg r2 = agent.call(List.of(new UserMessage("北京今天天气怎么样？")), WeatherResponse.class, ctx).block();
            System.out.println("第 2 次结构化返回: " + r2.getStructuredData(WeatherResponse.class));
        }

        // 读磁盘：看 summary 字段 + context 里还剩什么
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(sessionDir.resolve("agent_state.json").toFile());
        System.out.println("== summary = \"" + root.path("summary").asText() + "\"");
        JsonNode context = root.path("context");
        System.out.println("== context 共 " + context.size() + " 条：");
        for (JsonNode m : context) {
            for (JsonNode b : m.path("content")) {
                String text = b.path("text").asText("").replace("\n", " ");
                String brief = text.length() > 60 ? text.substring(0, 60) + "…" : text;
                System.out.println("   [" + m.path("role").asText() + "] <" + b.path("type").asText() + "> " + brief);
            }
        }
    }
}
