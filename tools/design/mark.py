"""
The ExecuServe mark, the Block, drawn once and written everywhere it appears:

    android  launcher foreground and monochrome layers, notification icon
    compose  MarkPaths.kt, which ui/Mark.kt draws in the console header
    web      mark.svg (favicon) and the two inline symbols in index.html
    docs     docs/brand: marks, lockups, Play icon and feature graphic

    python3 tools/design/mark.py           # SVG, XML and Kotlin outputs
    python3 tools/design/mark.py --png     # also PNGs, rendered by Chrome

A chip package seen from above as one solid object. Its three faces are parted by cuts of
constant width, legs hang from the two lower edges, and an ember die sits on the lid. The
cuts are real gaps, not lines painted in a background colour, so the mark sits on any
ground. In one colour the die stays solid, seated in a socket cut into the lid.

Everything is straight lines in a 100-unit box, so moving the mark between formats is
arithmetic on points: no masks, filters or arcs. The lockup needs uharfbuzz and fontTools
(pip install uharfbuzz fonttools) and the vendored Red Hat Display in fonts/.
"""
import math
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
INK, PAPER, EMBER, WHITE = "#262626", "#F3F4F7", "#EE4C2C", "#FFFFFF"

# The box: 72 wide, the lid a rhombus 40 tall, the sides 20 deep.
L, R, C = 14.0, 86.0, 50.0
TOP, LID, DEPTH = 18.0, 20.0, 20.0
MID, LOW = TOP + LID, TOP + 2 * LID

# Full drawing for 40 px and up; the small one (wider cuts, two fat legs a side, a larger
# die) for favicons, the notification and anything under 40 px.
FULL = dict(cut=2.6, legs=3, leg_w=6.0, leg_h=9.0, die=0.5)
SMALL = dict(cut=3.4, legs=2, leg_w=8.0, leg_h=10.0, die=0.56)

# In one colour (status bar, themed icon) Android keeps only the shape, so ember is gone. The
# die stays solid and sits in a socket cut around it, a ring as wide as the face cuts; a hole
# instead would show whatever is behind the icon, black on a dark shade.
MONO_DIE = {id(FULL): (0.5, 0.65), id(SMALL): (0.5, 0.68)}       # die, socket (lid fractions)


# ---- geometry ----------------------------------------------------------------

def _line_cross(p1, d1, p2, d2):
    """Intersection of two lines given as point + direction."""
    det = d1[0] * d2[1] - d1[1] * d2[0]
    t = ((p2[0] - p1[0]) * d2[1] - (p2[1] - p1[1]) * d2[0]) / det
    return p1[0] + t * d1[0], p1[1] + t * d1[1]


def inset(poly, shared, e):
    """Move the polygon's shared edges inward by e and keep its outer edges. Each
    face gives up half of every cut, so the gap is 2e wide along its whole length and
    opens into the silhouette as a small notch, the way RunPod's cube is cut."""
    n = len(poly)
    cx = sum(p[0] for p in poly) / n
    cy = sum(p[1] for p in poly) / n
    lines = []
    for i in range(n):
        a, b = poly[i], poly[(i + 1) % n]
        dx, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dx, dy)
        nx, ny = -dy / length, dx / length
        if (cx - a[0]) * nx + (cy - a[1]) * ny < 0:     # point the normal inward
            nx, ny = -nx, -ny
        d = e if i in shared else 0.0
        lines.append(((a[0] + nx * d, a[1] + ny * d), (dx, dy)))
    return [_line_cross(*lines[i - 1], *lines[i]) for i in range(n)]


def block(spec):
    """Polygons of the mark: the body (lid, two sides, legs) and the die."""
    e = spec["cut"] / 2
    lid = inset([(C, TOP), (R, MID), (C, LOW), (L, MID)], {1, 2}, e)
    left = inset([(L, MID), (C, LOW), (C, LOW + DEPTH), (L, MID + DEPTH)], {0, 1}, e)
    right = inset([(C, LOW), (R, MID), (R, MID + DEPTH), (C, LOW + DEPTH)], {0, 3}, e)
    legs = []
    n, w, h = spec["legs"], spec["leg_w"], spec["leg_h"]
    slope = LID / (C - L)
    for i in range(n):
        t = (i + 1) / (n + 1)
        for x0, sign in ((L, 1), (R, -1)):
            x = x0 + sign * (C - e - L) * t
            y = MID + DEPTH + slope * abs(x - x0)
            dx, dy = sign * w / 2, w / 2 * slope
            legs.append([(x - dx, y - dy), (x + dx, y + dy), (x + dx, y + dy + h), (x - dx, y - dy + h)])
    return [lid, left, right] + legs, rhombus(spec["die"])


def rhombus(k):
    return [(C, MID - LID * k), (C + (R - C) * k, MID), (C, MID + LID * k), (C - (C - L) * k, MID)]


def block_mono(spec):
    """One-colour polygons: the lid with its socket (draw even-odd), then sides, legs and
    the die (draw non-zero)."""
    polys, _ = block(spec)
    die_k, socket_k = MONO_DIE[id(spec)]
    return [polys[0], rhombus(socket_k)], polys[1:] + [rhombus(die_k)]


def bounds(polys):
    xs = [p[0] for poly in polys for p in poly]
    ys = [p[1] for poly in polys for p in poly]
    return min(xs), min(ys), max(xs), max(ys)


def fit(polys, die, size, box):
    """Scale and centre the mark so its bounding box fits `box` inside a `size` square."""
    x0, y0, x1, y1 = bounds(polys)
    s = box / max(x1 - x0, y1 - y0)
    ox, oy = size / 2 - s * (x0 + x1) / 2, size / 2 - s * (y0 + y1) / 2
    move = lambda poly: [(ox + s * x, oy + s * y) for x, y in poly]
    return [move(p) for p in polys], move(die)


def fit_circle(polys, die, size, radius):
    """Scale and centre so the farthest point sits on a circle: adaptive icons are cut
    by launcher masks of any shape, and only the inner circle is guaranteed."""
    x0, y0, x1, y1 = bounds(polys)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    far = max(math.hypot(x - cx, y - cy) for poly in polys for x, y in poly)
    s = radius / far
    move = lambda poly: [(size / 2 + s * (x - cx), size / 2 + s * (y - cy)) for x, y in poly]
    return [move(p) for p in polys], move(die)


def num(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def d(polys, sep=" "):
    return "".join("M" + "L".join(f"{num(x)}{sep}{num(y)}" for x, y in poly) + "Z" for poly in polys)


# ---- outputs -----------------------------------------------------------------

GENERATED = "Generated by tools/design/mark.py; edit the numbers there, not this file."


def android_vector(paths, size_dp, viewport, comment):
    body = "\n".join(
        f'    <path\n        android:fillColor="{colour}"\n'
        + (f'        android:fillType="evenOdd"\n' if even else "")
        + f'        android:pathData="{data}" />'
        for colour, data, even in paths)
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!--\n  {comment}\n  {GENERATED}\n-->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size_dp}dp"\n    android:height="{size_dp}dp"\n'
            f'    android:viewportWidth="{viewport}"\n    android:viewportHeight="{viewport}">\n{body}\n</vector>\n')


def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    print("wrote", rel)


def android():
    res = "android/app/src/main/res/drawable/"
    # Launcher: paper package on the ink background layer, the die in ember. The farthest
    # point sits 30 from the centre, inside the 33 every launcher mask keeps.
    polys, die = fit_circle(*block(FULL), 108, 30.0)
    write(res + "ic_launcher_foreground.xml", android_vector(
        [("@color/paper", d(polys, ","), False), ("@color/ember", d([die], ","), False)], 108, 108,
        "The Block: a chip package seen from above, its faces parted by cuts, an ember die on the lid."))
    # Themed icon: one colour, the die solid in its socket. Same transform as the launcher.
    lid, rest = block_mono(FULL)
    body, _ = block(FULL)
    lid, rest = mono_fit(body, lid, rest, lambda polys, die: fit_circle(polys, die, 108, 30.0))
    write(res + "ic_launcher_monochrome.xml", android_vector(
        [("#FFFFFF", d(lid, ","), True), ("#FFFFFF", d(rest, ","), False)], 108, 108,
        "The Block in one colour for themed icons: the die sits solid in a socket cut into the lid."))
    # Notification: the small drawing in 20 of 24 dp, white on alpha as Android asks.
    lid, rest = block_mono(SMALL)
    body, _ = block(SMALL)
    lid, rest = mono_fit(body, lid, rest, lambda polys, die: fit(polys, die, 24, 20.0))
    write(res + "ic_stat_serve.xml", android_vector(
        [("#FFFFFF", d(lid, ","), True), ("#FFFFFF", d(rest, ","), False)], 24, 24,
        "Status-bar icon: the small Block in one colour, the die solid in its socket."))


def mono_fit(body, lid, rest, place):
    """Move one-colour polygons with the transform the coloured mark gets, so both
    layers of the adaptive icon line up exactly."""
    moved, _ = place(body + lid + rest, body[0])
    n_body, n_lid = len(body), len(lid)
    return moved[n_body:n_body + n_lid], moved[n_body + n_lid:]


def compose():
    full_body, full_die = fit(*block(FULL), 100, 100.0)
    small_body, small_die = fit(*block(SMALL), 100, 100.0)
    write("android/app/src/main/kotlin/org/experimentalmachines/execuserve/app/ui/MarkPaths.kt",
          "package org.experimentalmachines.execuserve.app.ui\n\n"
          f"// {GENERATED}\n"
          "// Path data for ui/Mark.kt in a 100-unit square: the body and the die, full and small.\n"
          "internal object MarkPaths {\n"
          f"    const val BODY ={kotlin_string(full_body)}\n"
          f"    const val DIE ={kotlin_string([full_die])}\n"
          f"    const val SMALL_BODY ={kotlin_string(small_body)}\n"
          f"    const val SMALL_DIE ={kotlin_string([small_die])}\n"
          "}\n")


def kotlin_string(polys):
    """One polygon per line, joined with +, so no line passes ktlint's limit."""
    parts = [f'"{d([poly])}"' for poly in polys]
    return "\n" + " +\n".join(f"        {part}" for part in parts)


def web():
    web_dir = "shared/server/src/commonMain/web/"
    # The server's Content-Security-Policy (style-src 'self', sent with every asset) blocks
    # inline styles: a style attribute or a <style> element here is silently dropped and the
    # shape falls back to black. Colour only with presentation attributes and chat.css.
    #
    # Favicon: the launcher icon in small, a paper Block on an ink tile, readable on light and
    # dark tab strips alike without a media query.
    polys, die = fit_circle(*block(SMALL), 100, 36.0)
    write(web_dir + "mark.svg",
          f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" rx="22" fill="{INK}"/>'
          f'<path fill="{PAPER}" d="{d(polys)}"/><path fill="{EMBER}" d="{d([die])}"/></svg>\n')
    # Inline symbols: the body takes the page's ink through currentColor (so the chat's own
    # theme toggle reaches it, which an <img> could not); the die keeps the true ember.
    symbols = []
    for name, spec in (("mark", FULL), ("mark-small", SMALL)):
        body, die = fit(*block(spec), 100, 96.0)
        symbols.append(f'<symbol id="{name}" viewBox="0 0 100 100"><path fill="currentColor" d="{d(body)}"/>'
                       f'<path fill="{EMBER}" d="{d([die])}"/></symbol>')
    sprite = ('<svg class="sprite" aria-hidden="true" focusable="false">' + "".join(symbols) + "</svg>")
    index = ROOT / web_dir / "index.html"
    text = index.read_text()
    new = re.sub(r"<!-- mark:start -->.*?<!-- mark:end -->",
                 lambda _: f"<!-- mark:start -->{sprite}<!-- mark:end -->", text, flags=re.S)
    if new == text and "<!-- mark:start -->" not in text:
        sys.exit("index.html has no <!-- mark:start --> ... <!-- mark:end --> markers")
    index.write_text(new)
    print("wrote", web_dir + "index.html (symbols)")


# ---- brand kit -----------------------------------------------------------------

def text_path(text, font_file, size, tracking=0.0):
    """HarfBuzz shaping (so kerning applies) and fontTools outlines; baseline at y = 0."""
    import uharfbuzz as hb
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.ttLib import TTFont
    face = hb.Face(hb.Blob.from_file_path(str(font_file)))
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hb.Font(face), buf, {"kern": True, "liga": True})
    tt = TTFont(str(font_file))
    glyphs, order = tt.getGlyphSet(), tt.getGlyphOrder()
    scale = size / face.upem
    pen = SVGPathPen(glyphs)
    x = 0.0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        glyphs[order[info.codepoint]].draw(TransformPen(pen, (scale, 0, 0, -scale, x + pos.x_offset * scale, 0)))
        x += pos.x_advance * scale + tracking * size
    return pen.getCommands(), x - tracking * size


def mark_svg(body_colour, die_colour=EMBER, mono=False, spec=FULL):
    body, die = fit(*block(spec), 100, 96.0)
    if mono:
        lid, rest = block_mono(spec)
        lid, rest = mono_fit(block(spec)[0], lid, rest, lambda polys, die: fit(polys, die, 100, 96.0))
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><path fill="{body_colour}" '
                f'fill-rule="evenodd" d="{d(lid)}"/><path fill="{body_colour}" d="{d(rest)}"/></svg>\n')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><path fill="{body_colour}" d="{d(body)}"/>'
            f'<path fill="{die_colour}" d="{d([die])}"/></svg>\n')


def lockup_svg(ink):
    """Mark and wordmark: Red Hat Display Bold, the mark as tall as twice the cap height."""
    size = 64.0
    word, advance = text_path("ExecuServe", ROOT / "tools/design/fonts/RedHatDisplay-Bold.ttf", size, -0.012)
    cap = 0.7 * size
    height = 2.15 * cap
    body, die = fit(*block(FULL), height, height)
    x0, _, x1, _ = bounds(body)
    shift = -x0
    gap = 0.3 * size
    tx = (x1 - x0) + gap
    width = tx + advance
    move = lambda poly: [(x + shift, y) for x, y in poly]
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {num(width)} {num(height)}">'
            f'<path fill="{ink}" d="{d([move(p) for p in body])}"/><path fill="{EMBER}" d="{d([move(die)])}"/>'
            f'<path fill="{ink}" transform="translate({num(tx)} {num(height / 2 + cap / 2)})" d="{word}"/></svg>\n')


def play_icon_svg():
    """512 Play icon: full square, opaque, the same keyline as the launcher (Play masks it)."""
    polys, die = fit_circle(*block(FULL), 108, 30.0)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><rect x="18" y="18" width="72" height="72" fill="{INK}"/>'
            f'<path fill="{PAPER}" d="{d(polys)}"/><path fill="{EMBER}" d="{d([die])}"/></svg>\n')


def feature_graphic_svg():
    """1024 x 500 Play feature graphic: the lockup in paper on ink, nothing else."""
    lock = lockup_svg(PAPER)
    vb = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', lock)
    w, h = float(vb[1]), float(vb[2])
    s = 700 / w
    inner = lock[lock.index(">") + 1:lock.rindex("</svg>")]
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 500"><rect width="1024" height="500" fill="{INK}"/>'
            f'<g transform="translate({num(512 - w * s / 2)} {num(250 - h * s / 2)}) scale({num(s)})">{inner}</g></svg>\n')


def brand_kit(png):
    write("docs/brand/mark.svg", mark_svg(INK))
    write("docs/brand/mark-dark.svg", mark_svg(PAPER))
    write("docs/brand/mark-mono.svg", mark_svg(INK, mono=True))
    write("docs/brand/mark-small.svg", mark_svg(INK, spec=SMALL))
    write("docs/brand/lockup.svg", lockup_svg(INK))
    write("docs/brand/lockup-dark.svg", lockup_svg(PAPER))
    write("docs/brand/play-icon.svg", play_icon_svg())
    write("docs/brand/feature-graphic.svg", feature_graphic_svg())
    if png:
        chrome = shutil.which("google-chrome") or "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        for name, w, h, bg in (("play-icon", 512, 512, "ff262626"), ("feature-graphic", 1024, 500, "ff262626"),
                               ("mark", 1024, 1024, "00000000")):
            page = ROOT / "build" / f"{name}.html"
            page.parent.mkdir(exist_ok=True)
            page.write_text(f"<html><body style='margin:0'><img src='{ROOT}/docs/brand/{name}.svg' "
                            f"style='display:block;width:{w}px;height:{h}px'></body></html>")
            subprocess.run([chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                            f"--default-background-color={bg}", f"--window-size={w},{h}",
                            f"--screenshot={ROOT}/docs/brand/{name}.png", page.as_uri()], capture_output=True, check=True)
            page.unlink()
            print("wrote", f"docs/brand/{name}.png")


if __name__ == "__main__":
    android()
    compose()
    web()
    brand_kit("--png" in sys.argv)
