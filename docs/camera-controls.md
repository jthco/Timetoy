# Camera controls and image orientation

The CAM entry in the mode rail opens sensor settings, separate from each effect's
parameters. Capture defaults to 60 FPS. The available FPS choices are 60 and 120;
the selected rate is preserved when changing modes. Unsupported size/rate choices
leave the current selection intact and show a message; there is no 30 FPS fallback.
Resolution can be changed to 720p if 1080p does not expose the chosen rate.

Normal Camera2 sessions are preferred when the advertised stream durations and
sensor/AE capabilities support the requested cadence. Manual exposure is the
default on cameras advertising MANUAL_SENSOR: AE is disabled, frame duration is
1/FPS, shutter defaults to 1/120 second, and ISO defaults to 400. Shutter and ISO
choices are filtered by the active camera's ranges. Exposure is clamped to both
the sensor range and the frame period; it cannot become a 1/30 shutter at 60 FPS.
Users can select Auto explicitly. Auto requests also use a fixed FPS range.

If 120 FPS needs a constrained high-speed session, Android's high-speed API forces
auto exposure. CAM shows A and its menu identifies high-speed Auto; shutter/ISO
selection reports this limitation instead of claiming those values were applied.
Return to 60 FPS for manual control, where supported. A normal 120 FPS session
can use manual exposure if the camera advertises it. This is capability-dependent,
not a promise that every Android camera supports manual 120 FPS or 1080p60.

Tape3's encoder FPS and stream size follow the selected capture settings. Its
diagnostic overlay distinguishes requested FPS from measured sensor frame duration,
callback cadence and codec rate; check these on device to detect driver overrides
or dropped frames. Sensor FPS does not change the existing GPU history/playback
sampling rates of the chunked effects (including Reverse's 30 Hz bank).

The renderer uses the producer's SurfaceTexture matrix directly for camera and
decoder textures. No app-added rotation is applied to camera pixels, effect history,
or decoder frames. The activity window is fixed to landscape so Android cannot
rotate the SurfaceView with the phone. Controls live in a separate GUI sibling:
only that layer rotates, using physical orientation and the fixed display turn.
The GUI's layout dimensions swap for quarter turns. Choice palettes are children
of the same GUI layer, so menus follow it too. Orientation sensing is disabled
while paused. The recorder and player contain no extra rotation/orientation hints.

Capture requests also set android.scaler.rotateAndCrop to NONE when that key is
advertised, preventing Camera2 automatic frame rotation/cropping on newer devices.
The key is addressed by name to retain compileSdk 29/AIDE compatibility. New trace
lines report GUI turns separately from video surface bounds and rotate/crop policy.

The supplied smm_trace_1793528375486.txt showed decoder rotation-degrees=0 and
manual 60 FPS requests, but actual 33.333 ms sensor frames (about 30 FPS) and a
remaining TEMPLATE_RECORD FPS range of [30,30]. Manual requests now replace that
range with the selected fixed range when advertised. When it is not advertised,
we log the available ranges rather than setting an unsupported value. Explicit
FPS MISMATCH diagnostics distinguish a requested rate from actual delivery.
This does not establish that the device now delivers 60 FPS; a new trace is needed.

## Validation

CaptureTimingCheck passes for 60/120 FPS periods, short shutters, clamping a long
shutter at each FPS, sensor min/max limits, and incompatible exposure ranges.
TapePlayheadCheck and GuiOrientationCheck pass. GUI tests cover all physical and
display quarter turns, quantization, and dimension swaps. Changed Java sources
pass javac parsing, and the fixed-landscape manifest parses successfully.
Full Android compilation and device validation remain required.

On device, verify live/effect switching and GUI rotation without quarter-turn image
jumps. Check Wobble's Delay and Amount popups and absence of sliders. In low light,
verify the 60 FPS sensor metadata stays near 16.67 ms while changing shutter/ISO.
Then test supported 120 FPS (near 8.33 ms), its exposure limitation where applicable,
resolution changes, all mode transitions, camera recovery and app pause/resume.
