#!/usr/bin/env bash
set -e

echo "=== RR Client 安全签名生成工具 ==="
KEYSTORE_FILE="release.keystore"
ALIAS="rrclient"

if [ -f "$KEYSTORE_FILE" ]; then
    echo "错误: $KEYSTORE_FILE 已存在于当前目录，避免覆盖。"
    exit 1
fi

read -s -p "请输入新的 Keystore 密码: " PASS
echo
read -s -p "请再次输入密码确认: " PASS_CONFIRM
echo

if [ "$PASS" != "$PASS_CONFIRM" ]; then
    echo "两次密码输入不一致！"
    exit 1
fi

keytool -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -dname "CN=RR Client, OU=Engineering, O=RR, L=Tokyo, C=JP"

echo
echo "✓ 证书生成成功: $KEYSTORE_FILE"
echo "=================================================="
echo "请在 GitHub 仓库 -> Settings -> Secrets and variables -> Actions 中添加以下 Secrets:"
echo "1. RR_KEYSTORE_BASE64: (将下面这串 Base64 内容填入)"
base64 -w 0 "$KEYSTORE_FILE" 2>/dev/null || base64 "$KEYSTORE_FILE"
echo
echo "2. RR_KEYSTORE_PASSWORD: 你的密码"
echo "3. RR_KEY_ALIAS: $ALIAS"
echo "4. RR_KEY_PASSWORD: 你的密码"
echo "=================================================="
echo "注意: 请妥善保存 $KEYSTORE_FILE，绝不要提交到 Git 仓库！"
