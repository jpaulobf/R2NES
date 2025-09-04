# R2NES - NES Emulator (Java) / Emulador NES (Java)

> English first, Portuguese version below / Inglês primeiro, versão em Português abaixo.

<div align="center">
  <img src="./nesemu/.assets/logob.png"/>
</div>

---
## English

### Overview
Experimental NES emulator (CPU + PPU) in Java focused on background pipeline accuracy & diagnostic tooling.
 
### New (0.3.9.8)
Turbo buttons (autofire) & no-ROM startup improvements:
* New optional mappings in `emulator.ini`: `dp-01-a-turbo`, `dp-01-b-turbo` (and equivalents for pad2). If not mapped, turbo is inactive (no overhead).
* Default turbo cadence: 15 Hz (pattern ON 2 frames / OFF 2 frames @60fps).
* Optional faster cadence: set `turbo-fast=true` for 30 Hz (ON 1 / OFF 1).
* Turbo key has precedence over the normal A/B key when both held (clean deterministic state latching).
* Internal frame hook drives cadence (`onFrameAdvance` per controller) – deterministic across runtime (not yet serialized in save states).
* Startup with GUI and no ROM now shows a clean black screen (HUD / ESC still functional) instead of auto-loading a legacy fallback ROM.
* Removed deprecated implicit fallback ROM behavior (prevents accidental infringement / confusion).

Limitations / Next:
* Turbo phase not saved in snapshots (will reset pattern after load; acceptable for now).
* Potential future HUD indicator (e.g., showing T-A / T-B when active) – deferred.

### Previous (0.3.9.2)
Left column rendering modes & scroll fidelity fixes:
* New `left-column-mode=` (INI / CLI `--left-column-mode=`) values:
  * `hardware` (default) – authentic NES: left 8 background pixels only blank if PPUMASK bit 1 cleared.
  * `always` – always blanks first 8 background pixels (legacy masking; hides edge artifacts unconditionally).
  * `crop` – renders full 256 px internally then post-frame blanks first 8 columns (debug-friendly; no pipeline divergence).
* Post-frame crop hook applied automatically after each frame when mode = crop.
* Fine X tap optimization: cached fine X value reduces per-pixel masking ops.
* Save/load state vertical scroll bit 14 preserved (fixes rare wrong vertical copy at pre-render cycles).
* Default reset PPUMASK adjusted (0x08) removing implicit left-column background enable.

### Previous (0.3.9.1)
UX & pacing diagnostics:
* ESC key (fixed) prompts confirmation (autosave on Yes, then exit).
* Mouse cursor auto-hidden in borderless fullscreen, restored on exit.
* Frame pacing instrumentation (avg frame time, jitter, worst frame) internal (HUD exposure soon).
* High-res pacer earlier realignment (>1 frame lag) to reduce burst stutter.

### Previous (0.3.9)
Fast-forward system + timing refinements:
* Hold configurable hotkey (INI `fast-foward=` / CLI `--fast-forward-key=`) to bypass normal frame pacing.
* Optional throttle: INI `fast-foward-max-fps=` or CLI `--fast-forward-max-fps=`. Set to e.g. 240 for ~4x, 0 (default) = uncapped.
* HUD overlay now shows `FFWD xN.N` multiplier relative to 60 FPS.
* TAB usable as fast-forward key (focus traversal disabled internally).
* PPU background phase-0 fetch refactored (readability, no functional change).

### Previous (0.3.8)
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
| `--fast-forward-key=KEY` | Override fast-forward hotkey (INI `fast-foward=`) |
| `--fast-forward-max-fps=N` | Throttle cap during fast-forward (0 = uncapped) |

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

### Novidade (0.3.9.8)
Botões turbo (autofire) & modo sem ROM:
* Novos mapeamentos opcionais no `emulator.ini`: `dp-01-a-turbo`, `dp-01-b-turbo` (e pad2). Se não mapeados, turbo fica totalmente inativo (zero custo).
* Cadência padrão: 15 Hz (liga 2 frames / desliga 2 frames).
* `turbo-fast=true` ativa modo rápido 30 Hz (liga 1 / desliga 1).
* Tecla turbo tem precedência sobre a tecla normal A/B quando ambas pressionadas (estado previsível na latch).
* Hook interno por frame (`onFrameAdvance`) dirige a cadência (ainda não incluso no save state).
* Iniciar GUI sem ROM agora exibe tela preta limpa (HUD / ESC ok) em vez de carregar ROM fallback antiga.
* Comportamento de fallback automático de ROM removido (evita confusão / possíveis issues de licença).

Limitações / Próximos:
* Fase do turbo não serializada no snapshot (reinicia padrão após load).
* Indicador HUD (ex: T-A / T-B) pode vir depois.

### Versão Anterior (0.3.9.2)
Modos de coluna esquerda & correções de scroll:
* Novo `left-column-mode=` (INI / CLI `--left-column-mode=`) com valores:
  * `hardware` (padrão) – comportamento autêntico: 8 px iniciais só ficam em branco se bit 1 do PPUMASK estiver limpo.
  * `always` – sempre apaga os 8 primeiros pixels (mascaramento constante para ocultar artefatos).
  * `crop` – renderiza 256 px internamente e apaga após o frame (debug-friendly, sem alterar pipeline).
* Hook pós-frame executa crop automático quando modo = crop.
* Otimização de fine X (cache) reduz operações por pixel.
* Correção: bit 14 do scroll vertical preservado ao carregar estado (evita cópia vertical incorreta no pré-render).
* PPUMASK inicial ajustado para 0x08 (removendo enable implícito de background na coluna esquerda).

### Versão Anterior (0.3.9.1)
UX e diagnósticos de pacing:
* Tecla ESC (fixa) abre confirmação (autosave se Yes e encerra).
* Cursor do mouse oculto automaticamente em fullscreen borderless e restaurado ao sair.
* Instrumentação de tempo de frame (média, jitter, pior) interna (HUD em breve).
* Realinhamento mais cedo no pacer high-res (>1 frame de atraso) reduz bursts.

### Versão Anterior (0.3.9)
Sistema de fast-forward + refinamentos de timing:
* Manter hotkey configurável pressionada (INI `fast-foward=` / CLI `--fast-forward-key=`) ignora o pacing normal.
* Throttle opcional: INI `fast-foward-max-fps=` ou CLI `--fast-forward-max-fps=`. Ex: 240 ≈ 4x; 0 (padrão) = sem limite.
* HUD mostra `FFWD xN.N` (multiplicador relativo a 60 FPS).
* TAB agora funciona (teclas de travessia de foco desabilitadas).
* Refatoração da fase 0 do pipeline de background da PPU (legibilidade).

### Versão Anterior (0.3.8)
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
| `--fast-forward-key=TECLA` | Hotkey fast-forward (override INI `fast-foward=`) |
| `--fast-forward-max-fps=N` | Limite FPS durante fast-forward (0 = sem limite) |

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