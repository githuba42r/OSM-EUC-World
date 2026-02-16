# EUC OsmAnd Plugin

An Android app that displays Electric Unicycle (EUC) metrics from the EUC World app across multiple interfaces: OsmAnd map widgets, Android Auto car displays, and home screen widgets. Features three independent trip meters for versatile distance tracking and real-time monitoring of all critical EUC data.

## Features

### Multiple Display Interfaces

**OsmAnd Map Widgets**
- **EUC Battery & Voltage** - Combined display showing battery % and voltage (e.g., "85% 84.2V")
- **EUC Battery %** - Battery percentage only for minimal display
- **EUC Voltage** - Voltage reading only for technical monitoring
- **EUC Trip A/B/C** - Three independent trip meter widgets for distance tracking
- Configurable widget positioning on left or right map panels
- Real-time updates while riding with EUC World connected

**Android Auto Car Display**
- Native Android Auto integration with dedicated automotive interface
- Large HUD-style display optimized for safe glance viewing while driving
- Real-time metrics: battery %, voltage, speed, temperature, power, and load
- Works with both wired and wireless Android Auto connections
- Compatible with built-in car displays and aftermarket head units
- Read-only interface designed for minimal driver distraction

**Home Screen Widget**
- Compact widget showing battery percentage, voltage, and speed
- Updates in real-time when background service is running
- Quick at-a-glance status without opening the app

**Main Phone App**
- HUD-style battery and voltage display with large, readable fonts
- Real-time speed, temperature, power, and load indicators
- Wheel model identification and connection status
- Trip meter management with reset controls
- Settings access for customization

### Trip Meter System

Three independent trip meters (A, B, C) provide versatile distance tracking with different use cases:

**How Trip Meters Work:**
- Each meter independently tracks distance using the wheel's total odometer (vdt)
- Reset stores current odometer reading as baseline; trip = current - baseline
- Accurate measurement even if wheel disconnects temporarily
- All trip values persist across app restarts
- Individual reset or clear all trips at once

**Common Use Cases:**

*Scenario 1: Multi-Timeframe Tracking*
- **Trip A** - Daily commute (reset daily to track today's ride)
- **Trip B** - Weekly/monthly totals (reset weekly/monthly for mileage summaries)
- **Trip C** - Long-term tracking (service intervals, tire wear, major maintenance)

*Scenario 2: Journey Tracking*
- **Trip A** - Outbound journey distance
- **Trip B** - Return journey distance  
- **Trip C** - Total round-trip for complete picture

*Scenario 3: Performance & Maintenance*
- **Trip A** - Current ride session (reset each time you ride)
- **Trip B** - Range testing and efficiency monitoring (reset for range tests)
- **Trip C** - Lifetime tracking since last service/tire change

### Real-Time EUC Monitoring

All interfaces display comprehensive EUC data:
- **Battery Level** - Percentage and voltage for capacity monitoring
- **Speed** - Current riding speed (km/h or mph)
- **Temperature** - Motor/battery temperature (°C or °F)
- **Power** - Real-time power consumption in watts
- **Load** - Current load on the wheel
- **Trip Distance** - All three independent trip meters
- **Wheel Model** - Automatic identification of connected EUC
- **Connection Status** - Live connection indicator

## Requirements

- **Android Version**: Android 8.0 (API 26) or higher
- **EUC World App**: [EUC World](https://euc.world) installed and running with Internal Webservice enabled
- **Electric Unicycle**: Any EUC supported by EUC World, connected via Bluetooth
- **OsmAnd** (optional): [OsmAnd](https://osmand.net) app for map widget functionality
- **Android Auto** (optional): Car with Android Auto support or aftermarket head unit

## Installation

### From APK Release
1. Download the latest APK from the Releases page
2. Enable "Install from unknown sources" if prompted
3. Install the APK on your device

### From Source
```bash
git clone https://github.com/yourusername/OSM-EUC-World.git
cd OSM-EUC-World
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Setup

### 1. EUC World Configuration
1. Open EUC World app
2. Go to Settings > Internal Webservice
3. Enable the webservice (default port: 8080)
4. Connect your EUC wheel

### 2. Plugin Configuration
1. Launch EUC OsmAnd Plugin
2. The service starts automatically
3. Verify "Connected" status appears with your wheel model
4. (Optional) Go to Settings to adjust:
   - API host/port if using non-default
   - Update interval (100-2000ms)
   - Minimize notification option

### 3. OsmAnd Widget Setup
1. Open OsmAnd app
2. Tap the hamburger menu (three lines)
3. Select **Configure screen**
4. Scroll to find the EUC widgets under external plugins
5. Enable desired widgets:
   - **EUC Battery & Voltage** - Shows "85% 84.2V"
   - **EUC Battery %** - Shows "85%"
   - **EUC Voltage** - Shows "84.2V"
   - **EUC Trip A** - Shows trip A distance
   - **EUC Trip B** - Shows trip B distance
   - **EUC Trip C** - Shows trip C distance
6. Position widgets on left or right panel as desired
7. Widgets update automatically when EUC data is available

### 4. Android Auto Setup
1. Connect your phone to Android Auto
2. The EUC World app appears in the car's app launcher
3. Open to see the HUD display with battery, voltage, speed, and temperature

### 5. Home Screen Widget
1. Long-press on your home screen
2. Select "Widgets"
3. Find "EUC Battery Status"
4. Drag to home screen
5. Widget shows battery %, voltage, and speed

## Using the App

### Android Phone App Interface

The main phone app provides comprehensive monitoring and control of your EUC data distribution.

#### Main Screen Display

**Status Overview:**
- **Battery Display** - Large HUD-style battery percentage and voltage (e.g., "85% | 84.2V")
- **Connection Status** - Shows "Connected: [Wheel Model]" or "Disconnected"
- **Wheel Model** - Automatically identifies connected EUC (e.g., "InMotion V12", "Begode RS")

**Real-Time Metrics:**
- **Speed** - Current riding speed with unit (km/h or mph based on settings)
- **Temperature** - Motor/battery temperature (°C or °F)
- **Power** - Current power consumption in watts
- **Load** - Current load percentage on the wheel

**Trip Meter Display:**
All three trip meters (A, B, C) are displayed with:
- Current distance for each trip
- Individual reset buttons for each meter
- "Clear All Trips" button to reset all meters simultaneously
- Distance shown in km or miles based on unit preference

#### Managing Trip Meters

**Viewing Trip Data:**
- All three trips display simultaneously on main screen
- Each shows: "Trip A: 15.2 km", "Trip B: 127.8 km", "Trip C: 1,450.3 km"
- Updates in real-time as you ride

**Resetting Trip Meters:**

*Individual Reset:*
1. Locate the trip meter you want to reset
2. Tap the reset button (↻) next to that specific trip
3. Trip resets to 0.0 and begins tracking from current odometer reading
4. Other trips remain unchanged

*Reset All Trips:*
1. Tap "Clear All Trips" button at bottom of trip section
2. All three trips reset to 0.0 simultaneously
3. All begin tracking from current odometer reading

**Trip Meter Technical Details:**

*How Distance is Calculated:*
- Each trip stores a baseline odometer reading when reset
- Current trip distance = Current wheel odometer (vdt) - Trip baseline
- Uses wheel's total odometer for accuracy (not the wheel's built-in trip)
- Calculations continue correctly even if wheel disconnects temporarily

*Data Persistence:*
- All trip values automatically save to device storage
- Trips persist across app restarts and device reboots
- Baseline odometer values maintained in app preferences
- No data loss if service stops or app closes

*Connection Behavior:*
- If wheel disconnects, trip values freeze at last known distance
- When wheel reconnects, trips resume from where they left off
- Accurate tracking maintained through connection interruptions

#### Background Service

**Service Operation:**
- Runs as Android foreground service for reliable data polling
- Shows persistent notification with connection status
- Continues running when app is backgrounded or screen is off
- Polls EUC World API at configured interval (default 500ms)

**Service Notification:**
- Displays current connection status
- Shows basic metrics (battery, speed) in notification
- Can be minimized via Settings > Minimize Notification
- Tap notification to open main app

**Service Management:**
- Service starts automatically when app launches
- Continues running until app is force-stopped
- Can be set to auto-start on boot in Settings
- Distributes data to OsmAnd, Android Auto, and widgets

#### Settings Configuration

**Access Settings:**
1. Open the app
2. Tap gear icon (⚙️) in top-right corner
3. Modify desired settings
4. Changes apply immediately

**Key Settings:**
- **API Connection** - Configure host/port to match EUC World
- **Update Interval** - Balance responsiveness vs. battery (100-2000ms)
- **Units** - Choose metric (km/h, °C) or imperial (mph, °F)
- **Auto-start** - Launch service automatically on device boot
- **Notification** - Minimize or show full notification

### Android Auto Interface

The Android Auto interface provides a driver-focused, distraction-free view of your EUC metrics on your car's display.

#### Connecting to Android Auto

**Wired Connection:**
1. Connect phone to car via USB cable
2. Android Auto should launch automatically on car display
3. If not, tap Android Auto icon on car's home screen
4. Navigate to app launcher/grid on car display
5. Find and tap "EUC World" app icon

**Wireless Android Auto:**
1. Ensure phone and car are paired via Bluetooth
2. Enable WiFi on phone (required for wireless Android Auto)
3. Android Auto connects automatically when in car
4. Access app launcher on car display
5. Launch "EUC World" from available apps

**Prerequisites:**
- Background service must be running on phone
- EUC World app running on phone with webservice enabled
- Wheel connected to EUC World with active data
- Phone not in aggressive battery saving mode

#### Android Auto Display

**Screen Layout:**

*Primary Metrics (Large Display):*
- **Battery Status** - Prominent percentage and voltage at top center
- **Speed** - Large current speed display in center
- **Wheel Model** - Shows connected wheel name when available

*Secondary Metrics (Smaller Display):*
- **Temperature** - Motor/battery temperature reading
- **Power** - Current power consumption
- **Load** - Wheel load percentage
- **Connection Status** - Visual indicator of data connection

*Trip Information:*
- Displays current values for Trip A, B, and C
- Shows distances in preferred units (km or miles)
- Updates automatically with configured interval

**Display Characteristics:**
- **High Contrast** - Optimized for daytime and nighttime visibility
- **Large Fonts** - Easy to read with quick glances
- **Minimal Interaction** - Read-only display, no controls to distract
- **Auto-Refresh** - Updates based on phone app's update interval
- **Dark Theme** - Reduces glare for night driving

#### Using Android Auto Safely

**Best Practices:**
- **Position Display** - Ensure car screen is within safe glance zone
- **Quick Glances** - Designed for 1-2 second glances, not prolonged viewing
- **Pre-Ride Setup** - Verify connection before driving
- **Use Voice Commands** - Rely on car's voice controls for navigation
- **Hands-Free Operation** - No need to touch phone while connected

**Recommended Usage:**
- Monitor battery level during commute
- Track trip distances automatically
- Check temperature on long rides
- Verify wheel connection before departure
- Use trip meters to track daily/weekly commute distances

#### Android Auto Troubleshooting

**No Data Displaying:**
1. Check phone app shows "Connected" status
2. Verify EUC World webservice is running
3. Ensure wheel is connected via Bluetooth to EUC World
4. Check phone isn't in battery saver mode killing service

**App Not Appearing in Car:**
1. Verify Android Auto works with other apps
2. Update Android Auto app on phone to latest version
3. Check app permissions allow Android Auto access
4. Reconnect phone to car (unplug/replug or disconnect wireless)

**Display Frozen or Not Updating:**
1. Check update interval in phone app settings
2. Verify background service is running (check notification)
3. Disconnect and reconnect Android Auto
4. Restart phone app before connecting to car

**Connection Drops:**
1. For wired: Try different USB cable or port
2. For wireless: Ensure WiFi stays enabled on phone
3. Check phone stays connected to car Bluetooth
4. Disable battery optimization for this app on phone

### OsmAnd Map Widget Usage

**Enabling Widgets:**
1. Open OsmAnd app
2. Menu (☰) > Configure screen
3. Scroll to find EUC widgets (under "External plugins")
4. Tap to enable desired widgets
5. Choose left or right panel positioning

**Available Widgets:**
- **Battery & Voltage** - Combined: "85% 84.2V"
- **Battery %** - Percentage only: "85%"
- **Voltage** - Voltage only: "84.2V"
- **Trip A/B/C** - Individual trip distances

**Widget Positioning:**
- Drag widgets to reorder on panel
- Place most important metrics in primary view
- Consider visibility while riding
- Widgets update in real-time during navigation

**Best Practices:**
- Don't overcrowd with all widgets - choose essential ones
- Battery & Voltage combined widget saves space
- Use trip widgets for commute tracking with navigation
- Position critical data where glanceable while riding

### Home Screen Widget

**Adding Widget:**
1. Long-press on home screen
2. Select "Widgets" from menu
3. Find "EUC Battery Status"
4. Drag to desired location on home screen
5. Resize if needed (varies by launcher)

**Widget Display:**
- Battery percentage
- Current voltage
- Current speed
- Updates when service is running

**Use Cases:**
- Quick status check without opening app
- Monitor battery before ride
- Check if service is running
- At-a-glance speed verification

## Settings

The app provides comprehensive configuration options accessible via the settings menu (gear icon):

| Setting | Description | Default | Notes |
|---------|-------------|---------|-------|
| **API Host** | EUC World webservice address | http://localhost | Use localhost for same device |
| **API Port** | EUC World webservice port | 8080 | Must match EUC World settings |
| **Update Interval** | Data refresh rate (milliseconds) | 500 | Range: 100-2000ms; lower = more responsive but more battery |
| **Auto-start on Boot** | Start service when device boots | Off | Useful for automatic monitoring |
| **Minimize Notification** | Show minimal service notification | Off | Reduces notification prominence |
| **Use Metric Units** | Display units preference | On | On: km/h & °C, Off: mph & °F |

**Adjusting Settings:**
1. Open the app
2. Tap the gear icon in the top-right corner
3. Modify desired settings
4. Changes apply immediately to all interfaces (OsmAnd, Android Auto, widgets)

## Architecture

```
+-------------------+     +------------------+     +-------------+
|    EUC World      |     |   This Plugin    |     |   OsmAnd    |
|    App            | --> |   EucWorldService| --> |   App       |
|                   |     |   + API Client   |     |             |
| localhost:8080    |     |   + Trip Meters  |     |   Widgets   |
+-------------------+     +--------+---------+     +-------------+
                                  |
                                  | Broadcast IPC & Shared Data
                                  |
                    +-------------+-------------+
                    |                           |
                    v                           v
      +---------------------------+   +-------------------+
      |   Android Auto Head Unit  |   | Home Screen       |
      |   EucWorldCarAppService   |   | Widget            |
      |   EucWorldScreen          |   | EucWidgetProvider |
      +---------------------------+   +-------------------+
```

### Data Flow
1. **EUC Connection** - EUC World connects to your wheel via Bluetooth
2. **API Exposure** - EUC World exposes real-time data via internal webservice (localhost:8080)
3. **Service Polling** - This plugin's background service polls the API at configured intervals
4. **Trip Calculation** - Service manages three independent trip meters using wheel odometer data
5. **Data Distribution** - Service distributes data to multiple interfaces:
   - **OsmAnd** - Via AIDL (Android Interface Definition Language) for map widgets
   - **Android Auto** - Via CarAppService framework for automotive display
   - **Home Widgets** - Via broadcast updates for home screen widgets
   - **Main App** - Direct updates to UI when app is in foreground

## EUC World API

This plugin connects to EUC World's internal webservice:

- **Endpoint**: `http://127.0.0.1:8080/api/values`
- **Format**: JSON object with key-value pairs
- **Update Rate**: Configurable (default 500ms)

### API Response Fields

| Key | Description |
|-----|-------------|
| vba | Battery percentage |
| vvo | Battery voltage |
| vsp | Speed |
| vte | Temperature |
| vdv | Wheel trip distance |
| vdt | Total odometer |
| vmmo | Wheel model name |

## Supported Wheels

All wheels supported by EUC World:
- InMotion (V5, V8, V10, V11, V12, V13, etc.)
- King Song (14S, 16S, 16X, 18L, 18XL, S18, S22, etc.)
- Begode/Gotway (MCM5, Tesla, Nikola, Monster, EX, RS, etc.)
- Veteran (Sherman, Sherman Max, Abrams, etc.)
- Ninebot (One, S2, Z series, etc.)
- Leaperkim (Veteran brand wheels)

## Troubleshooting

### Connection Issues

**Plugin shows "Disconnected"**
- Ensure EUC World app is running in the background
- Verify Internal Webservice is enabled in EUC World (Settings > Internal Webservice)
- Check that API host/port in plugin settings match EUC World configuration
- Confirm your EUC wheel is connected to EUC World via Bluetooth
- Try restarting both EUC World and this plugin

**EUC World webservice not accessible**
- Default endpoint is `http://127.0.0.1:8080/api/values`
- Test in browser: navigate to endpoint to see JSON data
- Check EUC World didn't change the port number
- Ensure no firewall/security app is blocking localhost connections

### OsmAnd Widget Issues

**Widgets show "--" or no data**
- Plugin service must be running (check for notification)
- EUC World must be connected to your wheel with data flowing
- Verify widgets are enabled in OsmAnd Configure Screen
- Try restarting both OsmAnd and this plugin
- Check that plugin shows "Connected" status

**Widgets not appearing in OsmAnd**
- OsmAnd must be version 4.0+ with AIDL plugin support
- Go to OsmAnd > Menu > Configure screen
- Scroll to find widgets under "External plugins" or "Other" section
- Enable desired EUC widgets
- Widgets may take a few seconds to appear after enabling

**Widgets not updating**
- Confirm update interval in settings (default 500ms)
- Check plugin notification shows recent data
- Restart OsmAnd to refresh AIDL connection
- Verify background service is running and not battery-optimized away

### Android Auto Issues

**Android Auto not showing EUC World app**
- Ensure phone is properly connected to Android Auto (check other apps work)
- Verify app permissions allow Android Auto integration
- Disconnect and reconnect phone to car
- Check that Android Auto is updated to latest version on phone

**Android Auto shows no data or "Disconnected"**
- Phone background service must be running (check notification)
- Ensure EUC World webservice is running on phone
- Verify wheel is connected to EUC World with active data
- Check phone's battery saver isn't killing background service
- Restart the app on phone before connecting to Android Auto

**Android Auto display frozen or not updating**
- Verify update interval isn't set too high (try 500ms)
- Disconnect and reconnect Android Auto
- Force stop and restart the app on phone
- Check phone hasn't entered aggressive battery saving mode

### Trip Meter Issues

**Trip meters showing wrong values**
- Trip meters use wheel's total odometer (vdt), not wheel trip (vdv)
- Reset trip to start fresh from current odometer reading
- If wheel disconnects, trip values pause until reconnection resumes
- Ensure EUC World is receiving accurate odometer data from wheel

**Trip meters not persisting**
- Check app has storage permissions
- Verify app isn't being force-closed by system memory management
- Try manually resetting trip and riding to test persistence
- Check app data isn't being cleared by cleaning apps

**Trip meters reset unexpectedly**
- Ensure you're not accidentally tapping reset buttons
- Verify app data isn't being cleared by system or cleaning apps
- Check for app crashes in system logs
- Update to latest version if available

### Performance Issues

**High battery drain**
- Reduce update interval (increase value, e.g., 1000ms instead of 500ms)
- Enable "Minimize notification" to reduce notification updates
- Disable auto-start on boot if constant monitoring isn't needed
- Check battery optimization settings aren't conflicting

**Delayed or laggy updates**
- Decrease update interval (lower value, e.g., 250ms instead of 500ms)
- Ensure phone isn't in power saving mode
- Check EUC World isn't experiencing Bluetooth connection issues
- Verify phone has adequate free memory

### General Troubleshooting Steps

1. **Restart sequence**: Restart EUC World > Restart this plugin > Reconnect wheel
2. **Check permissions**: Verify all requested permissions are granted
3. **Clear cache**: Go to Android Settings > Apps > EUC OsmAnd Plugin > Clear Cache
4. **Reinstall**: Uninstall and reinstall the app (trip data will be lost)
5. **Check logs**: Enable developer options and check logcat for error messages

## Permissions

The app requires the following permissions to function properly:

| Permission | Purpose | Required |
|------------|---------|----------|
| **INTERNET** | Connect to EUC World API on localhost:8080 | Yes |
| **FOREGROUND_SERVICE** | Keep background polling service running | Yes |
| **POST_NOTIFICATIONS** | Show service status notification (Android 13+) | Yes |
| **RECEIVE_BOOT_COMPLETED** | Auto-start service on device boot (if enabled) | Optional |

**Privacy & Security:**
- All data access is local (localhost only)
- No data is sent to external servers
- No analytics or tracking
- No internet access beyond localhost
- All data stays on your device

## Version History

See [CHANGELOG.md](CHANGELOG.md) for version history.

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Credits

- [EUC World](https://euc.world) - The EUC companion app providing the data API
- [OsmAnd](https://osmand.net) - Open-source navigation app with AIDL plugin support

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## Support

For issues and feature requests, please use the GitHub Issues page.
