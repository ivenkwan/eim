#!/usr/bin/env bash
# Bootstrap external toolchains not present on this machine into ~/.local.
# Idempotent; safe to re-run. Tools already on PATH are left untouched.
set -euo pipefail

OPT_DIR="$HOME/.local/opt"
BIN_DIR="$HOME/.local/bin"
mkdir -p "$OPT_DIR" "$BIN_DIR"

log() { echo "[bootstrap] $*"; }

need() { command -v "$1" >/dev/null 2>&1; }

# --- JDK 21 (Temurin) ---
if need java; then
  log "java already present: $(java -version 2>&1 | head -1)"
else
  if [ ! -x "$OPT_DIR/jdk-21/bin/java" ]; then
    log "downloading Temurin JDK 21..."
    curl -fL --retry 3 -o /tmp/jdk21.tar.gz \
      "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
    tar -xzf /tmp/jdk21.tar.gz -C "$OPT_DIR"
  fi
  JDK_DIR="$(find "$OPT_DIR" -maxdepth 1 -type d -name 'jdk-21*' | head -1)"
  ln -sf "$JDK_DIR/bin/java" "$BIN_DIR/java"
  ln -sf "$JDK_DIR/bin/jar" "$BIN_DIR/jar"
  ln -sf "$JDK_DIR/bin/javac" "$BIN_DIR/javac"
  log "java installed: $JDK_DIR"
fi

# --- Maven ---
if need mvn; then
  log "mvn already present: $(mvn -version 2>&1 | head -1)"
else
  MVN_VER="$(curl -s --max-time 15 https://dlcdn.apache.org/maven/maven-3/ \
    | grep -oE '3\.[0-9]+\.[0-9]+/' | tr -d '/' | sort -V | tail -1)"
  if [ -z "$MVN_VER" ]; then MVN_VER="3.9.16"; fi
  if [ ! -d "$OPT_DIR/apache-maven-$MVN_VER" ]; then
    log "downloading Maven $MVN_VER..."
    if ! curl -fL --retry 3 -o /tmp/maven.tar.gz \
      "https://dlcdn.apache.org/maven/maven-3/$MVN_VER/binaries/apache-maven-$MVN_VER-bin.tar.gz"; then
      log "dlcdn miss, falling back to archive.apache.org"
      curl -fL --retry 3 -o /tmp/maven.tar.gz \
        "https://archive.apache.org/dist/maven/maven-3/$MVN_VER/binaries/apache-maven-$MVN_VER-bin.tar.gz"
    fi
    tar -xzf /tmp/maven.tar.gz -C "$OPT_DIR"
  fi
  ln -sf "$OPT_DIR/apache-maven-$MVN_VER/bin/mvn" "$BIN_DIR/mvn"
  log "mvn installed: $OPT_DIR/apache-maven-$MVN_VER"
fi

# --- shellcheck ---
if ! need shellcheck; then
  log "downloading shellcheck..."
  curl -fL --retry 3 -o /tmp/shellcheck.tar.xz \
    "https://github.com/koalaman/shellcheck/releases/download/v0.10.0/shellcheck-v0.10.0.linux.x86_64.tar.xz"
  tar -xJf /tmp/shellcheck.tar.xz -C /tmp
  cp /tmp/shellcheck-v0.10.0/shellcheck "$BIN_DIR/"
else log "shellcheck present"; fi

# --- actionlint ---
if ! need actionlint; then
  log "downloading actionlint..."
  curl -fL --retry 3 -o /tmp/actionlint.tar.gz \
    "https://github.com/rhysd/actionlint/releases/download/v1.7.7/actionlint_1.7.7_linux_amd64.tar.gz"
  tar -xzf /tmp/actionlint.tar.gz -C /tmp
  cp /tmp/actionlint "$BIN_DIR/"
else log "actionlint present"; fi

# --- kustomize ---
if ! need kustomize; then
  log "downloading kustomize..."
  curl -fL --retry 3 -o /tmp/kustomize.tar.gz \
    "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv5.5.0/kustomize_v5.5.0_linux_amd64.tar.gz"
  tar -xzf /tmp/kustomize.tar.gz -C /tmp
  cp /tmp/kustomize "$BIN_DIR/"
else log "kustomize present"; fi

log "bootstrap complete"
