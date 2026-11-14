# Lokalizator

Aplikacja na Androida: dwa (lub więcej) telefony z tym samym kodem widzą nawzajem swoją lokalizację.

## Dwa tryby

**Tryb oszczędny (cały czas)**
- pozycja z Wi-Fi/sieci komórkowej (bez GPS) co 1 min (do wyboru 30 s – 5 min),
- jedna mała wiadomość na minutę, stałe połączenie z rzadkim „keep-alive”,
- na ekranie: mapa, odległość, dokładność, bateria drugiego telefonu, „ostatni kontakt”.

**Tryb szukania (np. w sklepie) – 3/5/10 min, potem wyłącza się sam**
Włączasz go u siebie, a drugi telefon dostaje polecenie i też przechodzi w ten tryb. Oba telefony co 3 s wymieniają:
- **GPS co 2 s** → strzałka z kompasem pokazująca kierunek (na zewnątrz / duże odległości),
- **Bluetooth LE** – telefony nadają i słuchają się nawzajem; siła sygnału → odległość (do ~30–50 m)
  i wskaźnik **CIEPLEJ / ZIMNIEJ** (najlepsze w sklepie, gdzie GPS nie działa),
- **Wi-Fi** – porównanie list routerów obu telefonów (podobieństwo otoczenia) + „najbliższy router przy drugim
  telefonie: idź tak, by jego sygnał u Ciebie rósł”,
- **barometr** – różnica wysokości / piętra (po jednorazowej kalibracji, gdy jesteście obok siebie),
- **akcelerometr** – czy druga osoba idzie, czy stoi,
- bateria, sieć Wi-Fi, do której jest podłączony drugi telefon.

Akcje zdalne: **głośny dzwonek** (30 s, na maksymalnej głośności alarmu, z wibracją), **miganie latarką**, nawigacja w Mapach.

## Uruchomienie
1. Zainstaluj APK na obu telefonach (Android 8+).
2. Na pierwszym: wpisz nazwę, zostaw wygenerowany kod, „Wyślij kod” drugiej osobie.
3. Na drugim: wpisz ten sam kod.
4. Zezwól na lokalizację, Bluetooth („Urządzenia w pobliżu”) i powiadomienia.
5. Zalecane: lokalizacja „Zawsze” i wyłączenie optymalizacji baterii (przyciski na ekranie głównym).

## Jak to działa / prywatność
Telefony łączą się przez serwer MQTT (domyślnie publiczny `broker.hivemq.com`, TLS).
Każda wiadomość jest szyfrowana AES-256-GCM kluczem wyprowadzonym z kodu parowania, a nazwa kanału
to skrót kodu – serwer widzi tylko zaszyfrowane bajty. Publiczny serwer nie daje gwarancji działania;
w „Ustawienia → Zaawansowane” można podać inny (np. darmowe konto HiveMQ Cloud lub własny Mosquitto).

## Budowanie
```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
GitHub Actions buduje APK przy każdym pushu (artefakt „Lokalizator-apk”).
