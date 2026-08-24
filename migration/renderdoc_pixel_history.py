# RenderDoc pixel-history script — the seam face cut.
#
# HOW TO RUN: open the capture in RenderDoc, then Window > Python Shell, and use the
# "Open Script" button (or paste this in and hit Run). Set PIXEL_X / PIXEL_Y first.
#
# WHAT IT ANSWERS, in one pass, for the pixel where the cow's muzzle should be:
#   1. Every fragment that ever touched that pixel, which draw produced it, and whether it
#      PASSED or FAILED — and if it failed, WHICH test (depth / stencil / backface / scissor).
#   2. The depth stored there and the draw that wrote it.
#   3. The full list of draws whose output colour is ORANGE-ish, i.e. the tinted main-pass
#      projection — the painter we know is being issued and whose pixels are vanishing.
#
# The seam tint palette (mixed into fragment output by seamlessportals_DebugTint):
#   RED main-pass real body · ORANGE main-pass projection · BLUE in-pass projection
#   GREEN in-pass ambient · WHITE band P1 · CYAN band P2 · YELLOW seam-cell redraw

PIXEL_X = 0      # <-- set to the muzzle pixel (RenderDoc shows coords under the cursor)
PIXEL_Y = 0

def fmt_mod(m):
    """Human-readable pass/fail reason for one pixel-history event."""
    if m.backfaceCulled:      return "FAILED backface"
    if m.depthClipped:        return "FAILED depth-clip (near/far)"
    if m.depthTestFailed:     return "FAILED depth test"
    if m.stencilTestFailed:   return "FAILED stencil test"
    if m.scissorClipped:      return "FAILED scissor"
    if m.shaderDiscarded:     return "FAILED shader discard"
    if m.viewClipped:         return "FAILED view clip"
    return "PASSED"

def classify(col):
    """Map a tinted fragment colour back to its painter."""
    r, g, b = col.floatValue[0], col.floatValue[1], col.floatValue[2]
    if r > 0.55 and g > 0.35 and g < 0.75 and b < 0.3:  return "ORANGE main-pass PROJECTION"
    if r > 0.55 and g < 0.35 and b < 0.35:              return "RED main-pass real body"
    if g > 0.55 and r < 0.4 and b < 0.4:                return "GREEN in-pass ambient"
    if b > 0.55 and r < 0.4 and g < 0.6:                return "BLUE in-pass projection"
    if r > 0.6 and g > 0.6 and b > 0.6:                 return "WHITE band P1"
    if g > 0.55 and b > 0.55 and r < 0.4:               return "CYAN band P2"
    if r > 0.6 and g > 0.6 and b < 0.35:                return "YELLOW seam-cell redraw"
    return "untinted / other"

def run(ctrl):
    print("=" * 78)
    print("SEAM FACE CUT — pixel history at (%d, %d)" % (PIXEL_X, PIXEL_Y))
    print("=" * 78)

    tex = ctrl.GetTextures()
    target = ctrl.GetRootActions()[0].outputs[0] if ctrl.GetRootActions() else None

    # ---- 1. PIXEL HISTORY -------------------------------------------------------------
    # Find the backbuffer / main colour target from the final action.
    actions = []
    def walk(list_):
        for a in list_:
            actions.append(a)
            walk(a.children)
    walk(ctrl.GetRootActions())

    last = None
    for a in actions:
        if a.outputs and a.outputs[0] != rd.ResourceId.Null():
            last = a
    if last is None:
        print("!! could not find a colour target"); return
    rt = last.outputs[0]

    hist = ctrl.PixelHistory(rt, PIXEL_X, PIXEL_Y, rd.Subresource(), rd.CompType.Typeless)
    print("\n%d fragments touched this pixel\n" % len(hist))
    print("%-8s %-34s %-26s %s" % ("EVENT", "RESULT", "PAINTER (by tint)", "DEPTH before/after"))
    print("-" * 100)
    for m in hist:
        painter = classify(m.shaderOut.col)
        print("%-8d %-34s %-26s %.6f / %.6f" % (
            m.eventId, fmt_mod(m), painter, m.preMod.depth, m.shaderOut.depth))

    # ---- 2. WHO WROTE THE STORED DEPTH ------------------------------------------------
    passed = [m for m in hist if fmt_mod(m) == "PASSED"]
    if passed:
        w = passed[-1]
        print("\nLAST FRAGMENT TO WIN THIS PIXEL: event %d (%s), depth %.6f"
              % (w.eventId, classify(w.shaderOut.col), w.shaderOut.depth))

    # ---- 3. DID THE ORANGE PAINTER EVEN REACH THIS PIXEL? -----------------------------
    orange = [m for m in hist if "ORANGE" in classify(m.shaderOut.col)]
    print("\n" + "=" * 78)
    if not orange:
        print("VERDICT: NO ORANGE FRAGMENT REACHED THIS PIXEL AT ALL.")
        print("  => the main-pass projection did not RASTERIZE here. The draw is issued (the")
        print("     probe log proves that) but its geometry/clip removes it before fragments")
        print("     exist. Look at the clip plane and the submitted vertices, not at depth.")
    else:
        print("VERDICT: %d ORANGE fragment(s) DID reach this pixel." % len(orange))
        for m in orange:
            print("   event %d -> %s (depth %.6f vs stored %.6f)"
                  % (m.eventId, fmt_mod(m), m.shaderOut.depth, m.preMod.depth))
        print("  => the projection rasterized and was KILLED BY A TEST. The reason column above")
        print("     names it, and the depth pair says what it lost to.")
    print("=" * 78)

if 'pyrenderdoc' in globals():
    pyrenderdoc.Replay().BlockInvoke(run)
else:
    print("Run this inside RenderDoc's Python Shell (Window > Python Shell).")
