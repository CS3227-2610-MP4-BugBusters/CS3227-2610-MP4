#!/usr/bin/env bash
set -euo pipefail

# First use the hosted runner's configured sources. If mirror setup fails,
# retry with an isolated set of official Ubuntu 24.04 sources.
if sudo apt-get -o APT::Update::Error-Mode=any update && sudo apt-get install -y "$@"; then
  exit 0
fi

echo 'Runner package mirrors failed; retrying with official Ubuntu sources.' >&2
sources="$(mktemp)"
trap 'rm -f "$sources"' EXIT
chmod 644 "$sources"
cat > "$sources" <<'SOURCES'
deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://archive.ubuntu.com/ubuntu noble main restricted universe multiverse
deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://archive.ubuntu.com/ubuntu noble-updates main restricted universe multiverse
deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://archive.ubuntu.com/ubuntu noble-backports main restricted universe multiverse
deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://security.ubuntu.com/ubuntu noble-security main restricted universe multiverse
SOURCES
apt_options=(-o "Dir::Etc::sourcelist=$sources" -o Dir::Etc::sourceparts=- -o APT::Update::Error-Mode=any)
sudo apt-get "${apt_options[@]}" update
sudo apt-get "${apt_options[@]}" install -y "$@"
