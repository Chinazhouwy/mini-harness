package com.chinazhouwy.miniharness.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
public class StateTest {

    private static OpenAIChatModel model;
    private static final Path STORE = Path.of("./data/.agentscope-state");
    /** 固定 sessionId，方便去磁盘上找对应的文件 */
    private static final String SESSION = "sStateDemo01";

    @BeforeAll
    static void before() throws IOException {
        Properties props = new Properties();
        try (InputStream in = StateTest.class.getClassLoader()
                .getResourceAsStream("openrouter.local.properties")) {
            props.load(in);
        }
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .stream(true)
                .build();
    }

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

        // 重新 new agent（等价于进程重启，内存全丢）——记忆应该从 Redis 里捡回来
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

    // ================= State 2：跨进程恢复 =================

    @Test
    @DisplayName("State 2：跨进程恢复——新 agent 实例从磁盘捡回上一个进程的记忆")
    public void test02SessionRecovery() throws Exception {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("zhouwy").sessionId(SESSION).build();   // 复用 State 1 的会话

        // 这个实例没经历过 State 1 的对话（新 new 的对象，内存里一片空白），
        // 它能答出来，唯一的途径是启动时从磁盘上的 agent_state.json 读回 context
        try (ReActAgent agent = ReActAgent.builder()
                .name("recovery-demo")
                .sysPrompt("you are a helper")
                .model(model)
                .stateStore(new JsonFileAgentStateStore(STORE))
                .build()) {

            Msg ret = agent.call(List.of(new UserMessage(
                    "不查任何资料，直接凭我们之前的记忆回答：我最喜欢的数字是几？")), ctx).block();
            System.out.println("新实例回答: " + ret.getTextContent());
        }

        // 铁证：context 里第一条消息的时间戳 + 文件修改时间，都是上一个进程留下的
        Path stateFile = STORE.resolve("zhouwy").resolve(SESSION).resolve("agent_state.json");
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(stateFile.toFile());
        System.out.println("context 第 1 条消息的写入时间: " + root.path("context").get(0).path("timestamp").asText());
        System.out.println("state 文件最后修改时间:       " + Files.getLastModifiedTime(stateFile));
    }

    // ================= State 3：userId / sessionId 的隔离 =================

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
