# Play Store Graphics

This directory contains the graphics needed for the Google Play Store listing.

## Files Created

### App Icon
- **app_icon_512.png** (40 KB)
  - 512x512 PNG, 32-bit RGBA
  - Required for Play Store high-resolution icon
  - Design: Battery with location pin overlay and EUC wheel
  - Colors: Blue gradient background (#1976D2 to #2196F3)

### Feature Graphic
- **feature_graphic_1024x500.png** (39 KB)
  - 1024x500 PNG
  - Required for Play Store listing header
  - Shows app flow: EUC battery → connection → OsmAnd integration
  - Includes app name and tagline

### Source Files (SVG)
- **app_icon_512.svg** - Vector source for app icon
- **feature_graphic_1024x500.svg** - Vector source for feature graphic

## Icon Design Elements

The app icon combines three key elements:

1. **Battery (White/Green)** - Primary element representing EUC battery monitoring
2. **Location Pin (Blue)** - Represents OsmAnd/navigation integration
3. **Wheel (Cyan)** - Small EUC wheel symbol with connectivity indicators

## Upload to Play Store

1. Go to Google Play Console → Your App → Store Presence → Main Store Listing
2. Under "Graphical Assets":
   - **App icon**: Upload `app_icon_512.png`
   - **Feature graphic**: Upload `feature_graphic_1024x500.png`

## Rebuilding Graphics

If you need to modify the graphics:

1. Edit the SVG files
2. Regenerate PNG files:
   ```bash
   rsvg-convert -w 512 -h 512 app_icon_512.svg -o app_icon_512.png
   rsvg-convert -w 1024 -h 500 feature_graphic_1024x500.svg -o feature_graphic_1024x500.png
   ```

## Color Palette

- **Primary Blue**: #2196F3
- **Dark Blue**: #1976D2  
- **Battery Green**: #4CAF50
- **Accent Cyan**: #03DAC5
- **Dark Gray**: #1A1A1A
- **White**: #FFFFFF

## Additional Graphics Needed

For a complete Play Store listing, you'll also need:

- [ ] **Screenshots** (minimum 2, recommended 4-8)
  - Phone: 320-3840px on each side
  - Suggested: Main app, OsmAnd integration, Android Auto, Trip meters
  
- [ ] **Promo Video** (optional but recommended)
  - YouTube URL showing app functionality

## Notes

- The in-app launcher icon has been updated to match this design
- The icon uses adaptive icon format for Android 8.0+
- Source SVG files are included for future modifications
