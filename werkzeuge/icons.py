#!/usr/bin/env python3
"""Erzeugt die App-Icons aus logo.png.

Die Bilddateien liegen im Projekt, damit der Bau ohne Python auskommt --
dieses Skript ist der Weg, sie nachvollziehbar neu zu erzeugen, nicht ein
Schritt im Bau.

    python3 werkzeuge/icons.py

Zwei Dinge, die dabei nicht offensichtlich sind:

1. logo.png hat KEINEN sauberen transparenten Rand. Ausserhalb des Kreises
   liegt halbdurchsichtiges Weiss. Unbehandelt gaebe das auf dunklen
   Startbildschirmen einen hellen Schleier um das Icon. Entfernt wird es
   durch Fuellen von den Ecken aus -- eine Farbschwelle wuerde auch die
   weissen Flaechen *innerhalb* fressen: Mikrofonkorb und Vogelbrust.

2. Android maskiert adaptive Icons. Garantiert sichtbar sind nur die
   inneren 66 von 108 Einheiten; der Rest kann je nach Startbildschirm
   weggeschnitten werden. Deshalb wird das Motiv auf 72/108 gebracht --
   unter einer Kreismaske sitzt der Logoring dann knapp innen, und der
   Schriftzug bleibt in jedem Fall stehen.
"""

import pathlib
from PIL import Image, ImageDraw

WURZEL = pathlib.Path(__file__).resolve().parent.parent
RES = WURZEL / "app/src/main/res"

# Aus dem Logo abgetastet: die Innenflaeche des Kreises.
NAVY = (1, 36, 85, 255)

# Kantenlaenge des Startsymbols je Dichte.
DICHTEN = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# Anteil der 108er-Flaeche, den das Motiv einnimmt. 72/108 = zwei Drittel:
# unter einer Kreismaske genau die sichtbare Flaeche.
ANTEIL = 72 / 108


def freistellen(bild: Image.Image) -> Image.Image:
    """Setzt den zusammenhaengenden Hintergrund von den Ecken aus auf durchsichtig."""
    rgb = bild.convert("RGB")
    marke = Image.new("L", bild.size, 0)
    # Fuellen auf einer Kopie: Wir wollen nur wissen, WELCHE Pixel aussen
    # liegen, nicht deren Farbe aendern.
    arbeit = rgb.copy()
    for ecke in [(0, 0), (bild.width - 1, 0), (0, bild.height - 1), (bild.width - 1, bild.height - 1)]:
        ImageDraw.floodfill(arbeit, ecke, (255, 0, 255), thresh=60)
    for x in range(bild.width):
        for y in range(bild.height):
            if arbeit.getpixel((x, y)) == (255, 0, 255):
                marke.putpixel((x, y), 255)

    ergebnis = bild.convert("RGBA").copy()
    daten = ergebnis.load()
    aussen = marke.load()
    for x in range(bild.width):
        for y in range(bild.height):
            if aussen[x, y]:
                r, g, b, _ = daten[x, y]
                daten[x, y] = (r, g, b, 0)
    return ergebnis


def zuschneiden(bild: Image.Image) -> Image.Image:
    """Auf das sichtbare Motiv beschneiden und quadratisch zentrieren."""
    kasten = bild.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox()
    motiv = bild.crop(kasten)
    kante = max(motiv.size)
    quadrat = Image.new("RGBA", (kante, kante), (0, 0, 0, 0))
    quadrat.paste(motiv, ((kante - motiv.width) // 2, (kante - motiv.height) // 2), motiv)
    return quadrat


def auf(kante: int, motiv: Image.Image, anteil: float, hintergrund=None) -> Image.Image:
    """Motiv mittig auf eine Flaeche der Kantenlaenge [kante] setzen."""
    flaeche = Image.new("RGBA", (kante, kante), hintergrund or (0, 0, 0, 0))
    gross = max(1, round(kante * anteil))
    skaliert = motiv.resize((gross, gross), Image.LANCZOS)
    versatz = (kante - gross) // 2
    flaeche.alpha_composite(skaliert, (versatz, versatz))
    return flaeche


def kreisflaeche(kante: int) -> Image.Image:
    """Gefuellter Navy-Kreis -- Untergrund fuer die alten, unmaskierten Symbole."""
    scheibe = Image.new("RGBA", (kante, kante), (0, 0, 0, 0))
    ImageDraw.Draw(scheibe).ellipse([0, 0, kante - 1, kante - 1], fill=NAVY)
    return scheibe


def main() -> None:
    roh = Image.open(WURZEL / "logo.png").convert("RGBA")
    motiv = zuschneiden(freistellen(roh))
    print(f"Motiv freigestellt: {motiv.size[0]}x{motiv.size[1]}")

    for dichte, kante in DICHTEN.items():
        ordner = RES / f"mipmap-{dichte}"
        ordner.mkdir(parents=True, exist_ok=True)

        # Alte Symbole: Android maskiert sie nicht, also bringen sie ihre
        # eigene Flaeche mit. Das Motiv fuellt sie fast ganz aus.
        scheibe = kreisflaeche(kante)
        scheibe.alpha_composite(auf(kante, motiv, 0.94))
        # Nur eine Datei: Das runde Symbol waere byteweise dieselbe, und ab
        # Android 8 -- unserer Untergrenze -- greift ohnehin immer das
        # adaptive XML. Die Kopie waere reiner Ballast in der APK.
        scheibe.save(ordner / "ic_launcher.png")

        # Vordergrund des adaptiven Icons: 108 Einheiten Flaeche, davon
        # nutzt das Motiv 72. Der Rest ist Luft fuer die Maske.
        gross = round(kante * 108 / 48)
        auf(gross, motiv, ANTEIL).save(ordner / "ic_launcher_logo.png")
        print(f"  mipmap-{dichte}: {kante}px Symbol, {gross}px Vordergrund")

    print("fertig")


if __name__ == "__main__":
    main()
