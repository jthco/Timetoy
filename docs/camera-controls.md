# Camera controls and image orientation

The CAM entry opens sensor settings separately from each effect's parameters.
Tape3 is locked to a supported constrained high-speed 120 FPS session: the camera
request is explicitly [120,120], the encoder is configured for 120 FPS, and the
constructor rejects a different FPS. CAM shows a locked 120 FPS entry for Tape3.
Unsupported configurations report an error rather than falling back to 60 FPS;
720p is available if the selected camera does not support 1080p120.

Other engines retain the existing general 60/120 FPS selection. Their implementation,
including the working chunked Slow engine, is not replaced by this change.

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
For non-Tape3 engines, 60 FPS permits manual control where supported. Tape3
uses high-speed Auto exposure and does not offer a 60 FPS selection. This is capability-dependent,
not a promise that every Android camera supports manual 120 FPS or 1080p60.

Tape3's encoder remains at 120 FPS; its stream size follows the resolution setting. Its
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
display quarter turns, quantization, and dimension swaps. All app Java sources compile against Android API 29, and the fixed-landscape
manifest parses successfully. APK/Gradle and device validation remain required.

On device, verify live/effect switching and GUI rotation without quarter-turn image
jumps. Check Wobble's Length and Depth presets and absence of sliders. Verify Tape3's
request is [120,120], then measure actual sensor intervals (target about 8.33 ms)
and encoded frame cadence. Camera capture, preview delivery, decode and display
rates must be distinguished. Test non-Tape3 exposure controls separately, plus
resolution changes, all mode transitions, camera recovery and app pause/resume.
