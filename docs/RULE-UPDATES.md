# RRBOX 可独立更新的分流规则

支持本协议的 APK 通过设置页“立即更新分流规则”获取仓库维护的规则。第一次接入仍需升级 APK，之后修改已有域名、后缀、精确 IP 例外和代理应用包名数据无需用户重装 APK。核心、转发引擎、新规则格式或 UI 的变化仍需升级应用。

## 维护入口与版本

唯一策略源为 `app/src/main/assets/rules/rrbox-policy.json`。该文件作为离线内置规则，同时参与 ConfigBuilder 的路由和 DNS 配置生成。不要另写一份与 APK 不同的在线策略。

1. 修改规则数据并递增 `ruleVersion`。相同版本必须保持完全相同的文件字节；说明、空白等变化也需要递增版本。
2. 更新相关回归案例，覆盖需要修正的目的地和不能受影响的邻近域名。包名仅限精确匹配；不要为某一应用放宽整个共享云域名或整个 IP 网段。
3. 推送受支持的源分支。现有 CI 会完成全部 JVM、Lint、真实核心分流、Root TCP/UDP 和 HEV 验证，并验证原 APK 签名。
4. 所有门禁通过后，同一工作流将实际测试的策略 JSON、两个中国 SRS 文件和签名清单发布为独立规则版本。用户点击按钮即可取得。

源分支 `main` 和 `feat/domestic-routing-wechat-douyin` 可以发布当前规则频道。其他构建分支不会移动频道。此功能不发布、合并或更新正式版 APK；规则 Release 始终为 prerelease，`make_latest=false`。

`bundleVersion = GITHUB_RUN_ID * 100 + GITHUB_RUN_ATTEMPT`。它是每次完整规则包的递增标识，区别于策略自身的 `ruleVersion`。中国规则更新、策略字节不变时，允许只增加 bundleVersion。构建重跑获得新 bundleVersion，已有 Release 不被覆写。回滚分类数据时，将审核过的旧内容发布为一个更高 ruleVersion，客户端不接受版本下降。

## 发布物与信任

`rules-channel` 是独立数据分支，包含频道指针与签名规则包的镜像，不在 APK 构建触发分支列表内：

```json
{"schemaVersion":1,"bundleVersion":3419782534801}
```

客户端从 `https://raw.githubusercontent.com/Xiaowu7z/RR-BOX/rules-channel/channel.json` 获取频道，备用为该分支的 jsDelivr 镜像。频道只允许版本数字，不能指定任意 URL。客户端据此构造固定仓库 Release 路径 `rules-v1-<bundleVersion>`，以及数据分支 `bundles/<bundleVersion>/<filename>` 的 raw GitHub 和 jsDelivr 路径。各来源必须通过相同签名和摘要校验；镜像不是新的信任来源。

每个 Release 有且仅需四个文件：

- `rrbox-policy.json`
- `geosite-geolocation-cn.srs`
- `geoip-cn.srs`
- `bundle-manifest.json`

清单 envelope 有 `schemaVersion`、`payload`、`signature`、`certificate` 四个字段；后三者使用规范 Base64。payload 为原始 UTF-8 JSON 字节，包含固定的文件名、长度、SHA-256、规则包和策略版本、发布时间、来源提交、最低应用版本、策略格式版本及固定核心版本 `1.14.0`。签名算法为 `SHA256withRSA`。

签名继续使用现有 APK 的原始 RSA 4096 位证书；证书 SHA-256 固定为 `fe1368cf16ee9e8b56199655d0b1e2606a6ec9b8f3d4ac5e16e8cf66e180d816`。工作流通过现有 `RR_KEYSTORE_*` secrets 读取密钥，Java helper 在 keystore 内使用私钥完成签名，不导出私钥。客户端先验证固定证书和原始 payload 签名，再解释清单。HTTPS 或本地哈希不能替代该签名。

发布脚本先验证已有频道签名及版本关系，然后创建草稿 Release，上传全部文件并核对 GitHub 返回的长度和摘要。全部就绪后公开规则 Release，将完全相同的四个文件写入数据分支的版本目录，并把频道指针与该目录一起原子提交，最后以非强制 git 更新移动频道。上传损坏、构建失败或并发旧构建都不会覆盖当前频道。若发布中断留下草稿，用重跑生成新版本，不要修改已公开版本。

数据分支保留旧版本目录，避免缓存旧频道的客户端找不到对应文件；Git 历史也会随规则发布增长。不要单独改写已经发布的目录。raw 与 jsDelivr 回退使 Root 排除 RRBOX 自身 UID 时，更新仍有直连可用来源；若所有来源都不可达，客户端保留旧规则并报告更新失败。

## 客户端边界

客户端只接受数据字段，不接受远程脚本、出站节点、Root 命令或完整核心配置。策略 JSON 上限 1 MiB，SRS 各 8 MiB，签名清单 128 KiB；格式、兼容性、签名、完整性和核心规则解析都须通过。

DNS 与路由必须从同一代规则快照生成。下载或验证失败时保留当前版本；运行时更新采用候选启动确认和旧版恢复，启动失败不能把失败候选记为生效。用户停止连接或切换节点后，迟到的候选不能重新启动之前的连接。内置规则仍是离线启动的最后保底。

System 和 Root 可以在取得应用身份时按包名匹配。HEV 无原始应用身份时继续使用同一规则包的域名/IP 部分；更新规则不会凭空补出 UID。正确选路也不能证明每个服务登录或响应成功，应结合连接错误、收发记录与手机实际操作排查。

远程规则更新是手动动作。加载新规则可能产生一次短暂重连，不承诺已有长连接完全无感。签名和回退可防损坏或不兼容更新，但不能证明分类策略本身绝对正确，因此仍需回归案例和设备测试。

## JARVIS 应用直连

schema 1 仅支持 `proxyPackageGroups`，没有直连包名字段。1.0.2 将用户明确指定的 `app.jarvis.assistant` 作为 APK 内置的精确直连规则；不通过远程数据扩充权限或改动本机“选中绕过”列表。智能分流开启时，它在 DNS 接管之后、X 地址恢复及代理分流之前生效。System / Root 需要能取得真实应用身份，HEV 无身份时使用域名策略。

策略版本 `2026090803` 新增 `deepseek-api` 精确域名组，列出 `api.deepseek.com` → DIRECT，连接和 DNS 共用这条策略。既有 `deepseek.com` 直连后缀也覆盖此域名。继续使用 schema 1 和原有最低应用版本，旧 APK 可更新域名规则；JARVIS 包名规则仍需覆盖安装 1.0.2 并重新连接。独立版本的应急规则基线保持不变。

## X 连接目的地址恢复

`x-destination-recovery` 是 schema 1 中新增的保留域名组 ID。该组只能为 `PROXY`、`suffixes` 必须为空、`domains` 只列精确主机名。它仍是数据清单，不接受任意重定向地址或核心动作。支持该功能的 APK 将匹配的 HTTP / TLS / QUIC 请求恢复到**同一个域名**，使用现有 `dns-remote` 重新解析后，继续原来的应用和域名分流；不修改端口。

这是针对关闭 Root 期间取得的地址在重新接管后仍被应用沿用的恢复措施。单独的域名嗅探只帮助选路，不会自动替换已有目的 IP；单独对 IP 目的执行 `resolve` 也不能解决此问题。实现须先使用 `route-options.override_address` 恢复域名，再进行限定范围的解析，同时保留 UDP 回包到原应用地址的映射。

只处理当前策略确认为代理的精确域名，保护私网与特殊地址，排除节点自举域名。智能分流关闭、没有可识别主机名、未列入清单或未识别为上述协议时，维持原有路径。ECH 隐藏真实主机名或应用的旧连接一直不重试时，不能承诺自动恢复。该措施不清全机缓存、不杀应用、不重设 Android DNS，也不更改 Root 路由清理方式。

首次使用需要包含该恢复能力的新 APK，以及包含此组的规则（初始策略版本 `2026090802`）。已安装用户应点击“立即更新分流规则”并确认新策略版本；覆盖安装不会擅自替换已保存的规则。旧 APK 可读取同一 schema 1 规则包，将此组视为普通代理域名分类，因此仅更新规则不会使旧 APK 获得地址恢复能力。之后维护精确域名清单仍可通过规则按钮更新。

设备回归：保持同一节点，Root 关闭期间打开 X；随后仅通过快捷按钮重新开启 Root，刷新 X 内容与媒体，并导出全部日志。与 System / HEV 对照。核心测试必须检查最终代理目的地址和 UDP 回包地址，不能仅把命中 `proxy` 或有字节计数当作业务成功。

## ChatGPT 与自动选择（1.0.3）

策略 `2026091101` 补充 [OpenAI 官方网络说明](https://help.openai.com/zh-hans-cn/articles/9247338-network-recommendations-for-chatgpt-errors-on-web-and-apps) 列出的域名，沿用 schema 1，连接与 DNS 共用代理优先级。共享服务仅列精确主机，不扩大到整个 Cloudflare、Stripe、Sentry 或其他共享云平台。HTTPS / WebSocket 保持透传，不以关闭证书验证处理网络错误。

“自动选择”的常用应用目录属于 APK 功能，独立于强制代理的包名策略。点击时补充当前已激活规则中的代理包名组，与本机已安装应用求交集并保留手动添加/取消；在线规则更新不会自行改变接管范围，需用户下次点击自动选择。
