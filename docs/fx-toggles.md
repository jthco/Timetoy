# FX toggles

The FX menu lists Pop, BW, Mirror, each with an On/Off label. They are independent
and can be combined. All start off. The FX rail entry shows the number enabled.
Selections survive camera recovery and mode changes in the current activity.

Pop increases saturation (1.35x) and contrast (1.3x). BW converts the result to
luminance. Mirror reflects the presented quad along the GUI's horizontal axis,
without modifying producer texture coordinates. Portrait uses the fixed video
window's Y axis; landscape uses its X axis. No image rotation is introduced.

FX run in the final display pass for external camera/decoder textures, 2D history
textures and the existing YUV display path. Internal history, reverse-bank and
freeze capture draws explicitly disable FX, so effects do not accumulate and
existing buffered frames respond to toggling. This implements rendered FX; it
does not add an export pipeline to the existing Share placeholder.

Validation: `python tools/check_fx.py` uses a surfaceless EGL/OpenGL ES context
with the shader strings extracted from GLRenderer.java. All three shader programs
compile and link. Pixel checks cover all eight combinations on the 2D path,
including BW channel equality, reflection in portrait and landscape, visible Pop
changes, and restoration of original pixels. Asymmetric 2x2 images distinguish
reflection from a 180-degree rotation. Identity, Y-inverted and quarter-turn
producer matrices are tested. This requires
Mesa/EGL; it is a host-side graphics test, not an Android build.

All app Java sources compile against Android API 29. AIDE APK assembly and device
checks of live, decoded and history playback remain pending. Molasses/Downshift
menu hiding and Wobble Length/Depth presets are included in the current update.
