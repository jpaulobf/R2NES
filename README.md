<div style="background-color:#071E35;">

# R2NES - NES Emulator em Java

Projeto experimental de emulação do NES (CPU/PPU) em Java, com foco atual em estudo do pipeline de background da PPU e ferramentas de inspeção/diagnóstico.

<div align="center">
  <img src="./nesemu/.assets/logo.png"/>
</div>

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

## Teste de uma ROM real

Exemplos:
```powershell
java -jar target\nesemu-1.0-SNAPSHOT.jar --gui --hud roms\donkeykong.nes
```

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
| `--quiet` / `--no-debug` | Desliga logs verbosos (PPU writes, DMA, mudanças de MASK/CTRL, dumps de OAM) |
| `--verbose` | Força reativação de logs verbosos (override caso futuro default seja silencioso) |

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

### Controle de verbosidade

O sistema de logs foi refatorado para um logger interno (`Log`) com níveis e categorias.

### Níveis
`TRACE`, `DEBUG`, `INFO` (default), `WARN`, `ERROR`.

### Categorias
`CPU, PPU, APU, BUS, DMA, CONTROLLER, ROM, TEST, GENERAL` (todas habilitadas por default). É possível limitar via `--log-cats` ou `log-cats=` no arquivo de configuração.

### Flags de logging
| Flag | Descrição |
|------|-----------|
| `--log-level=LEVEL` | Ajusta nível mínimo global (ex: DEBUG) |
| `--log-cats=LIST` | Lista separada por vírgulas (`CPU,PPU`) ou `ALL` |
| `--log-ts` | Adiciona timestamp (HH:mm:ss.SSS) em cada linha |
| `--reset-key=F1` | Define tecla para reset rápido (CPU+PPU) na GUI |
| `--quiet` / `--no-debug` | Desliga apenas a verbosidade "legada" de PPU/Bus (não muda `log-level`) |
| `--verbose` | Se `--log-level` não for definido, força nível DEBUG (mantém categorias) |

Exemplos:
```powershell
# Depuração focada em PPU e CPU com timestamps
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main roms\donkeykong.nes --gui --log-level=DEBUG --log-cats=PPU,CPU --log-ts

# Modo de rastreamento máximo (todas as categorias, nível TRACE)
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main roms\donkeykong.nes --log-level=TRACE --log-cats=ALL --frames=10

# Reduz ruído de prints legados mantendo logs INFO principais
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main roms\donkeykong.nes --quiet --frames=120

# Define tecla de reset (também pode ir no emulator.ini como reset=F1)
java -cp target/nesemu-1.0-SNAPSHOT.jar com.nesemu.Main roms\donkeykong.nes --gui --reset-key=F1
```

Regras de precedência:
1. Argumentos CLI têm prioridade.
2. Caso a flag não seja passada na linha de comando, busca-se fallback no `emulator.ini`.
3. Se ausente em ambos, aplica-se o default interno (nível=INFO, categorias=ALL, timestamps=off).

### Arquivo `emulator.ini`
Pode ser colocado na raiz ao lado do JAR (`emulator.ini`) ou usado o exemplar em `src/main/java/com/nesemu/config/emulator.ini` (para desenvolvimento). Exemplo resumido:
```ini
# Enable GUI (true/false)
gui=true
# Logger level (TRACE|DEBUG|INFO|WARN|ERROR)
log-level=INFO
# Logger categories (comma list or ALL)
log-cats=PPU,CPU
# Show timestamps (true/false)
log-ts=true
# Reset key (GUI): press to reinitialize CPU+PPU (optional, default none)
reset=F1
# Dump nametable
dump-nt=false
```
Todas as outras flags suportadas pela CLI podem ter a mesma forma (remova o `--` e use `=` ou `true/false`). Comentários começam com `#`.

### Interação com flags de diagnóstico
Flags que habilitam logs específicos (ex: `--log-attr`, `--log-nt`, `--pipe-log`, `--dbg-bg-sample`) produzem saída mesmo que você tenha reduzido categorias — contanto que a categoria correspondente (geralmente `PPU`) permaneça ativa e o nível seja suficiente. Para silenciar integralmente, ajuste tanto nível/categorias quanto não habilite as flags produtoras de log.

Observação de precisão: o pipeline de sprites atualmente usa semântica de Y "direta" (valor de OAM é a linha superior do sprite) para alinhar com a suíte de testes. O hardware real armazena Y-1; poderá ser adicionada flag de compatibilidade futura.

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
</div>