#!/usr/bin/env bash
# Stores Maven Central and signing credentials in ~/.gradle/gradle.properties (your user-level
# Gradle file, never the project's). Run it from Git Bash: bash scripts/setup-maven-central.sh
set -euo pipefail

PROPS="$HOME/.gradle/gradle.properties"
mkdir -p "$HOME/.gradle"
touch "$PROPS"

echo "== Central Portal user token (central.sonatype.com > avatar > View Account > Generate User Token)"
read -rp "Token username: " CENTRAL_USER
read -rsp "Token password: " CENTRAL_PASS; echo

echo
echo "== Your GPG secret keys:"
gpg --list-secret-keys --keyid-format=long
read -rp "Long key ID to sign with (the part after 'rsa4096/' or 'ed25519/'): " KEY_ID
read -rsp "Passphrase for that key: " KEY_PASS; echo

# Armored key with headers, footers and checksum removed, on one line, as the plugin expects.
KEY_BODY=$(gpg --pinentry-mode loopback --passphrase "$KEY_PASS" --export-secret-keys --armor "$KEY_ID" \
  | grep -v '\-\-' | grep -v '^=.' | tr -d '\n')
if [ -z "$KEY_BODY" ]; then
  echo "Could not export the key. Check the key ID and passphrase." >&2
  exit 1
fi

cp "$PROPS" "$PROPS.bak"
grep -vE '^(mavenCentralUsername|mavenCentralPassword|signingInMemoryKey|signingInMemoryKeyId|signingInMemoryKeyPassword)=' "$PROPS.bak" > "$PROPS" || true
{
  echo "mavenCentralUsername=$CENTRAL_USER"
  echo "mavenCentralPassword=$CENTRAL_PASS"
  echo "signingInMemoryKeyId=${KEY_ID: -8}"
  echo "signingInMemoryKeyPassword=$KEY_PASS"
  echo "signingInMemoryKey=$KEY_BODY"
} >> "$PROPS"

echo
echo "Saved to $PROPS (previous version backed up to $PROPS.bak)."
