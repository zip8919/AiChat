#!/bin/bash
# AiChat Build Script
# Usage:
#   ./build.sh --sensenova-key sk-xxx
#   ./build.sh --sensenova-key sk-xxx --keystore-password xxx --key-alias mc

set -e

SENSENOVA_KEY=""
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_ALIAS="${KEY_ALIAS:-mc}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --sensenova-key)      SENSENOVA_KEY="$2"; shift 2 ;;
        --keystore-password)  KEYSTORE_PASSWORD="$2"; shift 2 ;;
        --key-alias)          KEY_ALIAS="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: ./build.sh [options]"
            echo ""
            echo "Options:"
            echo "  --sensenova-key <key>       SenseNova API Key (baked into BuildConfig)"
            echo "  --keystore-password <pwd>   Keystore password"
            echo "  --key-alias <alias>         Key alias (default: mc)"
            echo "  -h, --help                  Show help"
            exit 0
            ;;
        *) shift ;;
    esac
done

echo "========================================="
echo "  AiChat Build Script v1.3.5"
echo "========================================="
echo ""
[ -n "$SENSENOVA_KEY" ] && echo "SenseNova Key:     (set)" || echo "SenseNova Key:     (empty)"
[ -n "$KEYSTORE_PASSWORD" ] && echo "Keystore Password: (set)" || echo "Keystore Password: (not set - signing may fail)"
echo "Key Alias:         ${KEY_ALIAS:-mc}"
echo ""

# Build
./gradlew assembleRelease \
    ${SENSENOVA_KEY:+-PsensenovaKey="$SENSENOVA_KEY"} \
    ${KEYSTORE_PASSWORD:+-PkeystorePassword="$KEYSTORE_PASSWORD"} \
    ${KEY_ALIAS:+-PkeyAlias="$KEY_ALIAS"}

# Rename output
TIMESTAMP=$(date +%Y%m%d-%H%M)
OUTPUT="AiChat-v1.3.5-${TIMESTAMP}-release.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUTPUT"

echo ""
echo "========================================="
echo "  Build Complete"
echo "========================================="
echo "Output: $OUTPUT"
