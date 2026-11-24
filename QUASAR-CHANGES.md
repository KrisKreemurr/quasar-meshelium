# Quasar Meshelium

This is a modified version of [Meshelium](https://github.com/ajz811/Meshelium) by Ded811, made for the
Quasar MC modpack. It is licensed under the same **GNU LGPL-3.0-only** as the original (see `LICENSE`);
all original copyright notices, including the Nvidium attributions in shader headers, are kept.

The mod id stays `meshelium` so existing settings carry over; the display name is
"Meshelium (Quasar MC)" so it is clear this is not the upstream build.

## Changes from upstream

- 2026-11-23: forked from upstream `main` (Meshelium 1.6.2). Added a GitHub Actions build for the
  26.3 NeoForge jar, changed the display name/authors line, jar named QuasarMeshelium-neoforge-*.jar. No code changes yet.

Planned: per-pixel dynamic lights and coloured block light in the terrain shader, fed by Quasar MC's
Quasium mod.
