# Fonts

This app uses two custom fonts. Download them from Google Fonts and place them here:

## Required font files

### Orbitron (Display / headings)
- `orbitron_regular.ttf`   — weight 400
- `orbitron_semibold.ttf`  — weight 600
- `orbitron_bold.ttf`      — weight 700

Download: https://fonts.google.com/specimen/Orbitron

### Share Tech Mono (Data / body)
- `share_tech_mono_regular.ttf` — weight 400

Download: https://fonts.google.com/specimen/Share+Tech+Mono

## Quick download script (run from project root)

```bash
mkdir -p app/src/main/res/font
cd app/src/main/res/font

# Download via curl (filenames may differ — rename after download)
curl -L "https://fonts.gstatic.com/s/orbitron/v31/yMJMMIlzdpvBhQQL_SC3X9yhF25-T1nysimBoWgz.woff2" -o orbitron_regular.ttf
```

Or use Android Studio's built-in **Resource Manager → Font → Add** to download directly from Google Fonts.

> **Note:** Without these files the project will not compile. The `Typography.kt` references them via `R.font.*`.
