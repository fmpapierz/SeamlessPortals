# Seamless Portals — Shaders Update

**The big one: portals now work properly with shaderpacks.**

Before this update, turning on a shaderpack meant portals stopped showing anything — you'd walk up to
a portal and just see straight through it like an empty frame. Now portals render your destination
fully shaded, with your pack's lighting, fog, shadows and effects.

Getting there meant fixing a long list of things that were broken or ugly along the way. Here's
everything.

---

## 👻 Ghosts, phantoms and weird lighting — all gone

These were the worst ones, and they're all fixed:

**Ghost terrain following your camera.** A faint copy of the other world's terrain would drift across
your screen as you looked around, like a double-exposure photo. It tracked your view, so you couldn't
look away from it.

**Whole-screen phantom images.** Ghostly leftovers of the portal's destination smeared across your
entire screen, not just inside the portal.

**The lighting wave / wash.** Shadows through portals were broken in a way that made light sweep and
swing across the world as you turned. Shadow casters were being thrown out of the shadow map
entirely, so things that should have cast shadows just… didn't, and the lighting swung around with
your camera.

**Phantom lava light.** With Complementary's Advanced Color Tracing on, orange lava-glow would appear
in places with no lava anywhere near — bleeding through from the other side of a portal.

**Missing colored light in portals.** Looking through a portal into another dimension, colored light
sources (glowstone, lava, sea lanterns, anything Advanced Color Tracing lights up) simply didn't
light anything. The portal view was lit flat. Now it works.

**Portal glow ring.** A halo of bloom around the portal frame, caused by the game computing the
glow from the *whole* destination view instead of just the part you can actually see through the
window.

**Blurry portal windows.** With Motion Blur enabled, the inside of a portal was permanently smeared
— even standing perfectly still. The portal view thought the camera had teleported every single
frame. Windows are sharp now, and real motion blur still works normally when you actually move.

---

## 🚪 Crossing through portals

**Black band at the seam.** Walking through a portal, a black bar would flash across the middle of
the screen right at the moment you crossed.

**Your hand disappearing.** As you stepped through, your held item and hand would vanish or get
sliced in half, then pop back.

**The window changing shape.** Standing near a portal and looking around, the window's outline would
get cut by a straight line that swept across it as you turned your head — the portal appeared to
change shape depending on where you were looking.

**Camera clipping into terrain.** Getting close to a portal could push your view into the blocks on
the other side. The destination now clips cleanly at the portal plane.

**Third-person corruption.** In third person, if the camera ended up on the other side of a portal
from you, the entire screen exploded into stretched smeared triangles. Also, in that situation you
couldn't see yourself — there was no window back to your own dimension. Both fixed: you now get a
proper reverse window and can see your character.

---

## 🔁 NEW: portals inside portals, with shaders

Look through one portal at another and you now see *its* view too — and so on, several layers deep.
This already worked without shaders; now it works with them.

**How deep is up to you.** There are two settings (one for shaders on, one for off), and you can
type in any number rather than being stuck with a slider.

A couple of honest notes:
- Each extra layer with shaders on is a *full* extra render of the world. It's genuinely expensive.
- Two portals linked to each other stop at 2 layers no matter what you set — that's the engine
  refusing to render the portal you just came through. To see 5, 10, 20 layers you need a *chain* of
  that many separate portals.

**Also fixed while adding this:** the deepest portal in a chain used to draw as a solid black box
without shaders. Now it's simply not drawn, which looks much better and matches how it behaves with
shaders on.

---

## 📏 Distance: portals stopped working further than ~88 blocks

This one had been quietly wrong for a long time, including in the original mod.

Portals were only ever sent to your game client if they were within about **88 blocks** — so no
matter what your render distance was, a portal's view would blink out around that point and no
setting could change it. There's now a setting that actually controls it, and it genuinely reaches.

Two related fixes:
- **Windows that were visible but empty.** Once portals could be seen from far away, you could end
  up looking at a portal frame with nothing inside it, because the world behind it only loaded to
  128 blocks. The two ranges are now tied together — if you can see a window, its world loads.
- **Deep layers only loading a small bubble.** Nested portal levels loaded a quarter of your view
  distance regardless of how close you stood, so deeper layers were small islands of terrain
  floating in nothing. They now load as deeply as the first layer does.

---

## ⚙️ New settings (Mods → Seamless Portals → config)

The mod also has its proper icon in the mod list now — it was in the files the whole time, just never
hooked up.

- **Max Portal Rendering Recursion Layer** — portal-in-portal depth without a shaderpack
- **Shaderpack Portal Recursion Depth** — the same, with a shaderpack (each layer costs a lot more)
- **Reduce Shaderpack Recursion When Laggy** — optional, off by default
- **Portal Window Render Distance** — how far away a portal still shows its view
- **Destination Loading Depth** — how much world loads behind a portal *(this existed before, but was
  named "Indirect Chunk Loading Radius Cap", which nobody could be expected to find)*
- **Full Depth For Nested Portals** — deep layers load properly instead of a small bubble
- **Keep Portal Chunks Loaded** — how long the world behind a portal stays loaded after you look
  away. Raise it so glancing away and back doesn't reload everything. Set it to **-1** to never
  unload while you're playing.

All of these can be typed in directly, and each has a reset button.

**"Lag Attack Proof" is now OFF by default.** This is a protection that cuts portal rendering down to
a single layer when your frame rate drops. It made deep portal chains look broken with no explanation
— it only showed a warning for about 3 seconds and then went quiet while still limiting everything.
It now stays on screen for 5 seconds in red and tells you exactly what it did and which setting turns
it off. You can still enable it; it's just no longer on by default, since it mainly protects against
someone deliberately building a laggy mirror room.

---

## 💥 Crash fixes

**A random crash that had been happening for weeks.** Playing near portals could kill the game
outright with no warning. It turned out not to be mod logic at all — it's a bug in **Java itself**
(specifically the part of Java that optimizes running code). It happened on two different Java
versions, so it's not something a Java update fixes today.

It's been worked around properly. The previous workaround switched off optimization for six different
parts of the portal code — which worked, but made those parts permanently slower, and every time one
was disabled the crash just moved to the next one along. There's now a single, much more precise fix
that keeps everything fully optimized *and* protects the no-shaders renderer too, which was never
covered before.

**A crash at deep portal recursion.** Found while testing the above: looking down a portal chain 3+
layers deep could crash after a couple of minutes. Fixed.

---

## 🧪 Known issues still being worked on

Being upfront about what isn't done:

- In third person with Motion Blur, standing in front of a portal can put a faint outline around your
  character carrying colour from the portal behind you.
- A thin sliver of light can appear right at the edge where a portal window meets its obsidian frame.
- With Motion Blur on, a portal in a frame can look like it shifts slightly against the frame, making
  the seam visible.

All three are on the list next.

---

*If something in here doesn't match what you're seeing, please say so — several of these were found
because someone described exactly what they saw and gave a number ("it disappears at about 89
blocks"), and that turned out to be the exact cause.*
