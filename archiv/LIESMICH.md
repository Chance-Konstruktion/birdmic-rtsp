# Archiv

Ältere APKs. Hier liegen sie, damit das Wurzelverzeichnis übersichtlich bleibt —
dort stehen nur die **aktuelle** Version und die erprobte Rückfallebene.

| Datei | Was sie bringt |
| --- | --- |
| `BirdMic-2.1.apk` | Härtung nach dem Code-Audit, eigene Warteschlange je Empfänger |
| `BirdMic-2.2.apk` | AMOLED-Schwarz |
| `BirdMic-2.3.apk` | Erste Fassung der Empfängergrenze — **sie hielt nicht**, siehe 2.5 im Änderungsverlauf |
| `BirdMic-2.4.apk` | Logbuch-Export und Weboberfläche |
| `BirdMic-2.5.apk` | Empfängergrenze repariert, Logbuch geteilt |

Alle tragen dieselbe Signatur wie die aktuelle Version (`000c22b2…`), lassen sich
also über eine bestehende Installation installieren — auch rückwärts, solange
`versionCode` nicht sinkt. **Ein echter Rückschritt geht nur über
Deinstallieren**, Android verweigert eine Installation mit kleinerer Nummer.

Als Rückfallebene ist `BirdMic-1.2.apk` im Wurzelverzeichnis gedacht: die
einzige Fassung, die über Wochen im Dauerbetrieb gelaufen ist.

Wer eine dieser Versionen zieht, verliert die Versionsmeldung aus 2.6 — die App
sagt dann nicht mehr von selbst, dass es etwas Neueres gibt.
