# Spawn Distance Biomes

A Minecraft mod (NeoForge 1.21.1) that controls which biomes can generate based on distance from the world spawn point.

## The Problem

Modpacks can already gate structures, mobs, and loot by biome. But without a way to control *where* biomes appear, you're stuck with artificial difficulty scaling — the same plains biome just has bigger zombie HP numbers when you're far from spawn.

## The Solution

Spawn Distance Biomes operates at **Layer 1** of the worldgen stack — biome placement. By controlling which biomes exist at which distances, every biome-aware mod in your pack automatically inherits a natural difficulty curve without any additional configuration.

```
Distance from spawn:  0 ─────── 512 ─────── 1536 ─────── 3072 ─────── ∞
                      │           │            │            │          │
Allowed biomes:    [Plains,    [+Taiga,     [+Jungle,    [All        [No
                    Forest,     Savanna,     Badlands,    over-       limits]
                    Meadow]     Swamp]       Ice Spikes]  world
                                                          biomes]
```

## How It Works

1. **Biome bands** are defined in the config file as distance ranges
2. When the game queries "what biome is at (x, z)?", our custom `BiomeSource` checks the distance to spawn
3. If vanilla noise picks a biome not allowed in that band, it's replaced with a fallback biome
4. The outermost band unlocks all biomes — full vanilla generation

## Installation

1. Install [NeoForge](https://neoforged.net/) for Minecraft 1.21.1
2. Download the mod JAR and place it in `mods/`
3. When creating a world, select the **"Spawn Distance Biomes"** world type
4. Configure bands in `config/spawndistancebiomes.toml`

## Configuration

Edit `config/spawndistancebiomes.toml`:

```toml
# Format: "maxDistance;biome1,biome2,...;fallbackBiome"
# maxDistance = -1 for infinite; "*" = allow all biomes
bands = [
    "512;minecraft:plains,minecraft:forest,minecraft:meadow,minecraft:river;minecraft:plains",
    "1536;minecraft:plains,minecraft:forest,minecraft:taiga,minecraft:savanna,...;minecraft:forest",
    "-1;*;minecraft:plains"
]
```

Changes take effect on new chunk generation (no restart needed, reloads from config every 5 seconds).

## Compatibility

- **Zero conflicts** with structure mods (YUNG's, When Dungeons Arise, etc.)
- **Zero conflicts** with mob mods (Alex's Mobs, Ice and Fire, etc.)
- **Zero conflicts** with resource/ore mods (Create, etc.)
- All of the above gate their content by biome — our mod controls which biomes exist where

## Building

```bash
./gradlew build
```

Output: `build/libs/spawndistancebiomes-0.1.0.jar`

## License

MIT
