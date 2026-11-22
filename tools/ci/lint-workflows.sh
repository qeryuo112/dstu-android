#!/usr/bin/env bash
set -euo pipefail
TOOLS="${RUNNER_TEMP:-$PWD/build/ci-tools}/dstu-actionlint"
mkdir -p "$TOOLS"
curl --fail --location --retry 3 --output "$TOOLS/actionlint.tar.gz" \
  https://github.com/rhysd/actionlint/releases/download/v1.7.7/actionlint_1.7.7_linux_amd64.tar.gz
printf '%s  %s\n' '023070a287cd8cccd71515fedc843f1985bf96c436b7effaecce67290e7e0757' "$TOOLS/actionlint.tar.gz" | sha256sum --check
tar -xzf "$TOOLS/actionlint.tar.gz" -C "$TOOLS" actionlint
"$TOOLS/actionlint" .github/workflows/*.yml
