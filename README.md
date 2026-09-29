# Claude Puppet

A small NeoForge 1.21.1 mod that lets Claude (the AI assistant, running in Claude Code on the same machine) join a
singleplayer world: read chat, talk, run commands and control a mock-player mob, "Claude's Puppet".

## Use

1. Put the jar in the instance's `mods` folder.
2. In a singleplayer world run `/claudepuppet` (no cheats needed; world owner or op only).
   - `/claudepuppet here` brings the puppet to you, `/claudepuppet off` removes it and stops the bridge.
3. Claude talks to the world through `<game folder>/claudepuppet/`:
   - `in.txt`: commands, one per line (see `PuppetBridge` for the full list: `say`, `cmd`, `walkto`, `follow`,
     `attack`, `guard`, `gather`, `break`, `place`, `use`, `useon`, `eat`, `shoot`, inventory commands...).
   - `state.json`: players, the puppet, nearby mobs, time and weather, rewritten every second.
   - `events.log`: chat, damage, deaths, dimension changes, advancements and command results.

`cmd` runs commands as the server at op level, so they work with cheats off (the world's cheats flag isn't changed).

## The puppet

A pathfinding mob with the player model and a robot skin (`make_skin.py` generates it). 150 health, regenerates out
of combat, 36 slot inventory, uses items through a fake player named "Claude", drops everything on death and respawns
next to the owner.

## License

MIT
