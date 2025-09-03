# R2NES - NES Emulator (Java) / Emulador NES (Java)

> English first, Portuguese version below / Inglês primeiro, versão em Português abaixo.

<div align="center">
  <img src="./nesemu/.assets/logob.png"/>
</div>

---
## English

### Overview
Experimental NES emulator (CPU + PPU) in Java focused on background pipeline accuracy & diagnostic tooling.

### Build
Requires Java 17+ and Maven.
```powershell
mvn package
```
Artifact: `target/R2NES-X.X.jar`.

### Basic Usage
ROM precedence:
1. First CLI arg not starting with `--`.
2. `rom=` in `emulator.ini`.
Example:
```powershell
java -cp target/R2NES-X.X.jar --frames=60
```
GUI + HUD:
```powershell
java -cp target/R2NES-X.X.jar --gui --hud
```
Explicit override:
```powershell
java -cp target/R2NES-X.X.jar D:\path\game.nes --gui --hud
```

### Synthetic Background Test Modes
| Flag | Description |
|------|-------------|
| `--test-bands` / `--test-bands-h` | 5 horizontal bands (~48 lines each) |
| `--test-bands-v` | 5 vertical bands (~51 px each) |
| `--test-checker` | 8x8 checkerboard (4 colors) |
```powershell
java -cp target/R2NES-X.X.jar --gui --test-bands-h --hud
```
Notes: forced palette indices 1..5, grayscale bit ignored, HUD shows frame/scanline/cycle/fineX.

### Real ROM
```powershell
java -jar target\R2NES-X.X.jar --gui --hud roms\realrom.nes
```

### Background Diagnostics Flags
| Flag | Purpose |
|------|---------|
| `--force-bg` | Force PPUMASK bg bit |
| `--dbg-bg-sample=N` | Sample up to N background pixels |
| `--dbg-bg-all` | Extend sampling window |
| `--bg-col-stats` | Per column stats |
| `--tile-matrix=...` | ASCII tile matrix mode |
| `--timing-simple` | Simplified timing mode |
| `--init-scroll` | Initialize scroll/VRAM registers |
| `--log-attr[=N]` | Attribute table writes log |
| `--log-nt[=N]` | Nametable writes log |
| `--nt-baseline=HH` | Filter baseline value |
| `--dump-nt` | Dump nametable tile IDs |
| `--dump-pattern=TT` | Dump one pattern tile |
| `--dump-patterns=...` | Dump multiple tiles |
| `--frames=N` | Frames headless |
| `--until-vblank` | Run until first vblank |
| `--hud` | Overlay stats |
| `--quiet` | Disable legacy verbose |
| `--verbose` | Force legacy verbose |
| `--unlimited-sprites` | Disable 8-sprite limit |
| `--sprite-y=hardware or test` | Sprite Y semantics |

### Outputs
| File | Content |
|------|---------|
| `background.ppm` | Grayscale PPM or synthetic pattern |
| Console | Logs / stats / matrix |

### Logging
Levels: TRACE, DEBUG, INFO, WARN, ERROR. Categories: CPU, PPU, APU, BUS, DMA, CONTROLLER, ROM, TEST, GENERAL.

### `emulator.ini`
```ini
gui=true
log-level=INFO
log-cats=PPU,CPU
log-ts=true
reset=F1
log-palette=256
rom=roms/realrom.nes
```
New keys: `unlimited-sprites`, `sprite-y`.

### Sprites
`hardware`: OAM Y = (top-1). `test`: direct top. Unlimited removes hardware per-scanline limit.

### Supported Mappers
| Mapper | Name | Features |
|--------|------|----------|
| 0 | NROM | Fixed PRG, CHR 8K, H/V |
| 1 | MMC1 | PRG/CHR banking, single-screen |
| 2 | UxROM | Switchable 16K + fixed high 16K |
| 3 | CNROM | 8K CHR bank |

### Implementation Notes
* Shift registers, 8-cycle cadence.
* `--timing-simple` for early debug.
* Odd-frame pre-render cycle skip.

### Next Steps
* Horizontal gradient pattern.
* Mid-scanline palette change test.
* Batch frame export.

---
Project evolving; some PPU fine timing & sprite edge cases pending.

---
## Português

### Visão Geral
Projeto experimental de emulação NES (CPU + PPU) em Java, focado em precisão do pipeline de background e ferramentas de diagnóstico.

### Build
Requer Java 17+ e Maven.
```powershell
mvn package
```
Artefato: `target/R2NES-X.X.jar`.

### Execução Básica
Precedência:
1. Primeiro argumento CLI não iniciado por `--`.
2. `rom=` no `emulator.ini`.
Exemplo:
```powershell
java -cp target/R2NES-X.X.jar --frames=60
```
GUI + HUD:
```powershell
java -cp target/R2NES-X.X.jar --gui --hud
```
Override:
```powershell
java -cp target/R2NES-X.X.jar D:\caminho\jogo.nes --gui --hud
```

### Modos Sintéticos
| Flag | Descrição |
|------|-----------|
| `--test-bands` / `--test-bands-h` | 5 faixas horizontais |
| `--test-bands-v` | 5 faixas verticais |
| `--test-checker` | Tabuleiro 8x8 |

### ROM Real
```powershell
java -jar target\R2NES-X.X.jar --gui --hud roms\realrom.nes
```

### Flags de Diagnóstico
| Flag | Função |
|------|--------|
| `--force-bg` | Força background |
| `--dbg-bg-sample=N` | Amostra até N pixels |
| `--dbg-bg-all` | Amplia janela |
| `--bg-col-stats` | Estatísticas coluna |
| `--tile-matrix=...` | Matriz ASCII |
| `--timing-simple` | Tempo simplificado |
| `--init-scroll` | Inicializa scroll |
| `--log-attr[=N]` | Log attribute |
| `--log-nt[=N]` | Log nametable |
| `--nt-baseline=HH` | Filtro baseline |
| `--dump-nt` | Dump nametable |
| `--dump-pattern=TT` | Dump tile |
| `--dump-patterns=...` | Dump múltiplos |
| `--frames=N` | Frames headless |
| `--until-vblank` | Até vblank |
| `--hud` | HUD |
| `--quiet` | Silencia verboso |
| `--verbose` | Força verboso |
| `--unlimited-sprites` | Remove limite |
| `--sprite-y=hardware ou test` | Semântica Y |

### Saídas
| Arquivo | Conteúdo |
|---------|----------|
| `background.ppm` | PPM escala cinza / padrão |
| Console | Logs / estatísticas |

### Logging
Níveis: TRACE, DEBUG, INFO, WARN, ERROR. Categorias: CPU, PPU, APU, BUS, DMA, CONTROLLER, ROM, TEST, GENERAL.

### `emulator.ini`
```ini
gui=true
log-level=INFO
log-cats=PPU,CPU
log-ts=true
reset=F1
log-palette=256
rom=roms/realrom.nes
```
Chaves: `unlimited-sprites`, `sprite-y`.

### Sprites
`hardware`: OAM Y = topo-1. `test`: topo direto. Unlimited remove limite de 8.

### Mappers
| Mapper | Nome | Recursos |
|--------|------|----------|
| 0 | NROM | PRG fixo, CHR 8K |
| 1 | MMC1 | Banking PRG/CHR |
| 2 | UxROM | PRG 16K switch + fixo |
| 3 | CNROM | Banking CHR |

### Notas
* Shift registers 8 ciclos.
* `--timing-simple` ajuda depuração.
* Skip ciclo pré-render frame ímpar.

### Próximos Passos
* Gradiente horizontal.
* Mudança palette mid-frame.
* Export múltiplos frames.

---
Projeto em evolução; partes de timing fino e sprites ainda pendentes.