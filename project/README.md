# Testigo

App Android con widget que empieza a grabar audio al escuchar una frase secreta, pensada
para situaciones de riesgo en las que no se puede tocar el teléfono.

## Cómo funciona

- **Modo protección:** un servicio en primer plano escucha la frase secreta todo el tiempo,
  con la pantalla apagada y el teléfono bloqueado. El reconocimiento es local (Vosk), sin internet:
  ningún audio sale del teléfono.
- **Al escuchar la frase:** deja de escuchar, empieza a grabar con `MediaRecorder` y vibra una vez.
- **Alternativa manual:** botón "Grabar" en el widget, la notificación y la app.
- **Detener:** desde el widget, la app o la notificación (desde la pantalla de bloqueo pide desbloquear).
  No se detiene por voz, a propósito.
- Las grabaciones (AAC/ADTS) se guardan mientras avanzan: si el teléfono se apaga, lo grabado se conserva.

## Compilar

Requisitos: Android Studio (JDK incluido) y el SDK de Android.

```bash
./scripts/download-model.sh   # una sola vez: modelo de voz en español (39 MB, no está en Git)
./gradlew assembleDebug        # o abrir esta carpeta en Android Studio
./gradlew testDebugUnitTest lintDebug
```

## Estructura

| Archivo | Responsabilidad |
|---|---|
| `ProtectionService` | Servicio en primer plano: escucha (Vosk) y graba (MediaRecorder), notificación |
| `RecorderWidget` | Widget de la pantalla de inicio |
| `MainActivity` | Permisos, frase secreta, prueba de la frase, lista de grabaciones |
| `PhraseMatcher` | Validación de la frase, gramática de Vosk y comparación (con pruebas) |
| `ModelVocabulary` | Lee el vocabulario del modelo para validar la frase (con pruebas) |
| `ProtectionState` | Estado compartido entre servicio, actividad y widget |

## Requisitos de Android

- Android 11 o superior (API 30).
- Permisos: micrófono y notificaciones. Se recomienda quitar la optimización de batería
  para que el sistema no cierre la protección.
