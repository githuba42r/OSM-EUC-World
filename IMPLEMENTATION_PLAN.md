# EUC OsmAnd Plugin - Implementation Plan

## Current Status (Checkpoint: Feb 2026)

### Completed Features

#### 1. OsmAnd AIDL Widget Integration (Phone Only)
- **EUC Battery & Voltage** - Combined display
- **EUC Battery %** - Battery percentage only
- **EUC Voltage** - Voltage only
- **EUC Trip A/B/C** - App-managed trip meters
- Widgets show "NC" (Not Connected) when EUC World unavailable
- Widgets persist in OsmAnd's "Add Widget" list regardless of connection state

#### 2. Android Auto Car App
- Quick-glance display optimized for driving
- Shows battery % and voltage as primary display
- Shows active trip meters only (A/B/C if set)
- Shows "NC" when disconnected
- Separate app from OsmAnd (user taps EUC World icon to view)

#### 3. Home Screen Widget
- Compact widget showing battery %, voltage, speed
- Updates in real-time when service running

#### 4. Main App Features
- HUD-style battery display
- Real-time metrics (speed, temp, power, load)
- Trip meter management (reset A/B/C individually or clear all)
- Settings for API host/port, update interval
- Minimize notification option

#### 5. Service & Connectivity
- Foreground service polling EUC World API
- Auto-start on boot option
- Package: `com.a42r.eucosmandplugin`

---

## Architecture Limitations

### OsmAnd AIDL Widgets - Phone Only

**Important Discovery:** OsmAnd's AIDL widgets only work on the phone screen, NOT on Android Auto.

| Display | Widget Support |
|---------|----------------|
| Phone OsmAnd app | ✅ AIDL widgets work |
| Android Auto OsmAnd | ❌ AIDL widgets NOT supported |

**Reason:** OsmAnd uses completely different rendering systems:
- Phone: `MapInfoLayer` with `TextInfoWidget` (Android Views)
- Android Auto: `SurfaceRenderer` with hardcoded widgets only (speedometer, alarms)

There is no configuration or permission that enables AIDL widgets on Android Auto's OsmAnd display.

---

## Future Enhancement Options

### Option 1: Android Auto Notifications (Recommended)
Add notification support so users can see battery % in Android Auto's notification area without opening the EUC World app.

**Pros:**
- Quick glance at battery % without leaving OsmAnd
- Appears in notification tray
- Can be updated periodically

**Cons:**
- Not a persistent on-screen indicator
- User must swipe to see notifications

**Implementation:**
1. Create notification channel for Android Auto
2. Post ongoing notification with battery %, voltage
3. Update notification when data changes
4. Use `CarAppExtender` for Android Auto styling

### Option 2: Improve Car App Quick-Glance
Make the Android Auto Car App even simpler/faster to read.

**Ideas:**
- Larger text for battery %
- Color coding (red/yellow/green) if supported
- Minimal UI - just the essentials

### Option 3: Request OsmAnd Feature
File feature request with OsmAnd developers to support AIDL widgets on Android Auto.

**Pros:**
- Would integrate seamlessly with OsmAnd navigation

**Cons:**
- Requires significant OsmAnd architectural changes
- Timeline uncertain

### Option 4: Split-Screen (Hardware Dependent)
Some Android Auto head units support split-screen display.

**Pros:**
- Could show OsmAnd + EUC World side by side

**Cons:**
- Rare feature, not universally available
- No software changes needed, just documentation

---

## Recommended Usage Patterns

### Scenario 1: Phone Mounted on Handlebars
- Use OsmAnd on phone with EUC widgets visible on map
- Best experience - all data on one screen

### Scenario 2: Android Auto in Car (Transporting EUC)
- Use OsmAnd for navigation on Android Auto
- Tap EUC World app icon for battery check
- OsmAnd widgets work on phone if needed

### Scenario 3: Android Auto on Motorcycle/Scooter
- Use OsmAnd for navigation
- Quick tap to EUC World app for battery status
- Consider adding notification support for easier access

---

## API Reference

### EUC World Internal Webservice
- **Endpoint:** `http://127.0.0.1:8080/api/values`
- **Format:** JSON key-value pairs

| Key | Description |
|-----|-------------|
| vba | Battery percentage |
| vvo | Battery voltage |
| vsp | Speed |
| vte | Temperature |
| vdv | Wheel trip distance |
| vdt | Total odometer |
| vmmo | Wheel model name |

### OsmAnd AIDL API
- **Service:** `net.osmand.aidl.OsmandAidlServiceV2`
- **Packages:** `net.osmand`, `net.osmand.plus`, `net.osmand.dev`
- **Widget Methods:** `addMapWidget()`, `updateMapWidget()`, `removeMapWidget()`

---

## File Structure

```
app/src/main/java/com/a42r/eucosmandplugin/
├── api/
│   ├── EucData.kt              # Data models
│   └── EucWorldApiClient.kt    # HTTP client
├── auto/
│   ├── EucWorldCarAppService.kt # Android Auto entry point
│   └── EucWorldScreen.kt       # Android Auto display
├── receiver/
│   └── BootReceiver.kt         # Boot start receiver
├── service/
│   ├── AutoProxyReceiver.kt    # Android Auto IPC
│   ├── ConnectionState.kt      # Connection states
│   ├── EucWorldService.kt      # Main foreground service
│   ├── OsmAndAidlHelper.kt     # OsmAnd widget management
│   └── OsmAndConnectionService.kt
├── ui/
│   ├── MainActivity.kt         # Main UI
│   └── SettingsActivity.kt     # Settings
├── util/
│   └── TripMeterManager.kt     # Trip calculations
├── widget/
│   └── EucBatteryWidgetProvider.kt # Home screen widget
└── EucWorldApplication.kt      # Application class
```

---

## Version History

| Commit | Description |
|--------|-------------|
| f127bdf | Initial commit - full implementation |
| 6385b78 | Clear OsmAnd widgets when EUC World unavailable |
| abbef66 | Show NC on widgets when disconnected |
| d244da3 | Keep widgets in OsmAnd list when disconnected |
| 09d22f0 | Simplify Android Auto Car App for quick-glance |

---

## Next Steps

1. **Test on device** with EUC World and OsmAnd
2. **Consider notification support** for Android Auto quick-glance
3. **Gather user feedback** on Android Auto Car App usability
4. **Version bump** and release when stable
