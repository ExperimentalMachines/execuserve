"""
WCAG 2 contrast ratios and APCA (0.0.98G-4g) lightness contrast for every text/background
pair the app uses, in both themes. Run after any palette change:

    python3 tools/design/contrast.py            # reads the palette below
Targets (what this project holds itself to):
  body text        WCAG >= 4.5   APCA |Lc| >= 75   (14-16 sp, regular)
  small labels     WCAG >= 4.5   APCA |Lc| >= 75   (12 sp, semibold pills and captions)
  headings         WCAG >= 4.5   APCA |Lc| >= 60   (>= 20 sp)
  non-text marks   WCAG >= 3.0   APCA |Lc| >= 30   (status lights, input outlines)
"""
import sys

def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))

def wcag_lum(rgb):
    def ch(c):
        c = c / 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = map(ch, rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b

def wcag(fg, bg):
    a, b = wcag_lum(hex_rgb(fg)), wcag_lum(hex_rgb(bg))
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)

def apca(fg, bg):
    def y(rgb):
        r, g, b = [(c / 255) ** 2.4 for c in rgb]
        v = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        return v + (0.022 - v) ** 1.414 if v < 0.022 else v
    yt, yb = y(hex_rgb(fg)), y(hex_rgb(bg))
    if abs(yb - yt) < 0.0005:
        return 0.0
    if yb > yt:
        s = (yb ** 0.56 - yt ** 0.57) * 1.14
        out = 0 if s < 0.1 else s - 0.027
    else:
        s = (yb ** 0.65 - yt ** 0.62) * 1.14
        out = 0 if s > -0.1 else s + 0.027
    return out * 100

TARGET = {"body": (4.5, 75), "label": (4.5, 75), "heading": (4.5, 60), "mark": (3.0, 30)}

def audit(name, palette, pairs):
    print(f"\n== {name}")
    failed = 0
    for fg, bg, kind, where in pairs:
        f, b = palette[fg], palette[bg]
        ratio, lc = wcag(f, b), apca(f, b)
        need_w, need_a = TARGET[kind]
        ok = ratio >= need_w and abs(lc) >= need_a
        failed += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {kind:7s} {fg:>24s} on {bg:<22s} WCAG {ratio:5.2f}  APCA Lc {lc:6.1f}   {where}")
    return failed

def pairs():
    return [
        # Text on the page and in panels
        ("onBackground", "background", "heading", "tab title"),
        ("onSurface", "surface", "body", "body text, text actions"),
        ("onSurfaceVariant", "surface", "body", "secondary text, figure labels, log figures"),
        ("onSurfaceVariant", "background", "body", "captions on the page"),
        ("onSurface", "surfaceContainer", "body", "Try it reply box, lab tile ground"),
        ("surface", "onSurface", "label", "ink button (Send, Share) label"),
        # Brand: Start, selection, the header's mark
        ("onPrimary", "primary", "label", "Start button"),
        ("primary", "surface", "label", "selected nav label, selected text"),
        ("onPrimaryContainer", "primaryContainer", "body", "selected segment, nav indicator"),
        # State words and lights on neutral panels (the status panel, the log, the header)
        ("good", "surface", "label", "Serving, Done"),
        ("working", "surface", "label", "Working, Tool call"),
        ("attention", "surface", "label", "Starting, Paused, Cut off, warnings"),
        ("failed", "surface", "label", "Could not start, Failed, Delete"),
        ("good", "background", "label", "header: Serving"),
        ("working", "background", "label", "header: Working"),
        ("attention", "background", "label", "header: Paused"),
        ("failed", "background", "label", "header: Not responding"),
        # Pills: the only place a state colour fills a shape
        ("onGoodContainer", "goodContainer", "label", "Loaded pill"),
        ("onWorkingContainer", "workingContainer", "label", "working pill"),
        ("onAttentionContainer", "attentionContainer", "label", "amber pill"),
        ("onFailedContainer", "failedContainer", "label", "crimson pill"),
        ("onSurface", "surfaceVariant", "label", "neutral pill"),
        # Marks
        ("good", "surface", "mark", "status light"),
        ("outline", "surface", "mark", "input, segment and button outlines"),
        ("working", "surface", "mark", "progress"),
        ("primary", "surface", "mark", "switch track, focus"),
    ]

if __name__ == "__main__":
    import importlib.util, pathlib
    here = pathlib.Path(__file__).parent
    spec = importlib.util.spec_from_file_location("palette", here / "palette.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    total = sum(audit(name, pal, pairs()) for name, pal in module.PALETTES.items())
    print(f"\n{total} pair(s) below target")
    sys.exit(1 if total else 0)
