# Quasar Meshelium

This is a modified version of [Meshelium](https://github.com/ajz811/Meshelium) by Ded811, made for the
Quasar MC modpack. It is licensed under the same **GNU LGPL-3.0-only** as the original (see `LICENSE`);
all original copyright notices, including the Nvidium attributions in shader headers, are kept.

The mod id stays `meshelium` so existing settings carry over; the display name is
"Meshelium (Quasar MC)" so it is clear this is not the upstream build.

## Changes from upstream

- 2026-11-23: forked from upstream `main` (Meshelium 1.6.2). Added a GitHub Actions build for the
  26.3 NeoForge jar, changed the display name/authors line, jar named QuasarMeshelium-neoforge-*.jar. No code changes yet.

- 2026-11-23: smooth moving lights. New `com.deds.meshelium.quasar.QuasarLights` (filled each frame by
  Quasar MC's Quasium mod, reflectively); `TerrainDrawer.uploadScene` appends the light list to the scene
  UBO (208 -> 784 bytes, non-Sodium variants); `terrain.mesh` (`quasarDynamicLight`) slides a corner's
  baked block light from the baked block position to the exact light position when the baked value
  matches that light's open-space falloff. CI compiles the touched shader variants with glslc/spirv-val.

- 2026-11-23: the "Meshelium Settings..." row in Video Settings is skipped when Quasar MC's Quasium sidebar
  is active (system property `quasarmc.quasium.videoSidebar`); Quasium opens the same screen from its own
  "Meshelium" sidebar tab.

- 2026-11-23: block-light color, step 1 (plumbing): new storage buffer at binding 12 (mesh stage) in every
  non-Sodium terrain pipeline (`TerrainDrawPipeline`, pushed in both `TerrainDrawer` descriptor pushes;
  descriptor budget constants in `MesheliumScaling` +1). `quasar/QuasarColorLight` owns the host-mapped
  buffer (header + world-anchored RGBA8 ring, 128x64x128). `terrain.mesh` tints only the block-light part
  of the lightmap (sky light stays white). Step 1 ships a test pattern (all block light red) to prove it.

Planned: block-light color in the terrain shader.

- Colored light: pages of sections beyond render distance are freed (swept a few per frame), and a full pool
  gives up its farthest page. Before, unloaded sections kept their pages forever, the pool filled while exploring,
  and new sections near the player stayed white. Pool raised to 4096 pages (64 MB).

- Colored light buffer moved to device-local memory. The CPU keeps the section table; changed pages and table
  entries go through a small host staging buffer (FREE_FRAME_LAG + 1 slots) and are copied once per frame from
  `MesheliumTerrainGpu.endFrame`. The header carries a magic value so the dummy bound before the first upload
  never tints.
