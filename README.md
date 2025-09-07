# R2NES - NES Emulator (Java) / Emulador NES (Java)

> English first, Portuguese version below / Inglês primeiro, versão em Português abaixo.

<div align="center">
  <img src="./nesemu/.assets/logob.png"/>
</div>

---
## English

### Overview
Experimental NES emulator (CPU + PPU) in Java focused on background pipeline accuracy & diagnostic tooling.

### Current Release (0.5.6)
UX/QoL polish around overlays and ROM lifecycle.

What's new in 0.5.6:
* Centered overlays: all non-HUD message windows (RESET, save/load feedback, fast-forward indicator, PAUSED) are now centered; text is centered within each box. HUD unchanged.
* Safer no-ROM mode: when no ROM is loaded, save/load state, pause, fast-forward, and reset actions are disabled/ignored; fast-forward overlay is hidden.
* File → Close ROM: unloads the current ROM, performs PRG RAM autosave, stops audio, and returns to the black-screen emulator. Window title reflects the no-ROM state.
* Load ROM auto-pause: if a game is running, the emulator auto-pauses while the file chooser is open and restores the prior pause state if canceled.

Highlights from 0.5.5:
* Gamepad (optional): enable with `gamepad=true` in `emulator.ini`. Standard mapping to NES buttons (A/B/Start/Select + D-Pad), fallback to left stick axes.
* Packaging: dependencies are copied to `target/lib` and Jar manifest uses Class-Path to load them automatically.

Highlights from 0.5:
* APU: Pulse1/Pulse2 duty sequencers and sweep (with immediate divider reload on write), Triangle with linear counter and 32-step table, Noise with 15-bit LFSR (short/long modes).
* Mixing: NES-style non-linear pulse combiner and TND mixer (Triangle + Noise; DMC pending).
* Sampling & Playback: fixed-rate 44.1 kHz sample generation with a ring buffer, drained by a JavaSound player (PCM16 mono). Auto-starts on ROM load and restarts on ROM reload.
* Tests expanded covering sweep timing, pulse mute threshold (timer <= 8), triangle gating, and noise shift/output basics.

Key additions since earlier builds:
* Spin watchdog + optional opcode hex dump (stall diagnostics).
* Mapper (MMC1) bank/control logging limit.
* NMI trace & PPUSTATUS ($2002) read counters (frame + last 8 values).
* Sprite 0 hit timestamp instrumentation and optional forced-hit debug flag.
* Inline disassembly snippet in spin / manual snapshots (auto-disabled when instrumentation off).
* Coarse X increment accuracy fix (tile-boundary only) & robust vblank/NMI edge handling.
* Central instrumentation gating (minimal overhead when all debug flags off).
* Existing recent features retained: scanlines overlay, pause + unified exit dialog, turbo buttons (normal & fast), fast‑forward with optional FPS cap, left column modes, interleaved timing mode, save states (v2), HUD overlay, unlimited sprites mode, test pattern rendering.

### Audio
* Output: PCM16 mono at 44.1 kHz via JavaSound. Starts automatically when a ROM is loaded; stops and restarts on ROM reload.
* Configuration: sample rate and buffers are currently fixed; future versions may expose INI/CLI options for rate, latency, and gain.
* Limitations: DMC channel not implemented yet; frame counter/IRQ details simplified. Minor glitches may be heard under heavy load.

### Gamepad (optional)
* Enable: set `gamepad=true` in `emulator.ini`.
* Mapping: A→A, B→B, Start→START, Back→SELECT, D-Pad (fallback to left stick when D-Pad not reported).
* Platform: packaged with Windows natives only by default.
* Notes: picks first detected gamepad; no remapping UI yet; no rumble.

Core Feature Summary:
* CPU: Complete official 6502 instruction set with tracing & breakpoints.
* PPU: Background pipeline (nametable/attribute/pattern fetch), fine/coarse scroll logic, sprite evaluation & priority, basic sprite 0 hit, mirroring, palette handling.
* Timing: Simple & interleaved scheduling; odd-frame cycle skip; fast-forward & pacing strategies.
* Persistence: Save states (v2) + battery-backed PRG RAM autosave.
* Mappers: NROM, MMC1, UxROM, CNROM, partial MMC3/MMC5.
* Debug: Attribute/nametable/palette logs, pipeline logging, watchdog snapshots, disassembly, manual snapshot hotkey.
* UX: HUD, fullscreen & proportion cycling, scanlines, pause, turbo, fast-forward, configurable hotkeys.
* Config: `emulator.ini` precedence (CLI > INI > defaults) with extensive toggles.

Planned (short list): DMC channel, MMC3 IRQ counter, enhanced sprite 0 timing, save state v3 (more internal latches), HUD turbo indicator, audio configuration (rate/latency/gain).

### Build
Requires Java 21+ and Maven.
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

### Versão Atual (0.5.6)
Polimento de UX/QoL em overlays e ciclo de vida da ROM.

Novidades em 0.5.6:
* Overlays centralizados: todas as janelas de mensagens que não são HUD (RESET, feedback de save/load, indicador de fast-forward, PAUSED) agora ficam centralizadas; o texto fica centralizado dentro de cada caixa. HUD inalterado.
* Modo sem ROM mais seguro: quando não há ROM carregada, as ações de save/load state, pausa, fast-forward e reset ficam desabilitadas/ignoradas; o overlay de fast-forward é oculto.
* File → Close ROM: descarrega a ROM atual, faz autosave da PRG RAM, para o áudio e retorna para a tela preta. O título da janela indica o estado sem ROM.
* Auto-pause ao carregar ROM: se um jogo estiver rodando, o emulador entra em pausa automaticamente enquanto o seletor de arquivos está aberto e restaura o estado anterior se houver cancelamento.

Destaques do 0.5.5:
* Gamepad (opcional): habilite com `gamepad=true` no `emulator.ini`. Mapeamento padrão para NES (A/B/Start/Select + D-Pad), com fallback para o stick esquerdo.
* Empacotamento: dependências são copiadas para `target/lib` e o manifest do JAR usa Class-Path para carregá-las automaticamente.

Destaques do 0.5:
* APU: Pulse1/Pulse2 com sequenciadores de duty e sweep (reload imediato do divisor no write), Triangle com contador linear e tabela de 32 passos, e Noise com LFSR de 15 bits (modos curto/longo).
* Mixagem: combinador não-linear para pulses e mixer TND (Triangle + Noise; DMC pendente).
* Amostragem & Reprodução: geração fixa a 44,1 kHz com buffer circular, drenado por um player JavaSound (PCM16 mono). Inicia automaticamente ao carregar ROM e reinicia ao recarregar.
* Testes ampliados para timing de sweep, threshold de mute do pulse (timer <= 8), gating do triangle e basics de shift/saída do noise.

Principais adições recentes:
* Spin watchdog + dump opcional de opcodes.
* Logging limitado de banking do MMC1.
* Trace de NMI e contadores de leituras $2002 (quadro + últimos 8 valores).
* Instrumentação e flag de debug para sprite 0 hit (forçar/medir).
* Trecho de desassembly inline em snapshots (desativado quando instrumentação off).
* Correção de incremento coarse X apenas em limites de tile e limpeza de vblank/NMI duplicado.
* Gating central para minimizar custo quando sem debug ativo.
* Recursos já presentes: scanlines, pausa + diálogo de saída, turbo (normal/rápido), fast‑forward com limite opcional, modos de coluna esquerda, timing interleaved, save states v2, HUD, modo unlimited sprites, padrões sintéticos.

### Áudio
* Saída: PCM16 mono a 44,1 kHz via JavaSound. Inicia automaticamente ao carregar ROM; para e reinicia ao recarregar.
* Configuração: taxa e buffers ainda fixos; versões futuras podem expor opções INI/CLI para taxa, latência e ganho.
* Limitações: canal DMC ainda não implementado; detalhes de frame counter/IRQ simplificados. Pequenos glitches podem ocorrer sob carga.

### Gamepad (opcional)
* Habilitar: `gamepad=true` no `emulator.ini`.
* Mapeamento: A→A, B→B, Start→START, Back→SELECT, D-Pad (fallback para o stick esquerdo quando necessário).
* Plataforma: por padrão, empacotado com natives apenas para Windows.
* Notas: usa o primeiro gamepad encontrado; sem remapeamento/rumble neste momento.

Resumo de Funcionalidades:
* CPU: 6502 completo, trace e breakpoints.
* PPU: pipeline de background, scroll fino/grosso, sprites (prioridade, hit básico), mirroring, paleta.
* Timing: modos simple/interleaved, fast-forward, pacing configurável.
* Persistência: save states v2 + autosave de PRG RAM.
* Mappers: NROM, MMC1, UxROM, CNROM, MMC3 parcial, MMC5 parcial.
* Debug: logs de atributo/nametable/palette, pipeline, snapshots ricos, spin watchdog, disassembly.
* UX: HUD, fullscreen, proporção, scanlines, pausa, turbo, fast-forward, hotkeys configuráveis.
* Config: `emulator.ini` abrangente (precedência CLI > INI > default).

Planejado (curto prazo): Canal DMC, IRQ MMC3, timing avançado de sprite 0, save state v3, indicador turbo no HUD, configuração de áudio (taxa/latência/ganho).

### Build
Requer Java 21+ e Maven.
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