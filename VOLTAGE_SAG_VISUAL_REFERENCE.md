# Voltage Sag Behavior - Visual Reference

## Voltage vs Time During Typical EUC Ride

```
Voltage (V)
   84V ┤
       │
   82V ┤     ●●●●●
       │    ●     ●●●●
   80V ┤   ●           ●●●●●●
       │  ●                  ●●●●●
   78V ┤ ●                        ●●●●●●●●●
       │●                                  ●●●●
   76V ┤                                       ●●●
       │            ╱╲          ╱╲
   74V ┤           ╱  ╲        ╱  ╲              ●●●
       │          ╱    ╲      ╱    ╲            ●
   72V ┤         ╱ SAG  ╲    ╱ SAG  ╲          ●
       │        ╱        ╲  ╱        ╲
   70V ┤_______╱__________╲╱__________╲________●___________
       └────────────────────────────────────────────────────> Time
       │        │         │  │         │        │
       │        │         │  │         │        │
     Start   Accel    Cruise Hill    Cruise  Battery
                      (20km/h) Climb  (25km/h) Depleted

Legend:
  ● = True battery voltage (resting/low-load)
  ╱╲ = Voltage sag (temporary drop under high load)
```

## Power Consumption During Same Ride

```
Power (W)
  3000W ┤         ●                ●
        │        ● ●              ● ●
  2500W ┤       ●   ●            ●   ●
        │      ●     ●          ●     ●
  2000W ┤     ●       ●        ●       ●
        │    ●         ●      ●         ●
  1500W ┤___●___________●____●___________●____________  ← Sag Threshold
        │
  1000W ┤
        │  ●                                       ●
   500W ┤●●   ●●●●●●●●●●  ●●●●●●●●●●●●●●●●●●●●●●●   ●●●
        │
     0W ┤________________________________________________
        └────────────────────────────────────────────────> Time
        │        │         │  │         │        │
      Start   Accel    Cruise Hill    Cruise  Battery
                       (20km/h) Climb  (25km/h) Depleted
```

## Key Observations

1. **Voltage Sag Events:**
   - First sag: Hard acceleration (0 → 20 km/h)
   - Second sag: Hill climb (sustained 2000-3000W)
   - Voltage drops 2-4V during sag
   - **Voltage recovers** when power decreases

2. **Without Compensation:**
   ```
   During acceleration:
     Measured voltage: 72V → Algorithm thinks battery at 45%
     True voltage: 76V → Battery actually at 60%
     
   Result: Range UNDERESTIMATED by 15-20%
   ```

3. **With Compensation:**
   ```
   During acceleration:
     Sample flagged: VOLTAGE_SAG_SUSPECTED
     Energy calculation uses: 76V (previous low-load sample)
     
   Result: Accurate range estimate maintained
   ```

## Voltage Sag vs Real Discharge

### Voltage Sag (Temporary)
```
Time:     0s    1s    2s    3s    4s    5s
Power:   500W  2500W 2500W 2500W  600W  600W
Voltage:  76V   73V   73V   73V   75V   75V
                 ↓     ↓     ↓     ↑     ↑
               SAG   SAG   SAG  RECOVERY
```

### Real Discharge (Permanent)
```
Time:      0min   5min   10min  15min  20min
Power:     800W   800W   800W   800W   800W
Voltage:   80V    78V    76V    74V    72V
           ↓      ↓      ↓      ↓      ↓
         TRUE DISCHARGE (energy consumed)
```

## Detection Logic Flow

```
┌─────────────────────────────────────┐
│  New sample received                │
│  Voltage: 72V, Power: 2200W         │
└──────────────┬──────────────────────┘
               │
               ▼
      ┌────────────────────┐
      │ Power > 1500W?     │
      └────┬──────────┬────┘
           │ NO       │ YES
           ▼          ▼
    ┌──────────┐  ┌──────────────────────┐
    │ No sag   │  │ Voltage drop > 0.05V │
    │ Use as-is│  │ per cell?            │
    └──────────┘  └────┬──────────┬──────┘
                       │ NO       │ YES
                       ▼          ▼
                  ┌────────┐  ┌─────────────────┐
                  │ No sag │  │ Distance < 50m? │
                  └────────┘  └──┬──────────┬───┘
                                 │ NO       │ YES
                                 ▼          ▼
                            ┌────────┐  ┌──────────────┐
                            │ No sag │  │ FLAG: SAG!   │
                            └────────┘  │ Exclude from │
                                        │ SOC calc     │
                                        └──────────────┘
```

## Sample Filtering Example

### Trip with 20 samples over 10 seconds:

```
Sample #  Time   Voltage  Power   Distance  Flags               Use for SOC?  Use for Efficiency?
───────────────────────────────────────────────────────────────────────────────────────────────
1         0.0s   80.0V    600W    0.0km     []                  ✓ YES         ✓ YES
2         0.5s   80.0V    700W    0.01km    []                  ✓ YES         ✓ YES
3         1.0s   79.8V    1200W   0.02km    []                  ✓ YES         ✓ YES
4         1.5s   78.5V    2500W   0.03km    [VOLTAGE_SAG]       ✗ NO          ✓ YES
5         2.0s   77.2V    2800W   0.05km    [VOLTAGE_SAG]       ✗ NO          ✓ YES
6         2.5s   77.0V    2900W   0.07km    [VOLTAGE_SAG]       ✗ NO          ✓ YES
7         3.0s   76.8V    2600W   0.09km    [VOLTAGE_SAG]       ✗ NO          ✓ YES
8         3.5s   78.5V    1500W   0.11km    [VOLTAGE_SAG]       ✗ NO          ✓ YES
9         4.0s   79.2V    900W    0.14km    []                  ✓ YES ←       ✓ YES
10        4.5s   79.2V    850W    0.16km    []                  ✓ YES ← Use   ✓ YES
11        5.0s   79.1V    800W    0.19km    []                  ✓ YES ← this  ✓ YES
12        5.5s   79.1V    750W    0.22km    []                  ✓ YES ← for   ✓ YES
13        6.0s   79.0V    720W    0.25km    []                  ✓ YES ← SOC!  ✓ YES
...
```

**State of Charge Calculation:**
- Uses sample #13: 79.0V (most recent low-load sample)
- Ignores samples #4-8 (flagged as sag)
- Result: Accurate remaining energy estimate

**Efficiency Calculation:**
- Uses ALL samples (#1-13)
- Includes sag samples because power consumption was real
- Result: Accurate Wh/km consumption

## Configuration Impact

### Conservative Settings (High Threshold = 2000W)
```
Fewer samples flagged as sag
  → More stable estimates
  → May underestimate slightly during very aggressive riding
  
Best for: Casual riders, beginners
```

### Balanced Settings (Default = 1500W)
```
Good detection of typical sag events
  → Balanced stability and accuracy
  → Works well for most riding styles
  
Best for: Most users (recommended)
```

### Responsive Settings (Low Threshold = 1000W)
```
More samples flagged as sag
  → Very accurate during aggressive riding
  → May over-compensate during gentle riding
  
Best for: Advanced riders, racing, extreme terrain
```

---

## Real-World Test Data (Simulated)

### Test Ride: 10km mixed terrain

```
Segment          Distance  Avg Power  Voltage Range    Sag Events  Impact without Compensation
──────────────────────────────────────────────────────────────────────────────────────────────
Gentle start     0-2km     600W       80.0-79.5V       0           None
Acceleration     2-3km     2200W      79.5-77.2V       8 samples   -12% range error
Cruising         3-6km     800W       79.0-78.5V       0           None
Hill climb       6-7km     2600W      78.5-75.8V       15 samples  -18% range error
Descent          7-8km     200W       78.2-78.0V       0           None (regen)
Final cruise     8-10km    750W       78.0-77.5V       0           None
──────────────────────────────────────────────────────────────────────────────────────────────
TOTALS           10km      1150W avg  80.0-77.5V       23 samples  Avg -8% error
```

**With voltage sag compensation:**
- 23 samples flagged and excluded from SOC calculation
- Range estimate stable throughout ride (±3% variation)
- Final accuracy: 95% (error <5%)

**Without voltage sag compensation:**
- All samples used for SOC calculation
- Range estimate fluctuates wildly (±18% variation)
- Final accuracy: 82% (error 18%)

---

**References:**
- Main implementation: `RANGE_ESTIMATION_IMPLEMENTATION.md`, Section 6.1b
- Summary document: `VOLTAGE_SAG_COMPENSATION_SUMMARY.md`
