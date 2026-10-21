# E.D.I.T.H

In-game AI assistant for Minecraft (Fabric, 1.21.4). Press **I** to open it.

- Chat with an AI that knows your game: inventory, position, chat, scoreboard, tab list, menus, boss bars, nearby players, block activity and more.
- Crafting answers render as a real 3x3 grid with item icons.
- Attach screenshots (SHOT button, F9, or drag and drop) and ask about them.
- Notes questions and answers players post in chat (per server).
- **Client only** mode: stops reading anything the server sends. Everything stays on your side except what you send to your AI provider.

## Install
1. Install Fabric Loader and Fabric API for Minecraft 1.21.4.
2. Download the jar from **Releases** (or the **Actions** tab, artifact `edith-jar`) into `.minecraft/mods`.
3. Launch, press **I**, open the **Core** tab and paste your API key.

Supports Anthropic and any OpenAI-compatible endpoint (including local models). Image reading needs a vision-capable model.

## Build
```
gradle build          # JDK 21 + Gradle 8.12, or ./gradlew build if you add the wrapper
```
The jar appears in `build/libs/`.

## Privacy
Your API key is stored in plain text in `config/edith.json`. Chat, scoreboard and other game data (and any screenshots you attach) are sent to the AI provider you configure when you ask a question. Use CLIENT ONLY mode to stop server data being included.
