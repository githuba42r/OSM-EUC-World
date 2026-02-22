package com.a42r.eucosmandplugin.range.database

/**
 * Comprehensive database of EUC wheel models with battery specifications.
 * 
 * This database enables automatic configuration of range estimation based on
 * the connected wheel model detected from EUC World.
 * 
 * Battery configurations:
 * - 16S = 67.2V nominal (16 × 4.2V cells in series)
 * - 20S = 84.0V nominal (20 × 4.2V cells in series)
 * - 24S = 100.8V nominal (24 × 4.2V cells in series)
 * - 30S = 126.0V nominal (30 × 4.2V cells in series)
 * - 36S = 151.2V nominal (36 × 4.2V cells in series)
 * - 40S = 168.0V nominal (40 × 4.2V cells in series)
 */
object WheelDatabase {
    
    /**
     * Battery configuration for a wheel.
     * 
     * @param cellCount Number of cells in series (16S, 20S, 24S, 30S, 36S, 40S)
     * @param capacityWh Battery capacity in Watt-hours
     * @param nominalVoltage Nominal voltage (cellCount × 4.2V)
     * @param parallelPacks Number of parallel packs (P configuration)
     */
    data class BatteryConfig(
        val cellCount: Int,
        val capacityWh: Double,
        val nominalVoltage: Double,
        val parallelPacks: Int = 1
    ) {
        /**
         * Get battery configuration string (e.g., "20S2P", "24S4P")
         */
        fun getConfigString(): String {
            return "${cellCount}S${parallelPacks}P"
        }
        
        /**
         * Get voltage range (min to max)
         */
        fun getVoltageRange(): Pair<Double, Double> {
            return Pair(
                cellCount * 3.0,  // Min voltage (3.0V per cell)
                cellCount * 4.2   // Max voltage (4.2V per cell)
            )
        }
    }
    
    /**
     * Wheel specification entry.
     * 
     * @param displayName Human-readable wheel name
     * @param modelIdentifiers List of possible model strings from EUC World API
     * @param manufacturer Wheel manufacturer (InMotion, KingSong, Gotway, etc.)
     * @param batteryConfig Battery configuration
     * @param releaseYear Year the model was released
     */
    data class WheelSpec(
        val displayName: String,
        val modelIdentifiers: List<String>,
        val manufacturer: String,
        val batteryConfig: BatteryConfig,
        val releaseYear: Int
    )
    
    /**
     * All known wheel specifications.
     * Organized by manufacturer for easier maintenance.
     */
    private val wheelSpecs = listOf(
        // ===== BEGODE (Gotway) =====
        
        // Master Series (40S - 168V)
        WheelSpec(
            displayName = "Begode Master",
            modelIdentifiers = listOf("Master", "Begode Master"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 40,
                capacityWh = 3600.0,
                nominalVoltage = 168.0,
                parallelPacks = 1
            ),
            releaseYear = 2024
        ),
        WheelSpec(
            displayName = "Begode Master Pro",
            modelIdentifiers = listOf("Master Pro", "Begode Master Pro"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 40,
                capacityWh = 4320.0,
                nominalVoltage = 168.0,
                parallelPacks = 1
            ),
            releaseYear = 2024
        ),
        
        // EX Series (36S - 151.2V)
        WheelSpec(
            displayName = "Begode EX.N",
            modelIdentifiers = listOf("EX.N", "EXN", "Begode EX.N", "Begode EXN"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 36,
                capacityWh = 2700.0,
                nominalVoltage = 151.2,
                parallelPacks = 1
            ),
            releaseYear = 2023
        ),
        WheelSpec(
            displayName = "Begode EX.N HS",
            modelIdentifiers = listOf("EX.N HS", "EXNHS", "Begode EX.N HS"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 36,
                capacityWh = 3240.0,
                nominalVoltage = 151.2,
                parallelPacks = 1
            ),
            releaseYear = 2023
        ),
        WheelSpec(
            displayName = "Begode EX2",
            modelIdentifiers = listOf("EX2", "Begode EX2"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 36,
                capacityWh = 2700.0,
                nominalVoltage = 151.2,
                parallelPacks = 1
            ),
            releaseYear = 2024
        ),
        
        // Extreme Series (24S/30S)
        WheelSpec(
            displayName = "Begode Extreme",
            modelIdentifiers = listOf("Extreme", "Begode Extreme"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 2700.0,
                nominalVoltage = 100.8,
                parallelPacks = 2
            ),
            releaseYear = 2022
        ),
        
        // T Series (24S)
        WheelSpec(
            displayName = "Begode T4",
            modelIdentifiers = listOf("T4", "Begode T4"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 3600.0,
                nominalVoltage = 100.8,
                parallelPacks = 2
            ),
            releaseYear = 2023
        ),
        
        // Hero Series (24S)
        WheelSpec(
            displayName = "Begode Hero",
            modelIdentifiers = listOf("Hero", "Begode Hero"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 3600.0,
                nominalVoltage = 100.8,
                parallelPacks = 2
            ),
            releaseYear = 2022
        ),
        
        // RS Series (24S)
        WheelSpec(
            displayName = "Begode RS19",
            modelIdentifiers = listOf("RS19", "RS-19", "Begode RS19"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 1800.0,
                nominalVoltage = 100.8,
                parallelPacks = 1
            ),
            releaseYear = 2021
        ),
        WheelSpec(
            displayName = "Begode RS19 HS",
            modelIdentifiers = listOf("RS19 HS", "RS-19 HS", "Begode RS19 HS"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 2700.0,
                nominalVoltage = 100.8,
                parallelPacks = 1
            ),
            releaseYear = 2021
        ),
        
        // MCM Series (20S)
        WheelSpec(
            displayName = "Begode MCM5",
            modelIdentifiers = listOf("MCM5", "Begode MCM5"),
            manufacturer = "Begode",
            batteryConfig = BatteryConfig(
                cellCount = 20,
                capacityWh = 1800.0,
                nominalVoltage = 84.0,
                parallelPacks = 2
            ),
            releaseYear = 2020
        ),
        
        // ===== INMOTION =====
        
        // V Series (30S - 126V)
        WheelSpec(
            displayName = "InMotion V14",
            modelIdentifiers = listOf("V14", "InMotion V14"),
            manufacturer = "InMotion",
            batteryConfig = BatteryConfig(
                cellCount = 30,
                capacityWh = 3024.0,
                nominalVoltage = 126.0,
                parallelPacks = 1
            ),
            releaseYear = 2024
        ),
        
        // V Series (24S)
        WheelSpec(
            displayName = "InMotion V13",
            modelIdentifiers = listOf("V13", "InMotion V13"),
            manufacturer = "InMotion",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 2700.0,
                nominalVoltage = 100.8,
                parallelPacks = 2
            ),
            releaseYear = 2023
        ),
        WheelSpec(
            displayName = "InMotion V12",
            modelIdentifiers = listOf("V12", "InMotion V12"),
            manufacturer = "InMotion",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 1750.0,
                nominalVoltage = 100.8,
                parallelPacks = 1
            ),
            releaseYear = 2021
        ),
        WheelSpec(
            displayName = "InMotion V12 HT",
            modelIdentifiers = listOf("V12 HT", "V12HT", "InMotion V12 HT"),
            manufacturer = "InMotion",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 2400.0,
                nominalVoltage = 100.8,
                parallelPacks = 1
            ),
            releaseYear = 2022
        ),
        WheelSpec(
            displayName = "InMotion V11",
            modelIdentifiers = listOf("V11", "InMotion V11"),
            manufacturer = "InMotion",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 1500.0,
                nominalVoltage = 100.8,
                parallelPacks = 1
            ),
            releaseYear = 2020
        ),
        
        // V Series (20S)
        WheelSpec(
            displayName = "InMotion V10F",
            modelIdentifiers = listOf("V10F", "InMotion V10F"),
            manufacturer = "InMotion",
            batteryConfig = BatteryConfig(
                cellCount = 20,
                capacityWh = 960.0,
                nominalVoltage = 84.0,
                parallelPacks = 1
            ),
            releaseYear = 2018
        ),
        WheelSpec(
            displayName = "InMotion V8",
            modelIdentifiers = listOf("V8", "InMotion V8"),
            manufacturer = "InMotion",
            batteryConfig = BatteryConfig(
                cellCount = 16,
                capacityWh = 480.0,
                nominalVoltage = 67.2,
                parallelPacks = 1
            ),
            releaseYear = 2017
        ),
        
        // ===== KINGSONG =====
        
        // S Series (30S - 126V)
        WheelSpec(
            displayName = "KingSong S22 Pro",
            modelIdentifiers = listOf("S22 Pro", "S22Pro", "KingSong S22 Pro"),
            manufacturer = "KingSong",
            batteryConfig = BatteryConfig(
                cellCount = 30,
                capacityWh = 3024.0,
                nominalVoltage = 126.0,
                parallelPacks = 1
            ),
            releaseYear = 2024
        ),
        WheelSpec(
            displayName = "KingSong S22",
            modelIdentifiers = listOf("S22", "KingSong S22"),
            manufacturer = "KingSong",
            batteryConfig = BatteryConfig(
                cellCount = 30,
                capacityWh = 2520.0,
                nominalVoltage = 126.0,
                parallelPacks = 1
            ),
            releaseYear = 2022
        ),
        
        // S Series (24S)
        WheelSpec(
            displayName = "KingSong S20",
            modelIdentifiers = listOf("S20", "KingSong S20"),
            manufacturer = "KingSong",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 2400.0,
                nominalVoltage = 100.8,
                parallelPacks = 1
            ),
            releaseYear = 2023
        ),
        WheelSpec(
            displayName = "KingSong S18",
            modelIdentifiers = listOf("S18", "KingSong S18"),
            manufacturer = "KingSong",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 1110.0,
                nominalVoltage = 100.8,
                parallelPacks = 1
            ),
            releaseYear = 2020
        ),
        
        // 16 Series (20S)
        WheelSpec(
            displayName = "KingSong 16X",
            modelIdentifiers = listOf("16X", "KS-16X", "KingSong 16X"),
            manufacturer = "KingSong",
            batteryConfig = BatteryConfig(
                cellCount = 20,
                capacityWh = 1554.0,
                nominalVoltage = 84.0,
                parallelPacks = 1
            ),
            releaseYear = 2019
        ),
        WheelSpec(
            displayName = "KingSong 16S",
            modelIdentifiers = listOf("16S", "KS-16S", "KingSong 16S"),
            manufacturer = "KingSong",
            batteryConfig = BatteryConfig(
                cellCount = 20,
                capacityWh = 840.0,
                nominalVoltage = 84.0,
                parallelPacks = 1
            ),
            releaseYear = 2017
        ),
        
        // 14 Series (16S)
        WheelSpec(
            displayName = "KingSong 14D",
            modelIdentifiers = listOf("14D", "KS-14D", "KingSong 14D"),
            manufacturer = "KingSong",
            batteryConfig = BatteryConfig(
                cellCount = 16,
                capacityWh = 420.0,
                nominalVoltage = 67.2,
                parallelPacks = 1
            ),
            releaseYear = 2016
        ),
        
        // ===== VETERAN =====
        
        // Sherman Series (30S)
        WheelSpec(
            displayName = "Veteran Sherman Max",
            modelIdentifiers = listOf("Sherman Max", "Sherman-Max", "Veteran Sherman Max"),
            manufacturer = "Veteran",
            batteryConfig = BatteryConfig(
                cellCount = 30,
                capacityWh = 3600.0,
                nominalVoltage = 126.0,
                parallelPacks = 1
            ),
            releaseYear = 2023
        ),
        WheelSpec(
            displayName = "Veteran Sherman",
            modelIdentifiers = listOf("Sherman", "Veteran Sherman"),
            manufacturer = "Veteran",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 3200.0,
                nominalVoltage = 100.8,
                parallelPacks = 2
            ),
            releaseYear = 2021
        ),
        
        // Patton Series (24S)
        WheelSpec(
            displayName = "Veteran Patton",
            modelIdentifiers = listOf("Patton", "Veteran Patton"),
            manufacturer = "Veteran",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 1800.0,
                nominalVoltage = 100.8,
                parallelPacks = 1
            ),
            releaseYear = 2023
        ),
        
        // Abrams Series (24S)
        WheelSpec(
            displayName = "Veteran Abrams",
            modelIdentifiers = listOf("Abrams", "Veteran Abrams"),
            manufacturer = "Veteran",
            batteryConfig = BatteryConfig(
                cellCount = 24,
                capacityWh = 2700.0,
                nominalVoltage = 100.8,
                parallelPacks = 2
            ),
            releaseYear = 2022
        ),
        
        // ===== LEAPERKIM =====
        
        WheelSpec(
            displayName = "Leaperkim Lynx",
            modelIdentifiers = listOf("Lynx", "Leaperkim Lynx"),
            manufacturer = "Leaperkim",
            batteryConfig = BatteryConfig(
                cellCount = 30,
                capacityWh = 2700.0,
                nominalVoltage = 126.0,
                parallelPacks = 1
            ),
            releaseYear = 2023
        )
    )
    
    /**
     * Find wheel specification by model identifier.
     * 
     * Performs case-insensitive matching against all known model identifiers.
     * 
     * @param modelIdentifier Model string from EUC World API (e.g., "V13", "Sherman Max")
     * @return WheelSpec if found, null otherwise
     */
    fun findWheelSpec(modelIdentifier: String): WheelSpec? {
        if (modelIdentifier.isBlank()) return null
        
        val normalized = modelIdentifier.trim()
        
        return wheelSpecs.firstOrNull { spec ->
            spec.modelIdentifiers.any { identifier ->
                identifier.equals(normalized, ignoreCase = true)
            }
        }
    }
    
    /**
     * Get all wheel specifications, optionally filtered by manufacturer.
     * 
     * @param manufacturer Filter by manufacturer (null for all)
     * @return List of wheel specs
     */
    fun getAllWheelSpecs(manufacturer: String? = null): List<WheelSpec> {
        return if (manufacturer != null) {
            wheelSpecs.filter { it.manufacturer.equals(manufacturer, ignoreCase = true) }
        } else {
            wheelSpecs
        }
    }
    
    /**
     * Get list of all manufacturers.
     */
    fun getAllManufacturers(): List<String> {
        return wheelSpecs.map { it.manufacturer }.distinct().sorted()
    }
    
    /**
     * Get list of all supported cell counts.
     */
    fun getSupportedCellCounts(): List<Int> {
        return listOf(16, 20, 24, 30, 36, 40)
    }
    
    /**
     * Create a custom wheel specification.
     * 
     * @param displayName Custom wheel name
     * @param cellCount Number of cells in series
     * @param capacityWh Battery capacity in Wh
     * @return Custom WheelSpec
     */
    fun createCustomSpec(
        displayName: String,
        cellCount: Int,
        capacityWh: Double,
        parallelPacks: Int = 1
    ): WheelSpec {
        return WheelSpec(
            displayName = displayName,
            modelIdentifiers = listOf(displayName),
            manufacturer = "Custom",
            batteryConfig = BatteryConfig(
                cellCount = cellCount,
                capacityWh = capacityWh,
                nominalVoltage = cellCount * 4.2,
                parallelPacks = parallelPacks
            ),
            releaseYear = 0
        )
    }
}
