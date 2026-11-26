# MiCall — aktualizacje

Tu leżą gotowe pliki nowych wersji MiCall (bez kodu źródłowego).

Nic nie trzeba stąd pobierać ręcznie:

1. Serwer MiCall co 30 minut sprawdza, czy jest tu nowsza wersja.
2. Administrator klika w MiCall **„Aktualizuj serwer”** — serwer pobiera nową wersję, sprawdza sumę SHA-256 i uruchamia się ponownie.
3. Każdy w aplikacji na Windows dostaje przycisk **„Aktualizuj MiCall”** — jedno kliknięcie i gotowe. Strona i telefon odświeżają się same.

Najnowsza wersja jest opisana w `latest.json`, a jej pliki leżą w folderze `vX.Y.Z`:

| plik | co to |
|---|---|
| `MiCall.exe` | aplikacja na Windows |
| `micall-server-linux-amd64` | serwer (Intel/AMD) |
| `micall-server-linux-arm64` | serwer (ARM) |
| `SHA256SUMS` | sumy kontrolne — plik z inną sumą zostanie odrzucony |
