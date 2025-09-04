# R2NES - NES Emulator (Java) / Emulador NES (Java)

> English first, Portuguese version below / Inglês primeiro, versão em Português abaixo.

<div align="center">
  <img src="./nesemu/.assets/logob.png"/>
</div>

---
## English

### Overview
Experimental NES emulator (CPU + PPU) in Java focused on background pipeline accuracy & diagnostic tooling.
 
### New (0.3.8)
Save state system (snapshot) with hotkeys and INI configuration:
* Default hotkeys: F5 = Save, F7 = Load (configurable in `emulator.ini`).
* Keys in INI:
  * `save-state-path=` directory for `.state` files (created if missing).
  * `save-state=` key token (e.g. F5, F2 or letter), supports multiple alternatives with `A/a` style.
  * `load-state=` key token.
* State file format: magic 'NESS', version 2 (includes PPU internal latch data). Backward compatible with version 1 (auto-normalizes mid‑frame loads).
* Currently validated on Mapper 0 (NROM) and Mapper 1 (MMC1). Other mappers will serialize their banking registers / CHR & PRG RAM soon.
* On-screen overlay messages: "SAVING", "LOADING", or errors.
Limitations: APU not yet serialized; some rare mid-scanline loads may be normalized to start-of-frame (harmless visual micro-stutter).

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

### Example:

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

### Background Diagnostics & Timing Flags
| Flag | Purpose |
|------|---------|
| `--force-bg` | Force PPUMASK bg bit |
| `--dbg-bg-sample=N` | Sample up to N background pixels |
| `--dbg-bg-all` | Extend sampling window |
| `--bg-col-stats` | Per column stats |
| `--tile-matrix=...` | ASCII tile matrix mode |
| `--timing-simple` | Simplified timing mode (legacy) |
| `--timing-mode=simple|interleaved` | Select global CPU↔PPU scheduling |
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
Save state keys:
```ini
save-state-path=roms/savestates/
save-state=F5
load-state=F7
```
Place a directory path (relative or absolute). Emulator writes `*.state` atomically (temp + move) to reduce corruption.

Hotkey behavior: Press save key at any time; load key restores snapshot and normalizes PPU timing if the snapshot was mid-frame.

### Sprites
`hardware`: OAM Y = (top-1). `test`: direct top. Unlimited removes hardware per-scanline limit.

### Supported Mappers
| Mapper | Name | Features |
|--------|------|----------|
| 0 | NROM | Fixed PRG, CHR 8K, H/V |
| 1 | MMC1 | PRG/CHR banking, single-screen |
| 2 | UxROM | Switchable 16K + fixed high 16K |
| 3 | CNROM | 8K CHR bank |
| 4 | MMC3 | (WIP partial – save state support pending) |
| 5 | MMC5 | (WIP partial – save state support pending) |

### Timing Modes
Two global scheduling strategies (default: simple):

| Mode | Pattern | Characteristics | When to Use |
|------|---------|-----------------|-------------|
| simple | CPU instr then batch 3*cycles PPU | Legacy behavior, fewer context switches | Baseline performance, broad testing |
| interleaved | PPU, CPU, PPU, PPU (per CPU cycle slice) | Lower event latency (NMI, sprite zero hit) closer to hardware cadence | Timing-sensitive debugging, edge cases |

CLI: `--timing-mode=interleaved` or INI `timing-mode=interleaved`. Legacy `--timing-simple` still maps to simple.

### Implementation Notes
* Shift registers, 8-cycle cadence.
* `--timing-simple` for early debug.
* Odd-frame pre-render cycle skip.

### Next Steps
* Extend save-state coverage (mappers 2/3/4/5; APU; PPU shift registers exact restore).
* Horizontal gradient pattern.
* Mid-scanline palette change test.
* Batch frame export.

---
Project evolving; some PPU fine timing & sprite edge cases pending.

---
## Português

### Visão Geral
Projeto experimental de emulação NES (CPU + PPU) em Java, focado em precisão do pipeline de background e ferramentas de diagnóstico.

### Novidade (0.3.8)
Sistema de save state (snapshot) com hotkeys e configuração via INI:
* Hotkeys padrão: F5 = Salvar, F7 = Carregar (configurável no `emulator.ini`).
* Chaves no INI:
  * `save-state-path=` diretório dos arquivos `.state`.
  * `save-state=` tecla para salvar.
  * `load-state=` tecla para carregar.
* Formato: magic 'NESS', versão 2 (inclui latches internos da PPU). Compatível com versão 1 (normaliza load no meio do frame).
* Validado em Mapper 0 (NROM) e Mapper 1 (MMC1). Outros mappers terão seus registradores/buffers serializados em breve.
* Overlay mostra "SAVING" / "LOADING" / erros.
Limitações: APU ainda não persiste; alguns loads raros no meio da varredura reiniciam no início de frame (pequeno ajuste visual).

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

### Exemplo:

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

### Flags de Diagnóstico e Timing
| Flag | Função |
|------|--------|
| `--force-bg` | Força background |
| `--dbg-bg-sample=N` | Amostra até N pixels |
| `--dbg-bg-all` | Amplia janela |
| `--bg-col-stats` | Estatísticas coluna |
| `--tile-matrix=...` | Matriz ASCII |
| `--timing-simple` | Tempo simplificado (legado) |
| `--timing-mode=simple|interleaved` | Seleciona agendamento CPU↔PPU |
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
Chaves de save state:
```ini
save-state-path=roms/savestates/
save-state=F5
load-state=F7
```
Diretório é criado se não existir. Escrita atômica (arquivo temporário + rename) minimiza corrupção.

Hotkeys: Salvar a qualquer momento; carregar restaura snapshot e normaliza timing se capturado no meio do frame.

### Sprites
`hardware`: OAM Y = topo-1. `test`: topo direto. Unlimited remove limite de 8.

### Mappers
| Mapper | Nome | Recursos |
|--------|------|----------|
| 0 | NROM | PRG fixo, CHR 8K |
| 1 | MMC1 | Banking PRG/CHR |
| 2 | UxROM | PRG 16K switch + fixo |
| 3 | CNROM | Banking CHR |
| 4 | MMC3 | (Parcial – suporte save state pendente) |
| 5 | MMC5 | (Parcial – suporte save state pendente) |

### Modos de Tempo
Dois modos globais (padrão: simple):

| Modo | Padrão | Características | Uso Recomendado |
|------|--------|-----------------|-----------------|
| simple | CPU instr + lote PPU | Menos trocas de contexto | Desempenho base |
| interleaved | PPU, CPU, PPU, PPU | Menor latência (NMI, sprite zero) mais próximo do hardware | Depuração de timing |

CLI: `--timing-mode=interleaved` ou INI `timing-mode=interleaved`. `--timing-simple` mantido para retrocompatibilidade.

### Notas
* Shift registers 8 ciclos.
* `--timing-simple` ajuda depuração.
* Skip ciclo pré-render frame ímpar.

### Próximos Passos
* Expandir save state (mappers 2/3/4/5; APU; restaurar shifts/latches exatos).
* Gradiente horizontal.
* Mudança palette mid-frame.
* Export múltiplos frames.

---
Projeto em evolução; partes de timing fino e sprites ainda pendentes.