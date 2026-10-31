# CamGestos TV – control de Android TV con gestos de mano

Solo Android TV / Google TV (Android 8.0+). Necesita una **webcam USB** conectada a la TV.

## Compilar el APK en GitHub (sin instalar nada)
1. Crea un repositorio nuevo en github.com.
2. Sube **el contenido** de esta carpeta a la raíz del repositorio, rama `main`
   (debe verse `app/`, `build.gradle.kts`, `.github/`...). Por terminal:
   ```
   git init && git add . && git commit -m "CamGestos TV"
   git branch -M main
   git remote add origin https://github.com/TU_USUARIO/TU_REPO.git
   git push -u origin main
   ```
3. Pestaña **Actions** > "Build APK" (se lanza solo; o "Run workflow").
4. Al terminar (~5 min), entra en la ejecución y descarga el artefacto **CamGestos-apk**.

## Instalar en la TV
```
adb connect IP_DE_LA_TV:5555
adb install -r app-debug.apk
```
Si en Ajustes de la TV cuesta activar la accesibilidad o dar permisos, hazlo por adb:
```
adb shell pm grant com.example.camgestos android.permission.CAMERA
adb shell settings put secure enabled_accessibility_services com.example.camgestos/com.example.camgestos.GestureAccessibilityService
adb shell settings put secure accessibility_enabled 1
```
Después abre la app "CamGestos TV" y pulsa "Iniciar control por cámara".
