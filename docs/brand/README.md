# ExecuServe brand kit

The mark is the **Block**: a chip package seen from above as one solid object. Its three faces
are parted by cuts, legs hang from the two lower edges, and an ember die sits on the lid.
Every file here, and every copy in the app and the web chat, is written by
`tools/design/mark.py`. Change the numbers there and rerun it (`--png` also renders the PNGs).

| File | Use |
|---|---|
| `mark.svg`, `mark-dark.svg` | The mark on light and on dark grounds |
| `mark-small.svg` | Below 40 px: wider cuts, two legs a side, a larger die |
| `mark-mono.svg` | One colour; the die sits solid in a socket cut into the lid |
| `lockup.svg`, `lockup-dark.svg` | Mark and wordmark (Red Hat Display Bold, outlined) |
| `play-icon.svg` / `.png` | 512 px Play icon, opaque square; Play applies its own mask |
| `feature-graphic.svg` / `.png` | 1024 × 500 Play feature graphic |
| `mark.png` | 1024 px mark on transparency |

**Colour.** Ink `#262626` or paper `#F3F4F7` for the body, ember `#EE4C2C` for the die and
nowhere else in the mark. Interface text that needs an ember uses the darker accessible ember
from `ui/Theme.kt`, not this one.

**Space.** Keep clear space of half the die's width on every side. On the launcher, the farthest
point sits 30 dp from the centre of the 108 dp canvas, inside the 33 dp circle that every
mask keeps.

**Don't** recolour the faces separately, add outlines or shadows, close the cuts, or rotate
the package. Don't place PyTorch's or ExecuTorch's logo inside or beside the mark as one
unit. The PyTorch Foundation's guidelines rule that out; credit ExecuTorch in text instead.
