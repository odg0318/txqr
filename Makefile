# iOS gomobile build
gomobile:
	gomobile bind -target=ios -o txqr.framework github.com/divan/txqr/mobile

# ============================================================================
# CLI Docker build
# ============================================================================

# Build arguments for CLI (defaults: darwin/arm64 for Apple Silicon)
CLI_GOOS ?= darwin
CLI_GOARCH ?= arm64

# Build CLI binary
cli:
	docker build \
		--build-arg GOOS=$(CLI_GOOS) \
		--build-arg GOARCH=$(CLI_GOARCH) \
		-f Dockerfile \
		-t txqr-cli .
	docker run --rm -v "$(PWD):/output" txqr-cli sh -c "cp /txqr /output/"
	@echo "CLI built: ./txqr ($(CLI_GOOS)/$(CLI_GOARCH))"

# ============================================================================
# Android Docker builds
# ============================================================================

# Platform for Android builds (SDK/NDK only available on linux/amd64)
DOCKER_PLATFORM := --platform=linux/amd64

# Build AAR (Android Archive Library)
aar:
	docker build $(DOCKER_PLATFORM) -f Dockerfile.android --target aar-builder -t txqr-aar .
	docker run --rm -v "$(PWD):/output" txqr-aar sh -c "cp /src/txqr.aar /output/"
	@echo "AAR built: ./txqr.aar"

# Build APK (Android Package)
apk:
	docker build $(DOCKER_PLATFORM) -f Dockerfile.android --target apk-builder -t txqr-apk .
	docker run --rm -v "$(PWD):/output" txqr-apk sh -c "cp /src/android/app/build/outputs/apk/debug/app-debug.apk /output/txqr.apk"
	@echo "APK built: ./txqr.apk"

# Build both AAR and APK
android: aar apk

# Clean build artifacts
clean-android:
	rm -f ./txqr.aar ./txqr.apk
	docker system prune -f

# Create a new release (requires gh CLI)
release:
	@if [ -z "$(VERSION)" ]; then \
		echo "Usage: make release VERSION=1.0.0"; \
		exit 1; \
	fi
	gh release create "v$(VERSION)" \
		--title "txqr $(VERSION)" \
		--notes "## txqr $(VERSION)\n\n### 📱 Android APK\n- Install on Android device (enable Unknown sources)\n\n### Features\n- QR code animation scanning\n- Large file transfer (Fountain codes)\n\n### Usage\n\`\`\`bash\n# Generate QR code\ntxqr write myfile.txt\n\n# Terminal mode\ntxqr write --terminal myfile.txt\n\n# Scan QR codes\ntxqr read\n\`\`\`" \
		./txqr.apk

.PHONY: gomobile cli aar apk android clean-android release
