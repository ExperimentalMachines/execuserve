# The palette as Theme.kt defines it (copied; the audit reads this file).
# Neutral PyTorch greys (paper #F3F4F7, ink #262626), ember for the brand and Start, and
# state colours kept off ember's hue: failure is crimson, not red-orange.
PALETTES = {
    "light": dict(
        primary="#B83012", onPrimary="#FFFFFF", primaryContainer="#FCE4DE", onPrimaryContainer="#5C1405",
        good="#176C3C", goodContainer="#D7F2E1", onGoodContainer="#0B3D20",
        working="#2B57BF", workingContainer="#DDE5FA", onWorkingContainer="#122B66",
        attention="#855400", attentionContainer="#FFE8C2", onAttentionContainer="#3D2800",
        failed="#B01652", failedContainer="#FBDCE7", onFailedContainer="#5E0A2B",
        background="#F3F4F7", onBackground="#262626", surface="#FFFFFF", onSurface="#262626",
        surfaceVariant="#ECEDF1", onSurfaceVariant="#5B5F67", surfaceContainer="#F3F4F7",
        outline="#868D9A", outlineVariant="#E2E4E9",
    ),
    "dark": dict(
        primary="#FAC6BB", onPrimary="#2B0A03", primaryContainer="#6A2413", onPrimaryContainer="#FFDAD2",
        good="#79E3A7", goodContainer="#123D26", onGoodContainer="#B7F0CD",
        working="#C0CEF1", workingContainer="#1D2F5C", onWorkingContainer="#DCE5FF",
        attention="#FFC45E", attentionContainer="#4A3300", onAttentionContainer="#FFE3B0",
        failed="#F7BFD5", failedContainer="#5A1330", onFailedContainer="#FFD9E6",
        background="#121212", onBackground="#ECECEC", surface="#1C1C1E", onSurface="#ECECEC",
        surfaceVariant="#2A2A2D", onSurfaceVariant="#CCCED2", surfaceContainer="#242427",
        outline="#747A84", outlineVariant="#3A3A40",
    ),
}
