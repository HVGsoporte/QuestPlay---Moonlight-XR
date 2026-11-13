# QuestPlay Launcher

APK launcher para Meta Quest (Kotlin). Una sola app, sin servidor propio:

- **Juegos QUEST**: aplicaciones nativas instaladas en las gafas, que se lanzan directamente.
- **Juegos WINDOWS**: juegos del PC servidos por **Sunshine**. QuestPlay llama directamente a **Moonlight XR**
  (`ShortcutTrampoline`, con los extras `Name`/`UUID` y `AppName`) para que abra el stream ya con el juego.

## Puesta en marcha de los juegos de Windows

1. En el PC: instala Sunshine y añade cada juego como *Application* en su interfaz web
   (p. ej. Steam: comando detached `steam://rungameid/<ID>`).
2. En las gafas: instala Moonlight XR, empareja con el PC (PIN en la web de Sunshine) y activa **Stream in VR**
   si quieres el modo inmersivo. Abre la lista de apps del PC una vez (así Moonlight guarda la lista en caché).
3. En QuestPlay: botón **PC** → escribe el nombre del PC tal como sale en Moonlight XR.
4. **+ ADD WINDOWS GAME** → nombre a mostrar + nombre **exacto** de la app en Sunshine (+ carátula opcional).

Si añades o renombras juegos en Sunshine, abre de nuevo la lista de apps en Moonlight XR para refrescar la caché.

## Compilación

- `.github/workflows/android.yml` → APK de QuestPlay (Actions → último run → Artifacts).
- `.github/moonlight-xr-debug.yml` → plantilla de workflow para compilar Moonlight XR en tu fork
  (cópiala a `.github/workflows/` de tu fork de `Gilleece/moonlight-android-xr`).
