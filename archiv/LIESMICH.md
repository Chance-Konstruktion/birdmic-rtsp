# Archiv

Ältere APKs. Hier liegen sie, damit das Wurzelverzeichnis übersichtlich bleibt —
dort steht seit 2.8 gar keine APK mehr: die aktuelle Fassung kommt aus dem
Paketregister, alles Frühere aus diesem Verzeichnis.

| Datei | Was sie bringt |
| --- | --- |
| `BirdMic-2.1.apk` | Härtung nach dem Code-Audit, eigene Warteschlange je Empfänger |
| `BirdMic-2.2.apk` | AMOLED-Schwarz |
| `BirdMic-2.3.apk` | Erste Fassung der Empfängergrenze — **sie hielt nicht**, siehe 2.5 im Änderungsverlauf |
| `BirdMic-2.4.apk` | Logbuch-Export und Weboberfläche |
| `BirdMic-2.5.apk` | Empfängergrenze repariert, Logbuch geteilt |
| `BirdMic-2.6.apk` | Meldet neue Versionen von selbst |
| `BirdMic-2.7.apk` | APKs kommen aus dem Paketregister |
| `BirdMic-1.2.apk` | Mikrofonauswahl — bis 2.8 die erprobte Rückfallebene (siehe unten) |

Alle tragen dieselbe Signatur wie die aktuelle Version (`000c22b2…`), lassen sich
also über eine bestehende Installation installieren — auch rückwärts, solange
`versionCode` nicht sinkt. **Ein echter Rückschritt geht nur über
Deinstallieren**, Android verweigert eine Installation mit kleinerer Nummer.

`BirdMic-1.2.apk` war lange die einzige Fassung mit nachgewiesenem
Dauerbetrieb und stand deshalb als Rückfallebene im Wurzelverzeichnis. Seit
2.8 wird sie dafür nicht mehr gebraucht: 2.8 läuft seit dem 04.09.2026
ununterbrochen auf einem Nokia 2.3. 1.2 liegt seitdem hier wie alle anderen —
als Weg zurück taugt sie weiter, nur ist sie nicht mehr der empfohlene.

Wer eine dieser Versionen zieht, verliert die Versionsmeldung aus 2.6 — die App
sagt dann nicht mehr von selbst, dass es etwas Neueres gibt.
