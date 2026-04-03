# O que é isso?

Eu encontrei esse LLMoblings, capaz de inserir um NPC de LLM no Minecraft, e vi que ele funcionava perfeitamente bem no Mine 1.21.1 (chei de mods) com NeoForge (mod loader mais utilizado) e Ollama (LLMs locais e acessíveis). Para melhorar a experiência, decidi pegar o ChatGPT e passar o mod inteiro para Português, além de mandar ele fazer correções e criar comportamentos.

Note que apesar de eu ser um programador, não sou fluente em Java e muito menos em desenvolvimento de mods do Minecraft! Não me perturbem com PRs, issues nem manutenção. Claramente estou usando LLMs para criar o conteúdo e minha experiência para tomar as decisões e fazer pequenos remendos.

Se quiserem testar, sigam os passos abaixo, em Inglês, e podem falar com a LLM totalmente em Português. Recomendo baixarem o modelo qwen2.5:7b-instruct e deixei o jar disponível na página de releases.

- Xala

# LLMoblings - LLM-Powered Mobs for Minecraft

An AI-powered companion mod for NeoForge 1.21.1 that creates intelligent NPCs you can command using natural language. Companions are powered by Ollama LLM and can perform a wide variety of tasks autonomously.

## Features

### Natural Language Commands
Talk to your companions using the `@` prefix in chat:
- `@Sam follow me` - Companion follows you
- `@Sam get me some wood` - Companion gathers resources
- `@Sam attack that zombie` - Companion fights mobs
- `@Sam go autonomous` - Companion operates independently
- `@Sam build a cottage here` - Companion builds structures

### Autonomous Behavior
Companions can operate independently when set to autonomous mode:
- **Hunt** for food (cows, pigs, sheep, chickens, fish, and modded animals)
- **Avoid** farm animals (named, leashed, or in enclosures)
- **Equip** weapons and armor from inventory
- **Patrol** the area for threats
- **Explore** your base (can open doors!)
- **Store** excess items in nearby chests or ME networks
- **Retrieve** items from AE2 ME networks

---

## Personality System

Each companion is randomly assigned one of **6 distinct personalities** on creation:

| Personality | Description | Example Quote |
|-------------|-------------|---------------|
| **Adventurous** | Eager and brave, loves action | "FOR GLORY!" |
| **Scholarly** | Curious and analytical, loves facts | "Statistically, diamonds appear between Y -64 and 16." |
| **Laid-back** | Casual and relaxed, loves naps | "Working hard or hardly working? ...neither, actually." |
| **Cheerful** | Optimistic and encouraging | "YAY! You're back! I missed you SO much!" |
| **Sarcastic** | Witty and playfully teasing | "Oh look, more standing around. Thrilling." |
| **Mysterious** | Cryptic and philosophical | "The void whispers... but I don't speak void." |

### Rarity-Based Behaviors
Companions have tiered dialogue that gets progressively more memorable:

| Rarity | Chance | Description |
|--------|--------|-------------|
| **Common** | 70% | Everyday idle chatter and task comments |
| **Uncommon** | 20% | Environment comments + emotes |
| **Rare** | 8% | Memorable personality-specific moments |
| **Legendary** | 2% | Very special, hilarious monologues |

*Pity system*: Rare/Legendary chances increase over time if you haven't seen one recently.

### Jokes & Interactions
- **Minecraft puns**: "I lava good adventure!" / "Nether gonna give you up!"
- **Player teasing** (especially Sarcastic): "Remember when you fell in lava? Classic."
- **Other player interactions**: Companions comment on nearby players by name
- **Owner gear comments**: "Nice Diamond Sword! Ready to slay some mobs!"
- **Health warnings**: Personality-specific alerts when owner is low HP

### Emotes & Particles
- **Hearts** when happy
- **Smoke** when sad
- **Angry villager** particles when frustrated
- **Fireworks, notes, enchant glyphs** for rare/legendary moments
- **Arm swings, head turns, jumps** for physical emotes

---

## Building System

Companions can build structures from blueprints!

### Commands
- `@Sam build a cottage here` - Build at current location
- `@Sam build house at 100 64 200` - Build at specific coordinates

### Available Structures
- **Cottage** (7x7x5): Cobblestone foundation, oak walls, peaked roof, bed, torches

### Building Process
1. **Material Check**: Companion inventories materials needed
2. **Gathering**: Gets materials from inventory -> ME network -> chests -> mines/chops
3. **Site Prep**: Clears vegetation and levels ground
4. **Construction**: Places blocks in order (foundation -> walls -> roof -> interior)

---

## Mod Integrations

### Applied Energistics 2 (AE2)
- Access ME networks to store/retrieve items
- Auto-craft gear if patterns are available
- Commands:
  - `@Sam get iron gear` - Retrieve/craft iron armor set
  - `@Sam get diamond gear` - Retrieve/craft diamond armor set
  - `@Sam deposit items` - Store inventory in ME network

### Cobblemon (Pokemon)
Companions can have a Pokemon buddy that follows them!
- `@Sam find a pokemon buddy` - Bond with nearest player's Pokemon
- `@Sam bond with Pikachu` - Bond with specific Pokemon by name
- `@Sam release your buddy` - Release current Pokemon buddy
- `@Sam check on your buddy` - Status report on Pokemon companion
- `@Sam cobblestats` - Check nearby Pokemon IVs/EVs/nature/ability
- Pokemon buddy follows companion, teleports if too far

### Building Gadgets 2
Companions can use Building Gadgets for construction!
- `@Sam check your gadget` - Report gadget status
- `@Sam equip your gadget` - Equip gadget from inventory
- `@Sam set gadget to cobblestone` - Configure block type
- `@Sam gadget range 5` - Set build range
- `@Sam use the gadget` - Build with gadget

### Sophisticated Backpacks
Companions can use backpacks for extra storage!
- `@Sam check your backpack` - Report backpack status (slots used/total)
- `@Sam store cobblestone in backpack` - Store specific items
- `@Sam stash everything in backpack` - Store all non-gear items
- `@Sam get diamonds from backpack` - Retrieve items
- `@Sam what's in your backpack` - List contents

**Supported tiers**: Leather (27), Copper (36), Iron (45), Gold (54), Diamond (72), Netherite (81)

### FTB Teams
- Teammates can give commands to each other's companions
- Non-teammates can only chat (no commands)

### JourneyMap
- Companions appear as waypoints on your map

### Elevator Support
- Companions can use elevator blocks (Quark, OpenBlocks, etc.)
- `@Sam go up` / `@Sam go down` - Use nearby elevator

### Other Storage Mods
Companions detect and can use:
- Vanilla chests, barrels, shulker boxes
- Iron Chests (all tiers)
- Storage Drawers
- Sophisticated Storage

---

## Commands Reference

### Server Commands
```
/companion summon <name>  - Summon a new companion
/companion dismiss <name> - Dismiss a specific companion
/companion dismiss        - Dismiss all your companions
/companion list           - List your companions with status
/companion help           - Show help
```

### Chat Commands (via @prefix)

**Movement:**
| Command | Description |
|---------|-------------|
| `follow` | Follow the player |
| `stay` / `stop` | Stop and stay in place |
| `come` | Come to player's location |
| `goto <x> <y> <z>` | Go to specific coordinates |

**Combat:**
| Command | Description |
|---------|-------------|
| `attack <target>` | Attack specific mob type |
| `defend` | Defend player from hostiles |
| `retreat` | Run back to player |

**Resources:**
| Command | Description |
|---------|-------------|
| `mine <block> <count>` | Mine specific blocks |
| `gather <item> <count>` | Gather items |
| `give <item>` | Give items to player |

**Inventory:**
| Command | Description |
|---------|-------------|
| `inventory` | Report inventory contents |
| `equip` | Equip best weapon from inventory |
| `deposit` | Store items in ME/chest (keeps gear) |
| `deposit everything` | Store ALL items including gear |

**ME Network:**
| Command | Description |
|---------|-------------|
| `get iron gear` | Get iron armor/sword from ME |
| `get diamond gear` | Get diamond armor/sword from ME |

**Home:**
| Command | Description |
|---------|-------------|
| `sethome` | Set current location as home |
| `setbed` | Remember nearby bed location |
| `home` | Return to home/bed |
| `sleep` | Try to sleep at night |

**Autonomous:**
| Command | Description |
|---------|-------------|
| `autonomous` / `auto` | Go fully independent |
| `explore` | Explore the area |
| `scan <radius>` | Scan for mobs/resources |
| `status` | Report health and inventory |

**Multi-Step Commands (Iterative Action Loop):**

Companions can now chain multiple actions per request. Query actions (scan, status, inventory) feed results back to the LLM, which then decides the next action:

| Example | What Happens |
|---------|--------------|
| `check inventory and equip your best weapon` | Queries inventory → equips best weapon |
| `are there monsters nearby? defend me if so` | Scans area → defends or idles |
| `scan the area and mine any diamonds you find` | Scans → starts mining if found |

The loop runs up to 3 iterations by default (configurable). Simple commands like `follow me` still complete in a single step.

**Building:**
| Command | Description |
|---------|-------------|
| `build cottage here` | Build cottage at current spot |
| `build house at X Y Z` | Build at coordinates |

**Pokemon (Cobblemon):**
| Command | Description |
|---------|-------------|
| `find a pokemon buddy` | Bond with nearby Pokemon |
| `bond with <name>` | Bond with specific Pokemon |
| `release buddy` | Release Pokemon companion |
| `check buddy` | Status of Pokemon buddy |
| `cobblestats` | Check nearby Pokemon stats |

**Gadgets (Building Gadgets 2):**
| Command | Description |
|---------|-------------|
| `gadget info` | Check gadget status |
| `equip gadget` | Equip gadget |
| `gadget set <block>` | Set block type |
| `gadget range <n>` | Set build range |
| `use gadget` | Build with gadget |

**Backpack (Sophisticated Backpacks):**
| Command | Description |
|---------|-------------|
| `backpack info` | Check backpack status |
| `store <item> in backpack` | Store specific items |
| `stash everything` | Store all non-gear |
| `get <item> from backpack` | Retrieve items |
| `backpack contents` | List what's inside |

---

## Ultimine-Style Mining

Companions use intelligent mining similar to FTB Ultimine:

### Vein Mining
When mining ores, companions automatically detect and mine the entire connected vein:
- Finds all connected blocks of the same ore type (including diagonals)
- Matches deepslate variants with regular ores
- Up to 32 blocks per vein
- Logs: `[Sam] Vein mining: 8 blocks queued`

### Tree Felling
When chopping logs, companions fell the entire tree:
- Finds all connected logs of the same wood type
- Also breaks leaves (optional cleanup)
- Breaks from top down (natural tree falling)
- Up to 64 blocks per tree

### Automatic Tool Selection
Companions equip the best tool before mining:
- **Pickaxe** for ores, stone, cobblestone, bricks
- **Axe** for logs, wood, planks, fences
- **Shovel** for dirt, sand, gravel
- **Hoe** for leaves, hay, moss

Tool tier affects mining speed:
| Tier | Speed Multiplier |
|------|------------------|
| Wood/Gold | 2x |
| Stone | 4x |
| Iron | 6x |
| Diamond | 8x |
| Netherite | 9x |
| Allthemodium+ | 10-12x |

### Crop Harvesting
Companions can harvest and replant crops:
- Detects mature crops (wheat, carrots, potatoes, etc.)
- Harvests pumpkins and melons
- Picks sweet berries when ready
- **Auto-replants** from harvested seeds

---

## Hunting System

Companions intelligently hunt for food:

### Huntable Animals
**Vanilla:** Cow, Pig, Sheep, Chicken, Rabbit, Cod, Salmon, Squid, Turtle, Mooshroom, Goat

**Alex's Mobs:** Bison, Moose, Gazelle, Kangaroo, Capybara, and more

**Alex's Caves:** Various cave creatures

### Farm Animal Protection
Companions will NOT hunt animals that are:
- **Named** (has custom name tag)
- **Leashed** (on a lead)
- **In enclosures** (8+ fence blocks nearby)
- **Near farm structures** (hay bales, troughs, feeding blocks)
- **On player-made flooring** (planks, concrete, etc.)

### Hunt Priority
Higher priority targets are preferred:
- Cow: 100 | Pig: 80 | Sheep: 70 | Chicken: 50 | Fish: 30 | Goat: 25

---

## Configuration

Config file: `config/llmoblings-common.toml`

### Ollama Settings
```toml
[ollama]
host = "192.168.70.24"  # Ollama server IP
port = 11434            # Ollama port
model = "llama3:8b"     # Model to use
timeout = 30            # Request timeout (seconds)
```

### Companion Settings
```toml
[companion]
maxPerPlayer = 3              # Max companions per player
takeDamage = true             # Companions can be hurt
needFood = false              # Hunger system
followDistance = 5.0          # Default follow distance
itemPickupRadius = 3          # Item pickup range
loadChunks = true             # Force-load companion's chunk
actionLoopEnabled = true      # Multi-step action loop (query → decide → act)
actionLoopMaxIterations = 3   # Max iterations per loop (1 = single-shot)
```

### Chat Settings
```toml
[chat]
prefix = "@"                          # Chat prefix to address companions
broadcastChat = true                  # Broadcast responses to nearby players
allowOtherPlayerInteraction = true    # Let non-owners chat with companions
```

---

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Ollama server running with a compatible model (llama3:8b recommended)

### Optional Mod Support
- Applied Energistics 2 (ME network access)
- Cobblemon (Pokemon buddy system + stats)
- Building Gadgets 2 (gadget usage)
- Sophisticated Backpacks (backpack usage)
- FTB Teams (team-based permissions)
- JourneyMap (map markers)

---

## Installation

1. Install NeoForge 1.21.1
2. Place `llmoblings-1.0.0.jar` in your mods folder
3. Configure Ollama connection in config file
4. Start the server/game
5. Use `/companion summon <name>` to create your first companion!

---

## Troubleshooting

### Companion not responding
- Check Ollama server is running and accessible
- Verify config has correct host/port
- Check server logs for connection errors

### Companion getting stuck
- Companions have stuck detection - they'll give up after ~6-9 seconds
- Try commanding them to `come` to you
- Use `@Name tpa` to teleport them to you

### Companion hunting farm animals
- Name your farm animals with name tags
- Leash animals to fences
- Build proper enclosures with fences
- Ensure farm area has recognizable farm blocks

### Logs
The mod logs to `logs/latest.log` with prefix `[LLMoblings]`:
- INFO level: State changes, commands received, actions taken
- DEBUG level: Detailed pathfinding, inventory operations

---

## License

MIT License - See LICENSE file for details.

## Credits

Developed for the gblfxt modpack.

AI-powered by Ollama and Llama models.
