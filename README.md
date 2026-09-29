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

## Effort levels

`/claudepuppet effort <off|low|normal|high>` (default low) controls what wakes Claude up, to keep it cheap:

| Level | Claude responds to |
|---|---|
| off | nothing (the puppet keeps working and looking after itself) |
| low | chat that mentions "claude" or starts with `!` |
| normal | all chat, deaths, finished or failed jobs |
| high | everything: damage, fights, command output |

The puppet handles routine work itself: it announces finished jobs in chat (`[Puppet] Gathered 16 wood.`), goes back to
following the owner afterwards, fights back against mobs that hit it, and eats from its backpack below half health.
`status.txt` is a one-line summary for cheap reads.

## Snapshots

`/claudepuppet snapshot [width]` (or Claude's `snapshot` command) saves what the puppet sees to
`claudepuppet/view.png`, default 192 pixels wide. The client renders one or two frames from the puppet's eyes (first
person, HUD hidden, so you may see a flicker), shrinks the image and switches back. Small images are cheap for Claude
to look at. Singleplayer client only.

## The puppet

A pathfinding mob with the player model and a robot skin (`make_skin.py` generates it). 150 health, regenerates out
of combat, 36 slot inventory, uses items through a fake player named "Claude", drops everything on death and respawns
next to the owner.

## License

MIT
