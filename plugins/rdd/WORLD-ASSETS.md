# RDD world assets

RDD keeps reusable world knowledge separately from a task chain. Clearing or
replacing a task must not erase a known base, structure, workstation, portal,
or notable entity sighting.

## Data flow

```text
loaded companion surroundings + loaded respawn-base area
  -> RddWorldAssetObserver (30-second lazy observation, no chunk force-load)
  -> AssetRegistry
  -> config/numen/rdd-assets/<companion-uuid>.json
  -> Numen <known_world_assets> context
  -> Supervisor Stage-A / Stage-B planning context
  -> rdd_assets read-only tool
  -> asset_snapshot monitor event
  -> monitoring station
```

The same registry feeds every consumer. The monitor therefore exposes the
actual source and consumer names rather than reconstructing a second asset
state.

## Evidence boundaries

- A respawn bed creates a base asset. Chest plus furnace is still required for
  the existing `base` completion condition.
- Structures are recorded only while the companion is physically inside one.
  A locate result is not arrival evidence.
- Workstations and machines are scanned only in the companion's loaded nearby
  area. Distant chunks are never loaded for observation.
- Entity sightings use `refresh_policy=LAZY`; entities may move or die and must
  be checked again before use.
- Inventory observations remain task/runtime evidence and are not written to
  the long-lived world-asset file.
- The registry retains at most 128 world assets and 512 observation-history
  entries. Model context has a separate character budget.

## Verification

Run with JDK 21:

```powershell
.\gradlew.bat :rdd-core:test :plugins:rdd:test :api:common:compileClientJava --no-build-cache --no-daemon
```

In a loaded test world, verify all of the following before promotion:

1. `rdd_assets` appears in the MCP tool catalog and returns the same assets as
   `config/numen/rdd-assets/<uuid>.json`.
2. `asset_snapshot` contains `source`, `registry`, `consumers`, and `refresh`.
3. A Numen request includes `<known_world_assets>`.
4. A Supervisor planning request includes the same asset summary.
5. Replacing or clearing the task chain does not remove the asset file.
