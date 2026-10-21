# E.D.I.T.H (Fabric, client-side)

In-game AI helper. Press **I** (rebindable in Controls, or type `/edith`) to open the popup.

## Build
Same as Blocks per sometimes: upload the whole folder to a GitHub repo, open the **Actions** tab,
download the jar artifact (MC 26.1.2 and 26.2 builds). Needs Fabric API.

## First start
1. Press I -> **Brain** tab -> paste your API key (Anthropic by default; switch Provider to OPENAI for
   OpenAI / OpenRouter / Ollama / LM Studio - set the Endpoint for those).
   You can also set the `EDITH_API_KEY` environment variable instead of saving the key in `config/edith.json`.
2. Chat tab -> type and press Enter.

## What it does
- **Chat**: general AI help + Minecraft help. Crafting questions come back as a real 3x3 grid with item icons,
  the result slot, and a legend with each item's slot (top-left, center, ...).
  Up/Down arrows recall old prompts, PageUp/PageDown or mouse wheel scroll, Ctrl+V pastes, `/clear` wipes the chat.
- **Server** tab (the "SERVER LINK" chip in the title bar toggles this whole group): chat log, scoreboard, tab list,
  titles / action bar / boss bars, open menus (names + lore), server block changes, Q&A notebook, private messages (off by default).
- **Local** tab (always client side): movement + position + teleports, health/hunger/XP changes, your own
  breaking/placing + blocks/crops per minute/hour, nearby entities, inventory, crosshair target.
- **Memory** tab: every question players asked in chat + the replies that followed, saved per server in
  `config/edith/<server>.json`. Menus you open are remembered too, so you can ask about them later.
- **CLIENT ONLY** mode (SERVER LINK off): nothing the server sends is read. E.D.I.T.H still works as an AI helper and
  still uses everything the Local tab lists.

## Privacy
- Nothing is sent anywhere until you press Enter/GO. Then your question + a text snapshot of the enabled senses goes to
  the AI provider you configured.
- Other players' chat lines are sent to the provider when "Chat log" / "Q&A notebook" are on. Turn those off or use CLIENT ONLY if you don't want that.
- Private messages are filtered out by default (pattern based, not perfect).

## Limits
- E.D.I.T.H can only talk. It cannot press keys, run commands, or create files/images.
- Recipes come from the AI model's knowledge, not the game files. Vanilla recipes are reliable; custom server recipes are not.
- Q&A notebook uses simple rules (a chat line with "?" is a question; the next lines from other people are answers).
- Scoreboard/tab/boss bar reading uses reflection, so if a future Minecraft update renames something that part just goes quiet instead of crashing.
