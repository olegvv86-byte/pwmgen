# PWM Generator — Android App

## Как собрать APK в Android Studio

### 1. Открыть проект
- Запусти Android Studio
- File → Open → выбери папку `pwmgen_apk`
- Подожди пока Gradle синхронизируется (1-2 минуты)

### 2. Добавить репозиторий JitPack (для USB Serial библиотеки)
Открой файл `settings.gradle` и добавь:
```gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // ← добавь это
    }
}
```

### 3. Собрать APK
- Build → Build Bundle(s) / APK(s) → Build APK(s)
- APK будет в: `app/build/outputs/apk/debug/app-debug.apk`

### 4. Установить на телефон
- Включи "Установка из неизвестных источников" в настройках Android
- Перенеси APK на телефон и установи
- ИЛИ: Run → Run 'app' (телефон подключён к ПК)

## Использование

1. Запусти PWM Generator на телефоне
2. Подключи RP2040 Zero через OTG кабель
3. Нажми кнопку USB в боковой панели
4. Разреши доступ к USB устройству
5. Управляй генератором!

## Прошивка RP2040

Залей `main_v2_usb.py` как `main.py` на RP2040.
Протокол команд:
- F1:20000  — частота CH1/CH2
- D1:50     — скважность CH1/CH2
- F3:1000000 — частота CH3
- D3:50     — скважность CH3
- N3:5      — количество импульсов
- P3:0      — фаза %
- GET       — запрос статуса

## Файлы
- `app/src/main/assets/index.html` — весь UI
- `app/src/main/java/com/pwmgen/MainActivity.java` — USB Serial мост
