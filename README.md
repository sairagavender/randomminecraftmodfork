<div align="center">

# HardcorePlus+

[![Build](https://img.shields.io/github/actions/workflow/status/DeisDev/HardcorePlusPlus/build.yml?branch=main&logo=github&label=CI)](https://github.com/DeisDev/HardcorePlusPlus/actions)
[![License](https://img.shields.io/github/license/DeisDev/HardcorePlusPlus)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1_%7C_1.21.8_%7C_1.21.10_%7C_1.21.11-lime)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20NeoForge-5b8c)
[![Modrinth](https://img.shields.io/badge/Modrinth-Page-00AF5C?logo=modrinth)](https://modrinth.com/mod/hardcore-plus+)

Server-side Hardcore mod that makes dying more punishing: if anyone dies, the whole server wipes and generates a fresh world.

</div>

## Disclaimer

This is a hardcore multiplayer mod. When installed on a server:
- If any player dies, all players die and the current world is wiped.
- Expect irreversible world loss unless you configure backups and keep external copies.
- Do not add to existing hardcore worlds unless you’re prepared to lose the save on death.

## Features

- When one player dies in a hardcore world, all other players die too. 
- The server shuts down, and restarts with a new world to play. 
- Behavior can be configured. 
- Works on both Neoforge and Fabric. 

## Installation

Download from [Modrinth](https://modrinth.com/mod/hardcore-plus+) or compile the mod from source and place the jar in your server's `mods` folder.

This is a dedicated server-only mod. Clients do not need to install anything.

## Configuration

Generated at `config/hardcoreplus.properties` on first run. 

## Commands

All commands are under `/hcp`.

Key commands:
- `/hcp preview` — show the next world name and seed policy
- `/hcp time` — show MC day/time, ticks, and real uptime
- `/hcp reset` + `confirm` — rotate to a new world
- `/hcp masskill` + `confirm` — kill all players and schedule a reset
- `/hcp reload` — reload config
- `/hcp config` — list all loaded config values

## Auto Restart Server Wrapper (recommended)

Use a simple restart wrapper so the server comes back up after wipe (loop your `java -jar server.jar nogui`).

I strongly recommend this so you do not have to manually restart the server everytime. 

## Contributing

PRs and issues welcome.

## License

APACHE 2.0 — see [LICENSE](LICENSE).
