# EUC OsmAnd Plugin

An Android app that displays Electric Unicycle (EUC) metrics from the EUC World app as widgets on OsmAnd maps, with Android Auto support and home screen widgets.

## Features

### OsmAnd Map Widgets
- **EUC Battery & Voltage** - Combined display showing battery % and voltage
- **EUC Battery %** - Battery percentage only
- **EUC Voltage** - Voltage reading only
- **EUC Trip A/B/C** - Three independent app-managed trip meters

### Android Auto
- Full Android Auto car display with real-time EUC metrics
- Large, easy-to-read HUD-style display
- Shows battery, voltage, speed, and temperature

### Home Screen Widget
- Compact widget showing battery percentage, voltage, and speed
- Updates in real-time when service is running

### App Trip Meters
- Three independent trip meters (A, B, C) managed by the app
- Reset individually or clear all at once
- Persists across app restarts
- Uses wheel odometer for accurate distance tracking

### Main App Display
- HUD-style battery and voltage display
- Real-time speed, temperature, power, and load
- Wheel model identification
- Connection status indicator

## Requirements

- Android 8.0 (API 26) or higher
- [EUC World](https://euc.world) app installed and running
- [OsmAnd](https://osmand.net) app (for map widgets)
- Electric Unicycle connected to EUC World

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

## Settings

| Setting | Description | Default |
|---------|-------------|---------|
| API Host | EUC World webservice address | http://localhost |
| API Port | EUC World webservice port | 8080 |
| Update Interval | Data refresh rate (ms) | 500 |
| Auto-start on boot | Start service when device boots | Off |
| Minimize notification | Show minimal service notification | Off |
| Use Metric Units | km/h and Celsius vs mph and Fahrenheit | On |

## Architecture

```
+-------------------+     +------------------+     +-------------+
|    EUC World      |     |   This Plugin    |     |   OsmAnd    |
|    App            | --> |   EucWorldService| --> |   App       |
|                   |     |   + API Client   |     |             |
| localhost:8080    |     |                  |     |   Widgets   |
+-------------------+     +--------+---------+     +-------------+
                                  |
                                  | Broadcast IPC
                                  v
                    +---------------------------+
                    |   Android Auto Head Unit  |
                    |   EucWorldScreen          |
                    +---------------------------+
```

### Data Flow
1. EUC World connects to your wheel via Bluetooth
2. EUC World exposes data via internal webservice API
3. This plugin polls the API and distributes data to:
   - OsmAnd via AIDL (Android Interface Definition Language)
   - Android Auto via broadcast IPC
   - Home screen widgets via broadcast updates

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

### Plugin shows "Disconnected"
- Ensure EUC World app is running
- Check that Internal Webservice is enabled in EUC World settings
- Verify the API host/port in plugin settings match EUC World

### OsmAnd widgets show "--"
- Plugin service must be running (check notification)
- EUC World must be connected to your wheel
- Try restarting both apps

### OsmAnd widgets not appearing
- OsmAnd must be version 4.0+ with AIDL support
- Go to OsmAnd > Configure screen to enable widgets
- Widgets appear under external plugins section

### Android Auto not showing data
- Phone service must be running
- Ensure EUC World is running on the phone
- Android Auto cannot access localhost directly - it relies on the phone service

### Trip meters showing wrong values
- Trip meters use the wheel's odometer (vdt), not wheel trip
- Reset trip to start fresh from current odometer reading
- If wheel disconnects, trip values pause until reconnection

## Permissions

| Permission | Purpose |
|------------|---------|
| INTERNET | Connect to EUC World API on localhost |
| FOREGROUND_SERVICE | Keep polling service running |
| POST_NOTIFICATIONS | Show status notification |
| RECEIVE_BOOT_COMPLETED | Auto-start on boot (optional) |

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
