# VoleyClips (Android)

App para grabar partidos de voleibol (oficiales o de parque), llevar el marcador en vivo y quedarte solo con las jugadas.

## Cómo se usa

0. La app se ve en vertical en el inicio y en la revisión, y gira sola a horizontal mientras grabas.
1. En el inicio pones el nombre del partido, los dos equipos, el modo (Oficial o Parque, a 15 o a 21) y la calidad (720p ocupa menos, 1080p se ve mejor).
2. Tocas **Grabar** y dejas el celular en un trípode. La pantalla muestra el marcador a la derecha: **+1** para cada equipo, **Deshacer** y el set en juego. El set se cierra solo al llegar al objetivo con 2 puntos de diferencia (Oficial: 25, y 15 en el quinto set; el partido termina a 3 sets).
   Para los puntos también hay gestos: ☝ (un dedo arriba) suma un punto al equipo A y 🤟 (pulgar, índice y meñique) al equipo B.
3. Cuando pasa una jugada buena, la guardas de tres formas:
   - **Gesto de mano frente al lente:** 👍 guarda los 10 s anteriores, ✌ los 20 s, ✋ los 30 s. Sostén el gesto medio segundo.
   - **Botones 10 s / 20 s / 30 s** en la pantalla.
   - **Botones de volumen:** 1 toque = 10 s, 2 toques = 20 s, 3 toques = 30 s.
4. Al terminar, **Detener y guardar jugadas**. Aparece la lista de jugadas, cada una con el marcador del momento. Ahí puedes bajar una sola, un resumen con todas, o **Guardar jugadas sueltas y borrar el video completo** (queda solo lo que importa, en `Movies/VoleyClips`).

## Cosas que debes saber

- Los gestos solo se reconocen si la mano está cerca del lente (a 2 o 3 metros como mucho). Si el celular está lejos en un trípode, usa los botones de volumen o los de pantalla.
- El gesto sale en el video, así que la jugada termina unos 2,5 s antes de que lo hicieras.
- El video completo se guarda mientras grabas (aprox. 2 a 4 GB por hora en 720p). Solo se libera cuando guardas las jugadas sueltas y borras el original.
- La primera vez que abres la grabación necesita internet para bajar el modelo de gestos (unos 8 MB). Después funciona sin conexión.
- Si la grabación se traba, apaga los gestos con el botón "Gestos" del panel: la cámara deja de analizar imágenes y queda más liviana.
- El zoom automático que sigue al jugador todavía no está en la app; el zoom actual se fija a mano en cada jugada.
- También puedes **importar un video de la galería** y marcar las jugadas a mano.

## Cómo obtener el APK (sin instalar nada en el computador)

1. Crea una cuenta en https://github.com (gratis) y un repositorio nuevo, por ejemplo `voleyclips`.
2. Sube todos los archivos de esta carpeta al repositorio (botón **Add file → Upload files**, arrastra todo, incluida la carpeta oculta `.github`).
3. Entra a la pestaña **Actions**. La compilación arranca sola (si no, abre **Compilar APK → Run workflow**). Tarda unos 5 a 10 minutos.
4. Cuando termine, abre la ejecución y descarga **VoleyClips-apk** (es un .zip con `app-debug.apk` adentro).
5. Pasa el APK al celular, ábrelo y acepta «Instalar apps de origen desconocido».

## Cómo compilarlo en tu computador

Abre esta carpeta con **Android Studio** y dale a Run, o desde terminal con Gradle 8.7 y JDK 17:

```
gradle assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Detalles técnicos

- Kotlin + Jetpack Compose, minSdk 29 (Android 10).
- Grabación: CameraX (vista previa + video + análisis de imagen para los gestos). Gestos: MediaPipe GestureRecognizer. Reproducción y recorte: Media3.
- Lógica del marcador en `Model.kt` (`addPoint`, `setTarget`, `matchOver`). Gestos en `GestureTrigger.kt`. Pantalla de grabación en `RecordScreen.kt`.
- Versión de prueba firmada con la llave de depuración.
