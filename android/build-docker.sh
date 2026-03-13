#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "Building txqr Android APK using Docker..."

# Docker image name
IMAGE_NAME="txqr-android-builder"

# Build Docker image
echo "Building Docker image..."
docker build --platform=linux/amd64 -f "$PROJECT_ROOT/Dockerfile.apk" -t "$IMAGE_NAME" "$PROJECT_ROOT"

# Run build and extract APK
echo "Extracting APK from container..."
docker run --rm --entrypoint cat "$IMAGE_NAME" /txqr.apk > "$PROJECT_ROOT/txqr.apk"

echo ""
echo "========================================"
echo "APK built successfully!"
echo "Location: $PROJECT_ROOT/txqr.apk"
echo "========================================"
echo ""
echo "To install on a device:"
echo "  adb install $PROJECT_ROOT/txqr.apk"
