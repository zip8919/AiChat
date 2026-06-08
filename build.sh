#!/bin/bash
# AiChat Build Script
# Usage:
#   ./build.sh --deepseek-key sk-xxx --siliconflow-key sk-yyy
#   ./build.sh --deepseek-key sk-xxx --siliconflow-key sk-yyy --keystore-password xxx --key-alias mc

set -e

DEEPSEEK_KEY=""
SILICONFLOW_KEY=""
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_ALIAS="${KEY_ALIAS:-mc}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --deepseek-key)       DEEPSEEK_KEY="$2"; shift 2 ;;
        --siliconflow-key)    SILICONFLOW_KEY="$2"; shift 2 ;;
        --keystore-password)  KEYSTORE_PASSWORD="$2"; shift 2 ;;
        --key-alias)          KEY_ALIAS="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: ./build.sh [options]"
            echo ""
            echo "Options:"
            echo "  --deepseek-key <key>        DeepSeek API Key"
            echo "  --siliconflow-key <key>     SiliconFlow API Key"
            echo "  --keystore-password <pwd>   Keystore password"
            echo "  --key-alias <alias>         Key alias (default: mc)"
            echo "  -h, --help                  Show help"
            exit 0
            ;;
        *) shift ;;
    esac
done

echo "========================================="
echo "  AiChat Build Script v1.1.0"
echo "========================================="
echo ""
[ -n "$DEEPSEEK_KEY" ] && echo "DeepSeek Key:      (set)" || echo "DeepSeek Key:      (sk-xxx placeholder)"
[ -n "$SILICONFLOW_KEY" ] && echo "SiliconFlow Key:   (set)" || echo "SiliconFlow Key:   (sk-xxx placeholder)"
[ -n "$KEYSTORE_PASSWORD" ] && echo "Keystore Password: (set)" || echo "Keystore Password: (not set - signing may fail)"
echo "Key Alias:         ${KEY_ALIAS:-mc}"
echo ""

# Build
./gradlew assembleRelease \
    ${DEEPSEEK_KEY:+-PdeepseekKey="$DEEPSEEK_KEY"} \
    ${SILICONFLOW_KEY:+-PsiliconflowKey="$SILICONFLOW_KEY"} \
    ${KEYSTORE_PASSWORD:+-PkeystorePassword="$KEYSTORE_PASSWORD"} \
    ${KEY_ALIAS:+-PkeyAlias="$KEY_ALIAS"}

# Rename output
TIMESTAMP=$(date +%Y%m%d-%H%M)
OUTPUT="AiChat-v1.1.0-${TIMESTAMP}-release.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUTPUT"

echo ""
echo "========================================="
echo "  Build Complete"
echo "========================================="
echo "Output: $OUTPUT"
