# RR Client 固定签名设置

GitHub Actions 不再临时生成签名。第一次构建前必须创建并长期保存同一把 Keystore。

## 1. 本地生成

在 Git Bash、WSL 或 Linux/macOS 终端执行：

```bash
bash scripts/generate_keystore.sh
```

脚本会生成：

```text
local-secrets/rr-client-release.jks
local-secrets/RR_KEYSTORE_BASE64.txt
```

`local-secrets/` 已被 `.gitignore` 排除，不要提交。

## 2. 添加 GitHub Actions Secrets

进入仓库：

```text
Settings → Secrets and variables → Actions → New repository secret
```

建立四项：

```text
RR_KEYSTORE_BASE64
RR_KEYSTORE_PASSWORD
RR_KEY_ALIAS
RR_KEY_PASSWORD
```

取值：

- `RR_KEYSTORE_BASE64`：复制 `local-secrets/RR_KEYSTORE_BASE64.txt` 的完整单行内容。
- `RR_KEYSTORE_PASSWORD`：生成时输入的密码。
- `RR_KEY_ALIAS`：`rrclient`。
- `RR_KEY_PASSWORD`：生成时输入的同一密码。

## 3. 备份

至少离线保存：

```text
rr-client-release.jks
密码
alias=rrclient
证书 SHA-256
```

丢失 Keystore 后，新 APK 无法覆盖安装已经发布的旧 APK。
