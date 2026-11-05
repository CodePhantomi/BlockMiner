# BlockMiner

A client-side Fabric mod for Minecraft **26.2**. Mark a cuboid, choose a block ID, and let the client mine exposed matching blocks while avoiding nearby water and lava. When the inventory fills, it walks to a marked chest/barrel and moves matching drops into it.

> Use only where automated mining is permitted by the server. This mod simulates player actions; it does not bypass server rules or anti-cheat.

## Requirements

- Minecraft Java Edition 26.2
- Java 25
- Fabric Loader 0.19.3 or newer
- Fabric API for Minecraft 26.2

## Build

From the repository root, use Gradle 9.5.1:

```sh
gradle build
```

The mod jar is written to `build/libs/`. The GitHub Actions workflow also builds the project on pushes and pull requests.

## In-game use

1. Hold a **golden shovel**.
2. Left-click a block to set corner 1.
3. Right-click a block to set corner 2.
4. Left-click a chest, trapped chest, or barrel to set the drop-off.
5. Run `/block mine <block>`, using the block's registry path, for example `diamond_ore` or `stone`.
6. Review the box size and drop-off message, then run `/confirm`.
7. Run `/220` to stop.

The miner targets exposed matching blocks in the marked box. It skips targets near water or lava and stops if it cannot safely reach a target or the drop-off.

## Notes and limitations

- This is a **client-only** mod; it cannot force a server to accept block breaking or inventory actions.
- Keep the chest accessible and leave room in it.
- Keep suitable tools in the hotbar.
- Test in a copy of your world first. Automated movement/mining can be unsafe around cliffs, mobs, hazards, and server protections.
- The current implementation is a first pass; verify it in a local 26.2 Fabric instance before relying on it in a long-running world.
