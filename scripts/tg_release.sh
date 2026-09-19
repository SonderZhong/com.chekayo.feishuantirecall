#!/usr/bin/env bash
# 电报群同步发布: 公告 + APK + 置顶 (配合 make_release.sh 之后的最后一步)
#
# 用法: scripts/tg_release.sh <apk文件> <公告md/html文件>
#   公告文件内容为 Telegram HTML parse_mode 文本 (标题加 <b>、列表用 ·)
#
# 依赖:
#   - token 存于 ~/.fucklark_tg_token (chmod 600, 不入库)
#   - 本地代理默认 socks5h://127.0.0.1:7897, 可用环境变量 TG_PROXY 覆盖
set -e
APK="$1"; NOTES="$2"
[ -f "$APK" ] && [ -f "$NOTES" ] || { echo "用法: $0 <apk> <公告文件>"; exit 1; }

CHAT="-1004312365887"          # fuck lark 模块官方讨论群
THREAD="274"                   # 发布话题 (与历史版本发布帖同话题)
TOK="$(cat ~/.fucklark_tg_token)"
PROXY="${TG_PROXY:-socks5h://127.0.0.1:7897}"
API="https://api.telegram.org/bot$TOK"
CURL=(curl -sS --ssl-no-revoke -x "$PROXY" --connect-timeout 20 --retry 5 --retry-delay 3)

# 1. 发公告
MSG=$("${CURL[@]}" -X POST "$API/sendMessage" \
    --data-urlencode "chat_id=$CHAT" \
    --data-urlencode "message_thread_id=$THREAD" \
    --data-urlencode "parse_mode=HTML" \
    --data-urlencode "disable_web_page_preview=true" \
    --data-urlencode "text@$NOTES" \
  | python -c "import sys,json; print(json.load(sys.stdin)['result']['message_id'])")

# 2. 发 APK (带 SHA-256 校验)
SHA="$(sha256sum "$APK" | awk '{print $1}')"
printf '📦 %s ｜ arm64-v8a ｜ LSPosed\nSHA-256: %s' "$(basename "$APK")" "$SHA" > /tmp/tg_cap.txt
"${CURL[@]}" -X POST "$API/sendDocument" \
    -F "chat_id=$CHAT" -F "message_thread_id=$THREAD" \
    -F "caption=</tmp/tg_cap.txt" \
    -F "document=@$APK;type=application/octet-stream" > /dev/null

# 3. 置顶新公告 (旧置顶 Telegram 会自动按话题保留多个, 如需替换先 unpinChatMessage)
"${CURL[@]}" -X POST "$API/pinChatMessage" \
    -d "chat_id=$CHAT" -d "message_id=$MSG" -d "disable_notification=true" > /dev/null

echo "✅ 已发布到 t.me/fucklark 发布话题: 公告 msg=$MSG, APK=$(basename "$APK")"
