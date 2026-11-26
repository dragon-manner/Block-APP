# Keel monorepo. `make` builds the UI, embeds it and the catalog into the server, and builds the
# server for this machine and for the VPS (linux/amd64, static).
SERVER_BIN ?= dist/keel-server

.PHONY: all ui sync server server-linux desktop extension android clean

all: server

ui:
	cd ui && npm install --no-audit --no-fund && npm run build

# One catalog and one UI build feed every platform. Copies, not symlinks: go:embed and the
# Android asset packager both refuse to follow links out of their trees.
sync: ui
	rm -rf server/embed/web && mkdir -p server/embed/web && cp -r ui/dist/. server/embed/web/
	cp shared/catalog.json server/embed/catalog.json
	rm -rf desktop/dist && mkdir -p desktop/dist && cp -r ui/dist/. desktop/dist/
	cp shared/catalog.json desktop/src-tauri/catalog.json
	rm -rf android/app/src/main/assets/ui && mkdir -p android/app/src/main/assets/ui && cp -r ui/dist/. android/app/src/main/assets/ui/
	cp shared/catalog.json android/app/src/main/assets/catalog.json
	cp shared/catalog.json extension/catalog.json

server: sync
	mkdir -p dist && cd server && CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o ../$(SERVER_BIN) .

server-linux: sync
	mkdir -p dist && cd server && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o ../dist/keel-server-linux-amd64 .

desktop: sync
	cd desktop && npm install --no-audit --no-fund && npx tauri build

extension:
	cp shared/catalog.json extension/catalog.json
	mkdir -p dist && cd extension && zip -qr ../dist/keel-extension.zip . -x '*.DS_Store'

android: sync
	cd android && ./gradlew assembleRelease

clean:
	rm -rf dist ui/dist desktop/dist server/embed/web/assets
