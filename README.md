# Nesemu - NES Emulator em Java

Projeto experimental de emulação do NES (CPU/PPU) em Java, com foco atual em estudo do pipeline de background da PPU e ferramentas de inspeção/diagnóstico.

## Build

Requer Java 17+ e Maven.

```powershell
mvn package
```

Artefato gerado: `target/nesemu-1.0-SNAPSHOT.jar`.

## Execução básica

Sem argumentos (usa ROM padrão definida no código):

```powershell
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main --frames=60
```

Especificando ROM e abrindo GUI com HUD:

```powershell
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main D:\caminho\jogo.nes --gui --hud
```

## Modos de teste de background (sintéticos)

Estes padrões ignoram o pipeline real e desenham padrões controlados úteis para validar scroll, fine X, atributos e atualização de tela.

| Flag | Descrição |
|------|-----------|
| `--test-bands` / `--test-bands-h` | 5 faixas horizontais (cada ~48 linhas) |
| `--test-bands-v` | 5 faixas verticais (cada ~51 pixels) |
| `--test-checker` / `--test-xadrez` | Tabuleiro 8x8 alternando 4 cores (por tile) |

Exemplos:
```powershell
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main --gui --test-bands-h --hud
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main --gui --test-bands-v --hud
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main --gui --test-checker --hud
```

Observações:
* Os modos de teste forçam cores de índices 1..5 na palette para visibilidade.
* O bit de grayscale (PPUMASK bit0) é ignorado nos padrões para evitar tons cinza.
* Use `--hud` para acompanhar frame / scanline / ciclo / fineX.

## Flags de diagnóstico de background

| Flag | Função |
|------|--------|
| `--force-bg` | Força o bit de background em PPUMASK (bit 3) independentemente do jogo |
| `--dbg-bg-sample=N` | Loga até N pixels de background (pipeline real) |
| `--dbg-bg-all` | Em conjunto com `--dbg-bg-sample`, permite log massivo (limite maior) |
| `--bg-col-stats` | Imprime estatísticas de pixels !=0 por coluna (256) e por coluna de tiles (32) |
| `--tile-matrix=first|center|nonzero` | ASCII matrix: escolha de pixel representativo por tile ao gerar matriz |
| `--timing-simple` | Modo simplificado de mapeamento ciclo->pixel (para investigação) |
| `--init-scroll` | Inicializa registros de scroll/VRAM para um estado conhecido |
| `--log-attr[=N]` | Log runtime de writes na attribute table (limite default 200) |
| `--log-nt[=N]` | Log runtime de writes na nametable (tiles) |
| `--nt-baseline=HH` | Filtra valor baseline (hex) em logs de nametable |
| `--dump-nt` | Imprime IDs de tiles da nametable principal |
| `--dump-pattern=TT` | Dump de um tile de pattern table (hex) |
| `--dump-patterns=TT,UU,...` | Dump em sequência de múltiplos tiles |
| `--frames=N` | Número de frames a simular em modo headless |
| `--until-vblank` | Executa instruções até primeiro vblank (para testes iniciais) |
| `--hud` | Exibe overlay (FPS, frame, scanline, ciclo, VRAM, MASK, STATUS, fineX) na GUI |

Exemplos combinando diagnóstico:
```powershell
# Força background, imprime estatísticas, gera PPM e HUD
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main --gui --hud --force-bg --bg-col-stats --frames=120

# Investigar pipeline real capturando 80 amostras de pixels
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main --dbg-bg-sample=80 --frames=30

# Log de attribute + nametable com filtro baseline 24 (hex) e depois dump de pattern 24
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main --log-attr=300 --log-nt=300 --nt-baseline=24 --dump-pattern=24 --frames=10
```

## Saídas geradas

| Arquivo | Conteúdo |
|---------|----------|
| `background.ppm` | PPM (nível de cinza por índice de palette ou padrão sintético) |
| Console | Logs de pipeline, estatísticas, matriz de tiles, dumps de tiles |

## Notas de implementação

* A renderização normal utiliza shift registers (16 bits) e latências de 8 ciclos; o modo de teste pula esse pipeline.
* `--timing-simple` altera o mapeamento de ciclo para coluna para facilitar depuração inicial.
* O skip de ciclo em frames ímpares no pre-render está implementado (ciclo 339 -> próximo scanline) quando render ativo.

## Próximos passos sugeridos

* Adicionar padrão de gradiente horizontal para validar fine X sub‑pixel.
* Teste de mudança dinâmica de palette por scanline (raster effect simulado).
* Exportar série de frames em lote para análises externas.

---
Projeto em evolução; partes do PPU (sprites, scroll fino completo entre tiles, eventos precisos) ainda estão incompletas.
