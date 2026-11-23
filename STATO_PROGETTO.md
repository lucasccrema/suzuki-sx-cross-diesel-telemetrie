# Stato Progetto — S-Cross OBD (D16AA 2021)

Ultimo aggiornamento: **2026-11-22**

---

## 1. Veicolo e Ambiente
- **Auto:** Suzuki SX4 S-Cross 1.6 DDiS (motore Fiat/Suzuki **D16AA**, immatricolata 2021)
- **ECU Motore rilevata (`090A`):** `ECM-EngineContro` (Bosch EDC17C49)
- **Protocollo CAN rilevato (`ATDP` / `ATDPN`):** `AUTO, ISO 15765-4 (CAN 11/500)` (`A6`)
- **App Android:** Kotlin + Jetpack Compose + Firebase Auth (Google Sign-In) + Cloud Firestore

---

## 2. Cosa funziona già al 100% in auto (verificato dai report reali)
La ECU motore risponde regolarmente a **33 PID Mode 01** standard + `ATRV`:
`0101 0104 0105 010B 010C 010D 010F 0110 0113 011C 011F 0120 0121 0123 012C 012D 012F 0130 0131 0133 0134 0135 0140 0142 0145 0149 014A 014C 0160 0175 0177 017C ATRV`

Valori verificati live in auto:
- **Batteria ECU (`0142`):** ~14.20V – 14.66V (alternatore in carica)
- **Tensione presa OBD (`ATRV`):** ~14.0V – 14.5V
- **Giri motore (`010C`):** attivo
- **Temp. refrigerante (`0105`) / Aria aspirazione (`010F`):** attivi
- **Temp. scarico / DPF (`017C`):** supportato nella bitmask della ECU e già configurato a catalogo
- **Turbo (`010B`), Rail gasolio (`0123`), Carico (`0104`), MAF (`0110`), Velocità (`010D`), Barometro (`0133`):** supportati

### PID Mode 01 già supportati dalla ECU ma non ancora a catalogo widget
*(Possibili aggiunte rapide se desiderate)*:
- `012F` — Livello carburante (%)
- `012C` — EGR comandata (%)
- `0175` — Temperatura turbo (°C)
- `011F` — Tempo di funzionamento motore dall'avvio (s)
- `0131` — Distanza percorsa dal reset spie (km)

---

## 3. Diagnosi problema TPMS (`BCM?`) dai 4 Report CAN
### Esito delle prove eseguite finora
1. **Report #1/#2/#3 — Scansione attiva `Cerca moduli BCM / TPMS`:**
   - L'adattatore esegue correttamente i comandi `ATST28=OK`, `ATH1=OK`, `ATCM700=OK`, `ATCF700=OK` e il sanity check `[OK] 7E0 (RX=7E8) 010C`.
   - Tutti gli header `710..7D2` (36 indirizzi) e 29-bit (`ATSP7`) + `7E0 Mode 21/22` hanno risposto `NO DATA` senza nemmeno risposte negative `7F`.
2. **Report #4 — Test `Intercetta Menu TPMS (Auto ferma)`:**
   - Identificazione adattatore: **`ATI=ELM327 v2.1 | AT@1=OBDII to RS232 Interpreter`**.
   - Cattura passiva `ATMA`:
     - `[SNIFF_7XX] 0 frame (bufFull=false, timeout=false)`
     - `[SNIFF_4XX] 0 frame (bufFull=false)`
     - `[SNIFF_ALL] 0 frame (bufFull=false)`

### Conclusione tecnica
Con motore avviato (~800 rpm), un bus CAN reale trasmette centinaia di pacchetti al secondo: ricevere `0 frame` su filtro aperto `000..7FF` senza andare in `BUFFER FULL` dimostra che l'attuale dongle **`ELM327 v2.1`** non implementa realmente `ATMA` né l'accesso ai moduli estesi/proprietari Suzuki (limite noto anche su *SZ Viewer* e *Multiecuscan* per i cloni `v2.1`).

---

Ultima revisione: **2026-11-23**

## 4. Strumenti diagnostici & Log Tragitti pronti nell'app
Dentro **Ispettore PID & Multi-Header CAN** (`ConnectionAndPidSheets.kt` / `Elm327TelemetryRepository.kt`):
- **`Cerca moduli BCM / TPMS`**: interroga `ATI`/`AT@1`, `7E0` (`0902`, `1003`, `22xxxx`), broadcast `7DF`, sweep fisico 11-bit (`710..7E7`), indirizzamento esteso Suzuki (`ATCEA`), 29-bit (`ATSP7`) e sniff passivo breve `ATMA`.
- **`Intercetta Menu TPMS (Auto ferma)`**: ascolto dedicato in 4 fasi (`700..7FF` per 3,5 s, `400..7FF` per 2 s, `000..7FF` per 250 ms, probe attivi a menu aperto) mentre si apre la voce TPMS sul cruscotto con l'astina.
- **`Copia Report Scansione`**: esporta tutto negli appunti in un tocco.

Nuova funzione **Log Tragitti Casa-Lavoro** (`TripLogManager.kt`, `TelemetryLoggingService.kt`, `TripCsvStorage.kt`, `TripLogsSheet.kt`):
- **Auto-Log alla connessione ELM327** (disattivabile da storico) + avvio/arresto manuale su connessione attiva o Simulatore Demo.
- **Servizio in Foreground (`connectedDevice`)**: mantiene viva la connessione Bluetooth e la scrittura CSV anche a schermo spento o con Google Maps / Waze in primo piano.
- **Analisi consumi, code e DPF**: stima flusso gasolio (`L/h`, `L/100 km`, litri totali) combinando `MAF (0110)` + `Lambda O₂ (0134)` + `Carico (0104)` + rilevamento cut-off; conteggio tempo perso in coda (`< 10 km/h`) e rilevamento automatico rigenerazioni DPF (`017C ≥ 500 °C`).
- **Doppia persistenza**: CSV ad alta risoluzione (~1,8 s, 15 colonne) salvato localmente ed esportabile/condivisibile (`FileProvider`), + riepilogo e grafico 4 tracce sincronizzati su Cloud Firestore (`/users/{userId}/trip_logs/{tripId}`).

---

## 5. Checklist Prossimi Passi
- [x] **Log continuo tragitti casa-lavoro** implementato (CSV locale + Cloud Firestore + Foreground Service + storico e grafici).
- [ ] **Sostituire il dongle `v2.1` con un adattatore compatibile completo (Bluetooth Classic per Android):**
  - Verificare che sia **Bluetooth (2.0/3.0 Classic o dual-mode)** — **NON solo Wi-Fi** (es. evitare modelli con `SSID: WiFi OBDII` / `Port: 35000`) e **NON solo BLE iOS**.
  - Opzione economica: **ELM327 `V1.5` con chip reale `PIC18F25K80`** dichiarato, acquistato con reso gratuito e verificato subito con *ELM327 Identifier* (verde fino a `v1.4/v1.5`) o col nostro report `ATMA`.
  - Opzione affidabile superiore: **Vgate vLinker MC / FD** (o **OBDLink LX/MX+**).
- [ ] **Primo test in auto col nuovo adattatore:**
  - Eseguire **Intercetta Menu TPMS (Auto ferma)** + **Cerca moduli BCM / TPMS** (eventualmente anche un test breve in movimento sopra 25–30 km/h perché i sensori ruota 433 MHz si svegliano in marcia).
  - Copiare e analizzare il nuovo **Report Scansione** per mappare gli ID/PID TPMS (e verificare se emergono anche PID estesi DPF/BCM su `Mode 21/22` o frame CAN passivi).
- [ ] **Eventuale supporto in app per i nuovi PID / modalità passiva:**
  - Se il TPMS risponde su richiesta attiva (`TX:RX` + `Mode 21/22`), si configura subito con **"Applica alle 4 ruote"**.
  - Se invece il TPMS trasmette solo in broadcast CAN passivo (`[TPMS_PASSIVE?]`), aggiungere nel polling live la lettura periodica mirata via `ATCRA <CAN_ID>`.
- [ ] **Opzionale:** aggiungere al catalogo standard i PID Mode 01 extra supportati dalla ECU D16AA (`012F`, `012C`, `0175`, `011F`, `0131`).
