# S-Cross OBD — Telemetria D16AA

App Android (Kotlin / Jetpack Compose / Material 3) per la telemetria live via Bluetooth Classic SPP (`ELM327`) su **Suzuki SX4 S-Cross 1.6 Diesel D16AA (2021)**, con profili dashboard e PID personalizzati sincronizzati su **Google Sign-In + Cloud Firestore**.

## Stato attuale e diario di lavoro
Tutto il filo del progetto, i risultati dei test reali in auto, i motivi tecnici del blocco TPMS sull'adattatore `v2.1` e i prossimi passi sono tracciati in:

- **[`STATO_PROGETTO.md`](./STATO_PROGETTO.md)**

## Funzionalità principali implementate
- Connessione diretta **Bluetooth Classic SPP (`RFCOMM`)** con log live TX/RX/TO/ERR.
- Rilevamento automatico dei PID **Mode 01** supportati dalla ECU motore (`0100`/`0120`/`0140`/`0160`) + tensione presa OBD (`ATRV`).
- Dashboard personalizzabili (`Guida D16AA`, `Turbo & Iniezione`, `Batteria & Pneumatici`) salvate su Cloud Firestore per account Google.
- **Ispettore PID & Multi-Header CAN**:
  - Test singolo comando custom (`TX[:RX]`, formule `A..H`).
  - **Cerca moduli BCM / TPMS** (scansione `7E0`, `7DF`, `710..7E7`, `ATCEA`, 29-bit e sniff CAN passivo).
  - **Intercetta Menu TPMS (Auto ferma)** (ascolto mirato durante apertura menu TPMS sul cruscotto).
  - **Copia Report Scansione** negli appunti.
