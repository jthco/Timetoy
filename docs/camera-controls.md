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
or decoder frames. GUI orientation/layout can still follow Android configuration.
The recorder and player sources contain no additional rotation/orientation hints.

## Validation

CaptureTimingCheck passes for 60/120 FPS periods, short shutters, clamping a long
shutter at each FPS, sensor min/max limits, and incompatible exposure ranges.
TapePlayheadCheck still passes, and changed Java sources pass javac parsing.
Full Android compilation and device validation remain required.

On device, verify live/effect switching and GUI rotation without quarter-turn image
jumps. Check Wobble's Delay and Amount popups and absence of sliders. In low light,
verify the 60 FPS sensor metadata stays near 16.67 ms while changing shutter/ISO.
Then test supported 120 FPS (near 8.33 ms), its exposure limitation where applicable,
resolution changes, all mode transitions, camera recovery and app pause/resume.
