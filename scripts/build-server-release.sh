#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
output_dir="$repo_dir/dist"
mkdir -p "$output_dir"

(cd "$repo_dir/server" && go test ./...)
for arch in amd64 arm64; do
    output="$output_dir/orbbuddy-server_linux_$arch"
    (cd "$repo_dir/server" && CGO_ENABLED=0 GOOS=linux GOARCH="$arch" \
        go build -trimpath -ldflags='-s -w' -o "$output" ./cmd/orbbuddy-server)
done

(
    cd "$output_dir"
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 orbbuddy-server_linux_* > checksums.sha256
    else
        sha256sum orbbuddy-server_linux_* > checksums.sha256
    fi
)
cat "$output_dir/checksums.sha256"
