#!/usr/bin/env bash
# Descarga el modelo de voz en español de Vosk y lo deja en los assets de la app.
# Se ejecuta una sola vez antes de compilar (el modelo no se guarda en Git).
set -euo pipefail

MODEL=vosk-model-small-es-0.42
SHA256=09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f
URL="https://alphacephei.com/vosk/models/$MODEL.zip"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/model-es"

if [[ -f "$DEST/uuid" ]]; then
    echo "El modelo ya está en $DEST"
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Descargando $MODEL (39 MB)..."
curl -fSL "$URL" -o "$TMP/model.zip"
echo "$SHA256  $TMP/model.zip" | sha256sum -c --quiet

unzip -q "$TMP/model.zip" -d "$TMP"
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$TMP/$MODEL" "$DEST"

# StorageService de Vosk compara este archivo para saber si debe volver a copiar el modelo
echo "$MODEL" > "$DEST/uuid"
echo "Modelo listo en $DEST"
