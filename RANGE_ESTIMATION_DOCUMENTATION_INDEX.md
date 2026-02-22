# Battery Range Estimation - Documentation Index

**Project:** OSM EUC World - Android Companion App  
**Feature:** Adaptive Battery Range Estimation with Voltage Sag Compensation  
**Last Updated:** February 22, 2026

---

## Documentation Overview

This index provides a roadmap to all documentation created for the battery range estimation feature, with special emphasis on voltage sag compensation to ensure accurate estimates.

---

## Core Implementation Documents

### 📘 [RANGE_ESTIMATION_IMPLEMENTATION.md](./RANGE_ESTIMATION_IMPLEMENTATION.md) (115 KB)
**The complete technical specification and implementation blueprint.**

**⚠️ RECENTLY UPDATED:** Section 9 (Edge Cases & Error Handling) has been updated to reflect implementation decisions. See [DOCUMENTATION_UPDATES.md](./DOCUMENTATION_UPDATES.md) for details.

**Contents:**
- Executive Summary & Success Metrics
- Feature Requirements (Functional & Non-Functional)
- System Architecture & Data Flow Diagrams
- 9 Implementation Phases with Sprint Breakdown
- Complete Data Models (Kotlin code)
- 4 Algorithm Specifications:
  - Li-Ion Discharge Curve Model
  - **Voltage Sag Compensation (Strategy 2: Power-Weighted Smoothing)** ← UPDATED
  - Simple Linear Estimator
  - Weighted Window Estimator (Recommended)
  - ML-Lite Estimator (Optional)
- UI/UX Specifications (Compose code)
- Settings & Configuration (XML)
- **Edge Cases & Error Handling** ← UPDATED (Feb 22, 2026)
- Testing Strategy (Unit, Integration, Real-World)
- Dependencies & Build Configuration
- Timeline: 9-10 weeks MVP, 15-18 weeks full feature

**When to use:**
- Primary reference for implementation
- Architecture decisions
- Code templates and examples
- Testing requirements
- Timeline planning

---

### 📝 [DOCUMENTATION_UPDATES.md](./DOCUMENTATION_UPDATES.md) (NEW)
**Summary of changes made to align documentation with implementation decisions.**

**Contents:**
- Changes to Connection Gap handling (linear interpolation)
- Changes to Charging Event handling (continue trip, new baseline)
- Changes to Voltage Sag handling (Strategy 2 selected)
- Changes to Insufficient Data handling (10 min + 10 km)
- Updated model definitions
- Implementation checklist

**When to use:**
- Understanding what changed and why
- Reviewing recent decisions
- Tracking implementation status

---

### 📋 [RANGE_ESTIMATION_IMPLEMENTATION_PLAN.md](./RANGE_ESTIMATION_IMPLEMENTATION_PLAN.md) (NEW)
**Phased implementation plan with file-by-file breakdown.**

**Contents:**
- 6 implementation phases (5 weeks total)
- Phase 1: Core Models & Algorithm (2 weeks)
- Phase 2: Phone App Settings (2-3 days)
- Phase 3: Android Auto Settings Screens (2-3 days)
- Phase 4: Phone App UI Display (2 days)
- Phase 5: Android Auto Display Integration (1 day)
- Phase 6: Integration & Testing (5-7 days)
- Complete file structure tree
- Settings architecture (Phone vs Android Auto)
- Timeline summary

**When to use:**
- Planning development sprints
- Tracking implementation progress
- Understanding what to build next
- Onboarding new developers

---

## Voltage Sag Compensation Deep-Dives

### 📗 [VOLTAGE_SAG_COMPENSATION_SUMMARY.md](./VOLTAGE_SAG_COMPENSATION_SUMMARY.md) (15 KB)
**Executive summary of the voltage sag problem and solution.**

**Contents:**
- Problem Statement (Why voltage sag matters)
- Solution Overview (Detection & Compensation)
- Detection Algorithm (Complete code)
- Modified Data Models
- Integration into Estimators
- Configuration & Tuning
- Testing Strategy
- Expected Accuracy Improvement (5-20%)
- Implementation Checklist

**When to use:**
- Quick reference for voltage sag handling
- Understanding the problem domain
- Design review discussions
- Explaining approach to stakeholders

---

### 📙 [VOLTAGE_SAG_VISUAL_REFERENCE.md](./VOLTAGE_SAG_VISUAL_REFERENCE.md) (10 KB)
**Visual diagrams and examples of voltage sag behavior.**

**Contents:**
- Voltage vs Time graph during typical ride
- Power consumption correlation
- Voltage Sag vs Real Discharge comparison
- Detection Logic Flow diagram
- Sample Filtering Example (tabular)
- Configuration Impact comparison
- Real-World Test Data (simulated)

**When to use:**
- Understanding voltage sag visually
- Explaining concept to non-technical users
- Debugging sag detection issues
- Tuning thresholds

---

### 📕 [SAMPLE_VALIDATION_COMPLETE.md](./SAMPLE_VALIDATION_COMPLETE.md) (25 KB)
**Comprehensive reference for sample validation and filtering logic.**

**Contents:**
- Two-Stage Filtering Overview
- Complete SampleFlag Enum
- 7 Detection Functions (with code):
  1. Time Gap Detection
  2. Distance Anomaly Detection
  3. Charging Event Detection
  4. Voltage Anomaly Detection
  5. **Voltage Sag Detection** (detailed)
  6. Efficiency Outlier Detection
  7. Speed Anomaly Detection
- Comprehensive Sample Validation
- BatterySample Validation Properties
- Usage in Estimators (Energy vs Efficiency)
- Integration in RangeEstimationManager
- 3 Complete Test Cases
- Summary Flow Diagram

**When to use:**
- Implementing sample validation logic
- Understanding filtering differences
- Writing unit tests
- Debugging sample flags

---

## Quick Reference

### Key Concepts

| Concept | Description | Where Documented |
|---------|-------------|------------------|
| **Voltage Sag** | Temporary voltage drop under high load that recovers | All voltage sag docs |
| **Power-Weighted Smoothing** | Compensate for sag using power-aware voltage smoothing (Strategy 2) | RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.4 |
| **Connection Gap Interpolation** | Fill BT disconnections with linear interpolation | RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.1 |
| **Charging Baseline Reset** | Create new baseline after charging, continue trip | RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.2 |
| **Insufficient Data (10+10)** | Require 10 min + 10 km before showing estimate | RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.5 |
| **Li-Ion Discharge Curve** | Non-linear voltage-to-energy conversion | RANGE_ESTIMATION_IMPLEMENTATION.md, Section 6.1 |
| **Weighted Window** | Adaptive algorithm with exponential weighting | RANGE_ESTIMATION_IMPLEMENTATION.md, Section 6.3 |

---

### Critical Code Snippets

#### Voltage Compensation (Power-Weighted Smoothing)
```kotlin
// See: RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.4
// VoltageCompensator.kt (already created)
val compensated = VoltageCompensator.calculateCompensatedVoltage(
    currentVoltage = rawSample.voltage,
    currentPower = rawSample.powerWatts,
    previousCompensatedVoltage = previousCompensatedVoltage
)
```

#### Connection Gap Interpolation
```kotlin
// See: RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.1
fun interpolateGapSamples(
    startSample: BatterySample,
    endSample: BatterySample,
    gapDurationMs: Long
): List<BatterySample>  // Returns interpolated samples with INTERPOLATED flag
```

#### Sample Validation (Single Property)
```kotlin
// See: RANGE_ESTIMATION_IMPLEMENTATION.md, Section 5.2
val isValidForEstimation: Boolean  // Single validation, simpler
// Note: INTERPOLATED samples ARE valid for estimation
```

---

## Implementation Roadmap

### Phase 1: Core Implementation (Weeks 1-3)
**Focus:** Data models, algorithms, voltage sag detection

**Primary References:**
- RANGE_ESTIMATION_IMPLEMENTATION.md, Phases 1-2
- SAMPLE_VALIDATION_COMPLETE.md (for validation logic)
- VOLTAGE_SAG_COMPENSATION_SUMMARY.md (for detection algorithm)

**Deliverables:**
- [x] BatterySample with compensatedVoltage field
- [x] SampleFlag enum with INTERPOLATED flag  
- [x] VoltageCompensator (power-weighted smoothing)
- [ ] Connection gap interpolation logic
- [ ] Charging detection state machine
- [ ] Li-Ion discharge curve model
- [ ] Simple Linear & Weighted Window estimators

---

### Phase 2: Integration & UI (Weeks 4-6)
**Focus:** UI components, settings, display integration

**Primary References:**
- RANGE_ESTIMATION_IMPLEMENTATION.md, Phases 3-4
- (UI/UX sections for Compose code)
- (Settings section for XML)

**Deliverables:**
- [ ] RangeEstimateCard (Phone UI)
- [ ] Android Auto display rows
- [ ] OSMAnd widgets
- [ ] Settings preferences

---

### Phase 3: Testing & Validation (Weeks 7-10)
**Focus:** Real-world testing, voltage sag validation, accuracy tuning

**Primary References:**
- RANGE_ESTIMATION_IMPLEMENTATION.md, Phase 6
- VOLTAGE_SAG_VISUAL_REFERENCE.md (expected behavior)
- SAMPLE_VALIDATION_COMPLETE.md (test cases)

**Deliverables:**
- [ ] Unit tests (80%+ coverage)
- [ ] Integration tests
- [ ] Real-world ride tests (3+ EUC models)
- [ ] Voltage sag threshold tuning

---

## Key Design Decisions

### ✅ Decision 1: Power-Weighted Smoothing for Voltage Sag (Strategy 2)

**Rationale:**
- Smooth, stable estimates during aggressive riding
- No lag - responds immediately to real discharge
- Power-aware - automatically adjusts to riding style
- No sample exclusion - uses all data efficiently
- Self-tuning - works across different wheels

**Alternatives Considered:**
- ❌ Flag and exclude (Strategy 1) - causes data loss, estimate jumps
- ❌ Recent minimum voltage (Strategy 3) - too conservative

**Documented in:** RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.4 + DOCUMENTATION_UPDATES.md

---

### ✅ Decision 2: Linear Interpolation for Connection Gaps

**Rationale:**
- Real-world Bluetooth disconnections are common and long (>5 min)
- Linear interpolation is conservative and more accurate than ignoring data
- User control over trip reset (never automatic)
- Preserves all data for better estimates

**Alternatives Considered:**
- ❌ Flag and exclude gaps - loses valuable data
- ❌ Auto-reset trip after 5 min - too disruptive

**Documented in:** RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.1 + DOCUMENTATION_UPDATES.md

---

### ✅ Decision 3: Continue Trip with New Baseline After Charging

**Rationale:**
- Preserves complete trip history (rode 50km → charged → rode 30km)
- Accurate post-charging estimates using fresh baseline
- No user intervention required
- Can analyze efficiency before/after charging

**Alternatives Considered:**
- ❌ Auto-reset trip - loses trip continuity
- ✅ Option 2 selected (continue with new baseline)

**Documented in:** RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.2 + DOCUMENTATION_UPDATES.md

---

### ✅ Decision 4: Require 10 Minutes + 10 km Before Estimate

**Rationale:**
- 10 minutes ensures battery curve stability
- 10 km ensures meaningful efficiency calculation
- Progress indicator helps user understand requirements
- After charging, requirements restart from new baseline

**Alternatives Considered:**
- ❌ 0.5 km minimum - not enough data for accuracy

**Documented in:** RANGE_ESTIMATION_IMPLEMENTATION.md, Section 9.5 + DOCUMENTATION_UPDATES.md

---

## Testing Checklist

### Unit Tests
- [ ] Voltage sag detection (various power/voltage combinations)
- [ ] Voltage recovery pattern detection
- [ ] Sample validation (all flag types)
- [ ] Li-Ion discharge curve accuracy
- [ ] Energy calculation with sag filtering
- [ ] Efficiency calculation including sag samples
- [ ] Estimator algorithms

**Reference:** SAMPLE_VALIDATION_COMPLETE.md, Section "Testing Sample Validation"

### Integration Tests
- [ ] Full data flow (EucData → RangeEstimate)
- [ ] Trip lifecycle with sag events
- [ ] App restart mid-trip
- [ ] Connection gaps and recovery

**Reference:** RANGE_ESTIMATION_IMPLEMENTATION.md, Section 10.2

### Real-World Tests
- [ ] Gentle cruising (baseline, no sag)
- [ ] Aggressive acceleration (hard sag events)
- [ ] Steep hill climb (sustained sag)
- [ ] Mixed riding (sag and normal)
- [ ] 3+ different EUC models

**Reference:** VOLTAGE_SAG_VISUAL_REFERENCE.md, Section "Real-World Test Data"

---

## Accuracy Targets

| Metric | Target | Without Sag Compensation | With Sag Compensation |
|--------|--------|--------------------------|----------------------|
| **MAPE** | <15% | 15-25% | 8-12% ✓ |
| **Confidence Hit Rate (85%)** | ≥80% | 60-70% | 82-88% ✓ |
| **Estimate Stability** | ±10% variation | ±20-30% | ±5-8% ✓ |

**Reference:** VOLTAGE_SAG_COMPENSATION_SUMMARY.md, Section "Expected Accuracy Improvement"

---

## Configuration Reference

### Default Settings

```kotlin
// Voltage Sag Detection
val voltageSagEnabled: Boolean = true
val voltageSagPowerThreshold: Double = 1500.0  // Watts

// Battery Configuration
val batteryCapacityWh: Double = 2000.0  // Auto-detect in future
val cellCount: Int = 20  // 20S pack

// Algorithm Selection
val defaultAlgorithm: EstimationAlgorithm = WEIGHTED_WINDOW
val defaultPreset: AlgorithmPreset = BALANCED

// Window Configuration (Balanced preset)
val windowMinutes: Int = 30
val weightDecayFactor: Double = 0.5
```

**Reference:** RANGE_ESTIMATION_IMPLEMENTATION.md, Section 8

---

## FAQ

### Q: Why do we need voltage sag compensation?

**A:** Li-Ion batteries exhibit temporary voltage drops under high current draw (acceleration, climbs). Without compensation, these temporary drops are mistaken for real discharge, causing **10-20% underestimation** of remaining range.

**See:** VOLTAGE_SAG_COMPENSATION_SUMMARY.md, Section "Problem Statement"

---

### Q: Why include voltage sag samples in efficiency calculations?

**A:** Power consumption during sag is **real energy usage**. Excluding these samples would make efficiency calculations inaccurate (would show better efficiency than reality).

**See:** SAMPLE_VALIDATION_COMPLETE.md, Section "Usage in Estimators"

---

### Q: How do I tune the voltage sag threshold for different wheels?

**A:** Different EUC models have different power characteristics:
- Small wheels (<16"): 1000W threshold
- Medium wheels (16"-18"): 1500W threshold (default)
- Large wheels (20"+): 2000W threshold
- High-performance wheels: 2500W threshold

**See:** VOLTAGE_SAG_COMPENSATION_SUMMARY.md, Section "Wheel-Specific Tuning"

---

### Q: What if all recent samples are flagged as sag?

**A:** The algorithm falls back to the most recent sample (even if flagged) rather than failing. This prevents null estimates during sustained high power riding (long climbs). The estimate may be slightly pessimistic but still useful.

**See:** SAMPLE_VALIDATION_COMPLETE.md, Section "Energy Calculation"

---

## Next Steps

1. **Review all documentation** (start with this index)
2. **Confirm voltage sag approach** and thresholds
3. **Begin Phase 1 implementation:**
   - Create data models (BatterySample, SampleFlag enum)
   - Implement voltage sag detection
   - Implement Li-Ion discharge curve
   - Build Simple Linear estimator
4. **Write unit tests** as you implement
5. **Integrate into RangeEstimationManager**
6. **Test with real EUC data**

---

## Document Maintenance

When updating documentation:

1. Update the relevant document(s)
2. Update this index if new sections added
3. Update "Last Updated" date in this index
4. Increment version numbers in individual documents
5. Keep code examples synchronized with actual implementation

---

## Contact & Feedback

For questions, clarifications, or suggested improvements to this documentation:

- Open an issue in the project repository
- Tag documentation updates with `[docs]` prefix
- Reference specific document and section in issues

---

**Last Updated:** February 22, 2026  
**Latest Change:** Updated edge case handling strategies (see DOCUMENTATION_UPDATES.md)
**Documentation Version:** 1.0  
**Total Documentation Size:** ~180 KB  
**Estimated Read Time (all docs):** 3-4 hours
