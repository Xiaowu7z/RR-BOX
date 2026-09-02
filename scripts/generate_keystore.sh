#!/usr/bin/env bash
set -euo pipefail

umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
SECRET_DIR="$PROJECT_ROOT/local-secrets"
KEYSTORE_FILE="$SECRET_DIR/rr-client-release.jks"
BASE64_FILE="$SECRET_DIR/RR_KEYSTORE_BASE64.txt"
ALIAS="rrclient"

mkdir -p "$SECRET_DIR"

if [[ -e "$KEYSTORE_FILE" || -e "$BASE64_FILE" ]]; then
  echo "错误：签名文件已经存在，拒绝覆盖："
  echo "  $KEYSTORE_FILE"
  echo "  $BASE64_FILE"
  exit 1
fi

read -r -s -p "请输入新的 Keystore 密码（至少 12 个字符）: " password
echo
read -r -s -p "请再次输入密码确认: " password_confirm
echo

if [[ "$password" != "$password_confirm" ]]; then
  echo "错误：两次密码输入不一致。"
  exit 1
fi

if (( ${#password} < 12 )); then
  echo "错误：密码长度必须至少为 12 个字符。"
  exit 1
fi

keytool -genkeypair \
  -keystore "$KEYSTORE_FILE" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$password" \
  -keypass "$password" \
  -dname "CN=RR Client, OU=Engineering, O=RR, L=Tokyo, C=JP"

if base64 --help 2>&1 | grep -q -- '-w'; then
  base64 -w 0 "$KEYSTORE_FILE" > "$BASE64_FILE"
else
  base64 "$KEYSTORE_FILE" | tr -d '\r\n' > "$BASE64_FILE"
fi
printf '\n' >> "$BASE64_FILE"

certificate_sha256="$(
  keytool -list -v \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$password" \
    -alias "$ALIAS" \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' \
    | head -n 1
)"

unset password password_confirm

echo
echo "签名已生成并保存在仅本地目录："
echo "  Keystore: $KEYSTORE_FILE"
echo "  Base64:   $BASE64_FILE"
echo "  Alias:    $ALIAS"
echo "  Certificate SHA-256: $certificate_sha256"
echo
echo "请在 GitHub 仓库 Settings -> Secrets and variables -> Actions 中创建："
echo "  RR_KEYSTORE_BASE64    = $BASE64_FILE 的单行内容"
echo "  RR_KEYSTORE_PASSWORD  = 刚才输入的密码"
echo "  RR_KEY_ALIAS           = $ALIAS"
echo "  RR_KEY_PASSWORD        = 刚才输入的密码"
echo
echo "不要提交 local-secrets/，不要丢失原始 Keystore；以后覆盖安装必须继续使用同一把签名。"
