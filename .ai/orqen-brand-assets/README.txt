ORQEN BRAND ASSET PACK  (logo concept: Squircle)
================================================
Colors:  Dark #141414   White #FFFFFF   Light #F4F4F1

svg/      Vector masters (mark, wordmark, horizontal + vertical lockups, app icons).
          The wordmark is drawn as paths, so it needs no font installed.
png/
  logo/       Transparent PNGs: mark (white/black, 256-2048px) and lockups.
  app-icon/   1024 master icons (opaque square for App Store, rounded, light variant), 512 Play Store icon.
  social/     Avatars (square/round), OG image 1200x630, banner 1500x500.
ios/      AppIcon.appiconset (drop into Xcode Assets.xcassets) + legacy-sizes (20 to 1024).
          App Store icon is opaque square (iOS rounds the corners itself).
android/res/  Copy into app/src/main/res. mipmap-* launcher icons (48dp), round icons,
          adaptive foreground/monochrome layers (108dp), adaptive XML, background color #141414.
          Play Store: png/app-icon/orqen-icon-512-play-store.png
web/      favicon.ico, favicon.svg, favicon 16/32/48, apple-touch-icon (180), icon-192/512,
          maskable icons, mstile-150, og-image.png, site.webmanifest, head-snippet.html (paste into <head>).
macos/    orqen.icns

Usage tips
- Keep clear space around the mark of at least 1/4 of its width.
- Minimum size: mark 16px (use favicon files below 32px), lockup 120px wide.
- Use white versions on dark backgrounds, black versions on light backgrounds.
- Before public launch, run a trademark search (e.g. Thailand DIP) for "Orqen" and the mark.
