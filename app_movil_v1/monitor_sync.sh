#!/bin/bash
# Script para monitorear la sincronización de medicamentos
# Uso: ./monitor_sync.sh

echo "🔍 Monitoreando sincronización de medicamentos..."
echo "Filtrando logs con [SYNC]..."
echo ""
echo "Presiona Ctrl+C para detener"
echo ""

# Limpiar logcat previo
adb logcat -c

# Mostrar solo logs relevantes
adb logcat | grep -E "\[SYNC\]|SyncManager|FirebaseMedMgr|forzarSincronizacion" | while read line; do
    # Colorear según el tipo de log
    if [[ "$line" == *"✅"* ]]; then
        echo -e "\033[0;32m$line\033[0m"  # Verde
    elif [[ "$line" == *"❌"* ]]; then
        echo -e "\033[0;31m$line\033[0m"  # Rojo
    elif [[ "$line" == *"⚠️"* ]]; then
        echo -e "\033[0;33m$line\033[0m"  # Amarillo
    elif [[ "$line" == *"📥"* ]] || [[ "$line" == *"💾"* ]]; then
        echo -e "\033[0;34m$line\033[0m"  # Azul
    else
        echo "$line"
    fi
done
