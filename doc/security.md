# 安全设计

## 认证与会话

### 学生 / Admin
- **登录**：bcrypt(cost=12) 验证密码；成功后发 32 字节 SecureRandom token
- **会话**：内存 ConcurrentHashMap（SessionStore），TTL 30 分钟滑动续期；重启失效
- **登出**：双清（SessionStore + 审计缓存），token 立即不可复用
- **重置密码/改密**：成功后 `SessionStore.invalidateByPrincipal` 使该用户全部会话失效

### 服务间（Java ↔ Python / Java ↔ Java）
- 共享 `API_SERVICE_TOKEN`（X-Api-Token 头）；Python 用 `secrets.compare_digest`、Java 用 `MessageDigest.isEqual` 恒时比较
- Java 侧 RestTemplate/WebClient 拦截器统一注入；ApiAuthFilter 对合法 X-Api-Token 放行（vector delete 等内部调用）

### 前端 → 后端
- 学生：`X-Student-Token`；Admin：`X-Admin-Token`
- 浏览器不持有服务间 token：dev 由 vite proxy 注入，prod 由 nginx envsubst 注入

## 鉴权过滤（ApiAuthFilter，common 模块）

所有 `/api/*` 请求先过 filter：
- **公开**：login/register/forgot/reset-password、admin login、/health
- **只读**（未登录可访问，设计如此）：文件树/预览/raw/向量检索——题库为公开浏览资源
- **其余**：需有效会话；controller 二次校验（纵深防御）

## 漏洞防护清单

| 威胁 | 防护 |
|---|---|
| **IDOR（读他人会话）** | chat ask/history 的 sessionId 强制 `学号_` 前缀校验 |
| **路径穿越** | 所有文件操作 `Path.normalize()` + `startsWith(root)` 校验（Java 与 Python `Path.is_relative_to`） |
| **任意路径扫描** | scan/reparse 的 targetPath 必须限题库根目录内 |
| **SSRF / key 窃取** | llm-gateway `base_url`：预设供应商严格域名白名单；自定义供应商允许公网 https 域名但禁 IP/私网/localhost/元数据地址；env key 仅预设 provider 兜底。Java 侧 `X-Api-Token` 恒时比较放行服务间调用 |
| **服务白嫖** | llm-gateway 60 次/分/IP 限流；Python 服务全部 token 鉴权；注册接口 5 次/分/IP 限流（防批量刷号） |
| **登录爆破** | 登录/找回/改密均 5 次/分/IP 限流 |
| **邮箱轰炸** | forgot-password 限流 |
| **XSS** | React 默认转义；react-markdown 无 rehypeRaw；CSP header；moderateContent 输入过滤 |
| **SQL 注入** | 全 Spring Data 派生查询，无原生 SQL |
| **CSRF** | token 走 header 非 cookie，天然免疫 |
| **敏感信息泄露** | 配置接口密码脱敏（******）；日志 token 掩码；异常不返 e.getMessage() |
| **密钥泄露到仓库** | .gitignore 排除 data/、.env*、target/、node_modules/ |

## 数据安全

- **密码**：bcrypt cost=12，DB 不存明文
- **SMTP 授权码**：明文存 system_configs（脱敏返回）；DB 泄露即泄露授权码——生产建议数据库本身受防火墙保护
- **题库内容外发**：多模态提问时，题库页图会发给所选第三方供应商——属预期功能，选择供应商时注意
- **重置 token**：32 字节 SecureRandom，1 小时过期，用后即焚（DB 清空）

## 已知边界（接受的风险）

1. **内存会话/限流**：重启即失（单机场景可接受）
2. **题库只读公开**：未登录可浏览文件树/下载原文/向量检索（设计如此，题库非机密）
3. **embedding hash 降级**：无 key 时语义检索失效（不会误答，降级为关键词；hash 伪嵌入已加速为单种子 PRNG）
4. **RBAC 角色**：EDITOR/VIEWER 与 ADMIN 权限相同（角色字段存在但未细分）——如需细分在 AdminAuthController 各端点加角色判断
5. **服务绑定 0.0.0.0**：本机/内网场景；生产应置于防火墙/网关后，仅暴露 80
