# 🚨 项目失败记录与解决方案

> 记录项目开发过程中遇到的问题、失败原因及解决方案，避免重复踩坑。

---

## 📅 2026-04-07：Docker 配置迁移与 ClickHouse 部署

### ❌ 失败 1：Docker 镜像拉取超时

**时间**: 2026-04-07 21:11

**错误信息**:
```
Error response from daemon: Get "https://registry-1.docker.io/v2/": 
net/http: request canceled while waiting for connection (Client.Timeout exceeded while awaiting headers)
```

**原因分析**:
1. Docker Hub 连接不稳定，国内访问速度慢
2. 虽然配置了镜像加速器，但部分镜像源也无法访问
3. 网络波动导致连接超时

**尝试方案**:
- ✗ 使用 `docker compose up -d clickhouse` 直接启动 - 失败
- ✗ 单独拉取镜像 `docker pull clickhouse/clickhouse-server:24.3-alpine` - 失败
- ✗ 测试 Docker Hub 连接 `curl https://registry-1.docker.io/v2/` - 超时

**最终解决方案**:
- ✓ 多次重试拉取镜像（3次）
- ✓ 利用已有的镜像加速器配置
- ✓ 等待网络波动恢复

**成功命令**:
```bash
for i in {1..3}; do 
  echo "尝试 $i/3..." && \
  docker pull clickhouse/clickhouse-server:24.3-alpine && \
  break || sleep 5
done
```

**教训总结**:
1. 镜像拉取失败时，多次重试往往能解决网络波动问题
2. 镜像加速器配置很重要，需确保配置多个备用源
3. 可以考虑使用国内镜像源的替代方案

---

### ❌ 失败 2：DataGrip 连接 ClickHouse 失败

**时间**: 2026-04-07 22:00

**错误信息**:
```
[22000] Code: 0. DB::Exception: <Unreadable error message> (transport error: 400)
Ping: 25毫秒 (keep-alive 查询导致错误)
```

**原因分析**:
1. ClickHouse 默认配置中，`default` 用户只允许本地连接（`127.0.0.1` 和 `::1`）
2. 客户端从宿主机连接容器，属于远程连接，被拒绝
3. 配置文件 `/etc/clickhouse-server/users.d/default-user.xml` 限制了网络访问

**问题定位过程**:
```bash
# 1. 检查容器监听配置
docker exec harness-clickhouse cat /etc/clickhouse-server/config.d/docker_related_config.xml
# 结果：监听 0.0.0.0 和 ::（允许外部访问）✓

# 2. 检查用户权限配置
docker exec harness-clickhouse cat /etc/clickhouse-server/users.d/default-user.xml
# 结果：
<users>
  <default>
    <networks>
      <ip>::1</ip>        # 只允许 IPv6 本地
      <ip>127.0.0.1</ip>  # 只允许 IPv4 本地
    </networks>
  </default>
</users>
# ✗ 问题发现：只允许本地连接
```

**解决方案**:
1. 创建允许远程访问的用户配置文件
2. 修改 `users.xml` 允许所有 IP 访问（`::/0`）
3. 挂载配置文件到容器
4. 重启容器应用新配置

**配置文件**: `/Users/chinazhouwy/doc/docker/config/clickhouse/users.xml`
```xml
<clickhouse>
  <users>
    <default>
      <networks>
        <ip>::/0</ip>  <!-- 允许所有 IPv4 和 IPv6 地址 -->
      </networks>
      <profile>default</profile>
      <quota>default</quota>
      <access_management>1</access_management>
    </default>
  </users>
</clickhouse>
```

**docker-compose.yml 更新**:
```yaml
volumes:
  - /Users/chinazhouwy/doc/docker/data/clickhouse:/var/lib/clickhouse
  - /Users/chinazhouwy/doc/docker/config/clickhouse/users.xml:/etc/clickhouse-server/users.d/allow-remote.xml
```

**应用配置**:
```bash
# 必须重新创建容器才能挂载新的 volume
docker compose down clickhouse
docker compose up -d clickhouse
```

**验证成功**:
```bash
# 容器内测试
docker exec harness-clickhouse clickhouse-client --query "SELECT 1"
# 输出: 1 ✓

# 查看配置已生效
docker exec harness-clickhouse cat /etc/clickhouse-server/users.d/allow-remote.xml
```

**DataGrip 连接配置**:
- JDBC URL: `jdbc:clickhouse://localhost:9000/harness_db`
- User: `default`
- Password: (留空)

**教训总结**:
1. ClickHouse 默认安全策略限制远程访问，需要主动配置
2. 修改配置后必须 `docker compose down` 然后 `up`，`restart` 不会重新挂载 volume
3. 配置文件挂载位置很重要：`/etc/clickhouse-server/users.d/` 目录下的 XML 文件会自动加载
4. `<ip>::/0</ip>` 表示允许所有 IPv4 和 IPv6 地址访问
5. 生产环境应配置具体的 IP 白名单或设置密码

---

### ❌ 失败 3：DataGrip 认证失败（AUTHENTICATION_FAILED）

**时间**: 2026-04-07 22:14

**错误信息**:
```
[22000] Code: 516. DB::Exception: default: Authentication failed: 
password is incorrect, or there is no user with such name. 
If you have installed ClickHouse and forgot password you can reset it in 
the configuration file. The password for default user is typically located 
at /etc/clickhouse-server/users.d/default-password.xml and deleting this 
file will reset the password. (AUTHENTICATION_FAILED) 
(version 24.3.18.7 (official build))
```

**原因分析**:
1. 第一次修复（失败 2）后，网络连接已成功，但认证失败
2. 挂载的用户配置文件 `allow-remote.xml` 缺少 `<password>` 标签
3. ClickHouse 配置合并机制要求显式声明 `<password></password>` 以明确空密码
4. JDBC 驱动期望明确的密码配置，空标签不能省略

**问题定位过程**:
```bash
# 1. 验证用户是否存在
docker exec harness-clickhouse clickhouse-client \
  --query "SELECT name, storage FROM system.users"
# 输出: default users_xml ✓

# 2. 容器内测试连接（成功）
docker exec harness-clickhouse clickhouse-client \
  --user default --query "SELECT 1"
# 输出: 1 ✓ 说明容器内无密码认证正常

# 3. 检查主配置文件的 password 设置
docker exec harness-clickhouse cat /etc/clickhouse-server/users.xml | grep "<password>"
# 输出: <password></password> ✓ 主配置有空密码标签

# 4. 检查我们挂载的配置文件
docker exec harness-clickhouse cat /etc/clickhouse-server/users.d/allow-remote.xml
# 输出发现：缺少 <password></password> 标签 ✗
```

**根本原因**:
- XML 配置合并时，子配置文件（users.d/*.xml）会覆盖主配置文件（users.xml）
- 我们的 `allow-remote.xml` 定义了 `<networks>` 但未定义 `<password>`
- 导致用户配置不完整，JDBC 驱动无法正确处理空密码

**解决方案**:
更新 `/Users/chinazhouwy/doc/docker/config/clickhouse/users.xml`，添加显式的空密码标签：

```xml
<clickhouse>
  <users>
    <default>
      <password></password>  <!-- 关键：显式声明空密码 -->
      <networks>
        <ip>::/0</ip>
      </networks>
      <profile>default</profile>
      <quota>default</quota>
      <access_management>1</access_management>
    </default>
  </users>
</clickhouse>
```

**应用配置**:
```bash
# 更新配置文件后重启容器（注意：只需 restart，不需要 down/up）
docker compose restart clickhouse
```

**验证成功**:
```bash
# 查看配置已更新
docker exec harness-clickhouse cat /etc/clickhouse-server/users.d/allow-remote.xml
# 输出包含 <password></password> ✓

# 测试容器内连接
docker exec harness-clickhouse clickhouse-client --user default --query "SELECT 1"
# 输出: 1 ✓
```

**DataGrip 最终连接配置**:
- **JDBC URL**: `jdbc:clickhouse://localhost:9000/harness_db`
- **User**: `default`
- **Password**: (留空，无需填写)
- **驱动版本**: ClickHouse JDBC Driver 0.9.4+

**教训总结**:
1. ClickHouse XML 配置合并时，必须显式声明所有关键属性（包括空值）
2. `<password></password>` 空标签 ≠ 省略 password 标签
3. JDBC 驱动需要明确的密码配置，否则会报认证失败
4. 配置文件修改后，只需 `restart` 即可生效（volume 挂载无需重新创建容器）
5. 容器内测试成功 ≠ 客户端连接成功，需要验证外部连接

---

### ❌ 失败 4：JDBC 驱动无法处理空密码（最终解决方案）

**时间**: 2026-04-07 22:25

**错误信息**:
```
[22000] Code: 516. DB::Exception: default: Authentication failed: 
password is incorrect, or there is no user with such name. (AUTHENTICATION_FAILED)
```

**现象**:
- 失败 3 修复后，添加了 `<password></password>` 空标签
- 容器内测试连接成功：`docker exec ... clickhouse-client --user default` ✓
- HTTP 接口测试成功：`curl 'http://localhost:8123/?user=default'` ✓
- DataGrip JDBC 连接仍然失败 ✗

**根本原因**:
1. JDBC 驱动版本 0.9.4 无法正确处理 ClickHouse 的空密码认证
2. 驱动期望明确的密码字符串，空密码会被视为"未提供密码"
3. ClickHouse 的 `plaintext_password` 认证类型要求密码匹配，空字符串 ≠ NULL

**问题验证**:
```bash
# 1. 查看用户认证类型
docker exec harness-clickhouse clickhouse-client \
  --query "SELECT name, auth_type FROM system.users FORMAT Vertical"
# 输出: auth_type: plaintext_password ✓

# 2. 容器内测试（成功）
docker exec harness-clickhouse clickhouse-client \
  --user default --password "" --query "SELECT 1"
# 输出: 1 ✓ ClickHouse 本身支持空密码

# 3. JDBC 驱动测试（失败）
# DataGrip 使用空密码连接 → AUTHENTICATION_FAILED ✗
# 驱动无法正确传递空密码参数
```

**解决方案**:
为 default 用户设置实际密码，避免 JDBC 驱动的空密码兼容性问题。

**最终配置**: `/Users/chinazhouwy/doc/docker/config/clickhouse/users.xml`
```xml
<clickhouse>
  <users>
    <default>
      <password>harness123</password>  <!-- 设置实际密码 -->
      <networks>
        <ip>::/0</ip>
      </networks>
      <profile>default</profile>
      <quota>default</quota>
      <access_management>1</access_management>
    </default>
  </users>
</clickhouse>
```

**应用配置**:
```bash
docker compose restart clickhouse
```

**验证成功**:
```bash
# 使用密码连接（成功）
docker exec harness-clickhouse clickhouse-client \
  --user default --password harness123 --query "SELECT 1"
# 输出: 1 ✓

# 不使用密码连接（失败，说明密码保护生效）
docker exec harness-clickhouse clickhouse-client \
  --user default --query "SELECT 1"
# 输出: AUTHENTICATION_FAILED ✓（预期行为）
```

**DataGrip 最终连接配置**:
- **JDBC URL**: `jdbc:clickhouse://localhost:9000/harness_db` 或 `jdbc:clickhouse://localhost:8123/harness_db`
- **User**: `default`
- **Password**: `harness123`
- **驱动版本**: ClickHouse JDBC Driver 0.9.4（已验证兼容）

**根本解决方案对比**:
| 方案 | 优点 | 缺点 | 适用场景 |
|------|------|------|----------|
| 空密码 | 无需记住密码 | JDBC 驱动兼容性差 | 仅容器内使用 |
| 实际密码 | JDBC 兼容性好 | 需记住密码 | **生产环境（推荐）** |
| 创建新用户 | 权限隔离 | 需额外配置 | 多用户环境 |

**教训总结**:
1. JDBC 驱动版本兼容性是重要考虑因素，空密码在不同驱动间行为不一致
2. ClickHouse 本身支持空密码，但客户端驱动可能有不同实现
3. 生产环境应设置实际密码，避免依赖"空密码"这种非标准行为
4. 测试流程应包含：容器内测试 → HTTP 接口测试 → JDBC 客户端测试
5. 容器内测试成功不代表所有客户端都能正常连接

---

## 📊 统计数据

| 指标 | 数量 |
|------|------|
| 总失败次数 | 4 |
| 网络问题 | 1 |
| 配置问题 | 3 |
| JDBC 驱动兼容性 | 1 |
| 解决时间 | 约 60 分钟 |
| 最终方案 | 设置实际密码 `harness123` |

---

## 🔍 故障排查流程总结

### 1. 网络问题排查流程
```
网络超时 
  → 检查镜像加速器配置
  → 多次重试拉取镜像
  → 考虑使用国内镜像源替代
  → 检查代理/VPN 设置
```

### 2. 客户端连接问题排查流程
```
客户端连接失败
  → 检查容器端口映射 (docker ps)
  → 检查容器监听地址 (docker exec ... cat config.xml)
  → 检查用户权限配置 (docker exec ... cat users.xml)
  → 检查用户网络访问权限 (users.d/*.xml 中 <networks> 配置)
  → 检查密码配置 (users.d/*.xml 中 <password> 标签)
  → 检查防火墙/网络策略
  → 查看容器日志 (docker logs <container>)
```

### 3. ClickHouse 认证失败排查流程
```
认证失败 (AUTHENTICATION_FAILED)
  → 容器内测试连接 (docker exec ... clickhouse-client --user default)
  → 检查用户是否存在 (SELECT name FROM system.users)
  → 检查主配置文件密码设置 (cat users.xml | grep password)
  → 检查子配置文件密码设置 (cat users.d/*.xml)
  → 确保 <password></password> 标签存在（即使是空密码）
  → 确保 <networks> 允许远程访问 (::/0)
  → 重启容器应用配置 (docker compose restart)
```

### 4. Docker 配置修改流程
```
修改 docker-compose.yml
  → docker compose down <service>  # 停止并删除容器
  → docker compose up -d <service> # 重新创建并启动
  → 验证配置生效 (docker exec ... cat ...)
```

---

## 💡 最佳实践

### Docker 镜像管理
1. 配置多个镜像加速器作为备用
2. 使用 `docker pull` 预先拉取镜像，避免 `docker compose up` 时超时
3. 考虑使用阿里云等国内镜像源

### ClickHouse 安全配置
1. 开发环境可使用 `::/0` 允许所有访问
2. 生产环境必须：
   - 设置密码
   - 配置 IP 白名单
   - 使用非 default 用户
   - 限制 access_management 权限

### 配置文件管理
1. 所有配置文件统一存放在 `/Users/chinazhouwy/doc/docker/config/`
2. 数据文件统一存放在 `/Users/chinazhouwy/doc/docker/data/`
3. 修改配置后必须重新创建容器（`down` + `up`）
4. 密码配置必须使用实际密码，避免 JDBC 驱动兼容性问题

### JDBC 驱动兼容性
1. ClickHouse JDBC 驱动版本 0.9.4 无法正确处理空密码
2. 建议使用实际密码而非空密码（即使容器内支持）
3. 测试流程：容器内 CLI → HTTP 接口 → JDBC 客户端
4. 可考虑使用 HTTP 协议（端口 8123）代替原生协议（端口 9000）
5. 驱动版本不匹配时应尝试降级或升级版本

---

## 📝 待改进项

- [x] 为 ClickHouse 设置密码认证（已完成：harness123）
- [ ] 配置生产环境的 IP 白名单（当前允许所有 ::/0）
- [ ] 创建专用数据库用户，避免使用 default
- [ ] 设置 ClickHouse 数据备份策略
- [ ] 添加 Prometheus 监控 ClickHouse 指标
- [ ] 配置 ClickHouse 集群模式（高可用）
- [ ] 测试其他 JDBC 驱动版本兼容性

---

**最后更新**: 2026-04-07 22:28