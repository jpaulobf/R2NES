# Meta / Critério de Pronto (MVP Mapper0)
Rodar Donkey Kong / Super Mario Bros título+gameplay base (scroll, colisões) com input funcional; sprites corretos (posição, prioridade, transparência), background coerente, sem travar; timing estável (60 fps ±); sem áudio (opcional stub).

## 1. CPU (se já OK, apenas validar)
- Instruções oficiais + addressing modes (100%)
- Flags corretos (ADC/SBC, décimal desativado)
- Page crossing ciclos extras
- NMI/IRQ/RESET vetores
- Teste: nestest.log comparação parcial (PC, A,X,Y,P,SP)

## 2. Sincronização CPU↔PPU
- 3 PPU clocks por ciclo CPU
- NMI somente início vblank (scan 241 cyc 1)
- Odd frame skip (quando background enabled) ciclo 339 pre-render
- Testes: ROMs ppu_vbl_nmi / frame_timing (se disponíveis)

## 3. PPU Background Timing Completo
- Incremento horizontal coarse X nos ciclos: cada tile fetch + carry nametable
- Incremento vertical (fineY+coarseY+nametable bits) ciclo 256
- Cópia horizontal t->v ciclo 257
- Cópia vertical bits (fineY + ntY + coarseY alta) ciclos 280–304 (pre-render)
- Fine X aplicado aos shifters (já parcialmente)
- Mask bits (grayscale, left 8px) corretos
- Teste: scroll_test ROM / SMB status bar vs gameplay (split)

## 4. Palette / Mirroring Refinos
- Correção 3F10/14/18/1C mirror
- Writes com &1F e exceções universal color
- Emphasis bits (já) + validação grayscale
- Teste: paltest ROM (opcional)

## 5. Name Table Mirroring
- Horizontal vs Vertical aplicando Mapper0 header
- Acesso $2000–$2FFF + mirroring $3000–$3EFF
- Teste: PPUMirroringTest (já existe)

## 6. Sprites (OAM)
- OAM array 256 bytes
- Escrita $2003/$2004 incremento
- DMA $4014 (copiar 256 bytes da página) – timing pode ser simplificado (bloquear 513/514 ciclos)
- Avaliação de 8 sprites por scanline (var simplificada: percorrer 64, coletar ≤8)
- Fetch pattern sprite (8x8 only) – usar bit 0x08 PPUCTRL para pattern table sprites
- Pixel mux: prioridade sobre background exceto bg=0, sprite pixel=0 transparente
- Sprite 0 hit: set bit 6 STATUS se sprite0 pixel nonzero & bg nonzero & x>=1 & rendering
- Left 8px clipping (MASK bit 1 sprites)
- Atributos: palette (bits 0..1), flipping H/V
- Testes: sprite_hit_tests, sprite_overflow_tests (overflow pode ser ignorado inicialmente)

## 7. Controller Input
- Registrador $4016 (strobe + shift bits A,B,Select,Start,Up,Down,Left,Right)
- Implementar polling simples (teclado → estado)
- Teste: mover personagem em ROM real

## 8. Mapper0 (refino)
- PRG banking: 16KB * n (mirror se 16KB único)
- CHR: ROM vs RAM (CHR RAM suporte se pages=0)
- Reset state
- Teste: diferentes ROMs NROM-128 vs NROM-256

## 9. APU Stub (opcional para MVP silencioso)
- Clock avanço (para não travar jogos esperando frame sequências)
- Escrever registros aceita mas ignora saída
- (Ou deixar sem, se jogos-alvo não exigirem timers de áudio críticos)

## 10. Timing / Pacing
- Loop GUI: frame boundary quando PPU completa scanline 240→vblank
- Limitar a ~60 Hz (sleep adaptativo)
- Contador de frames e sync de NMI
- Teste: medir drift após 5 minutos (<1s)

## 11. Dump & Debug (já avançado)
 - Toggle para ver sprite overlay (opcional)
 - Log de sprite 0 hit primeiro frame detectado
 - Test pattern modes (já) – manter isolados

## 12. Test Harness Automatizado
- Unidade: CPU instructions subset (already?)
- Integração: Run nestest até PC final e validar checksum registradores
- PPU: gerar frame hash de test patterns (determinismo)
- CI script (mvn test + exemplos headless)

## 13. Polimento / Erros Comuns
- Reset limpa OAM, scroll latches
- STATUS leitura limpa bit 7 vblank
- PPUSCROLL/PPUADDR write toggle sequências (já parcial?)
- VRAM read buffer pipeline (já para background; validar sprites não usam)
- $2007 address increment (1 ou 32) background CHR fetch consistente

## Ordem Recomendada de Implementação Próxima

- Scrolling preciso (passos 3) – base para jogos com scroll.
- Sprites mínimos sem prioridade avançada → depois prioridade e sprite 0 hit.
- Sprite 0 hit + clipping + flipping.
- Controller input.
- DMA OAM.
- Refinos palette/mirroring edge cases.
- Pacing / frame timing polido.
- APU stub.
- Test harnesses & hashes.


# Critérios de Aceite Resumidos

- Donkey Kong: mostra título, entra gameplay, Mario/Jumpman se move (input), plataformas corretas (background + sprites).
- SMB: scroll lateral funcional nos primeiros passos; sprite 0 hit usado para status bar (sem glitches severos).
- Nenhuma exceção/loop travado após 5 minutos.
- Test patterns ainda funcionam.

# Riscos / Armadilhas

- Sprite 0 hit timing (requere ciclo exato; pode começar simplificado e ajustar)
- Vertical increment + nome de nametable bits (erro comum)
- DMA bloqueando CPU ciclos (pode causar timing divergente se ignorado)
- Input latch (strobe) inversão de bits

# Métricas Sugeridas
- Frames por segundo real vs alvo
- % instruções CPU testadas (nestest)
- Hash de buffer final por N frames para regressão
- Latência média frame loop

---

# Roadmap Avançado PPU (Pós-MVP / Próximas Iterações)

Objetivo: elevar precisão visual/temporal, compatibilidade com mappers (MMC3 IRQ), performance e modularidade para facilitar APU e recursos avançados (MMC5 / PAL) futuramente.

## Prioridade (Ordem Sugerida)
1. Máscara 8px esquerda + sprite/background enable bits corretos.
2. Odd frame cycle skip (ciclo 339 linha pré-render quando rendering ativo).
3. Sprite 0 hit preciso (BG!=0 & SPR!=0 & x>=1 & prioridade; ignorar transparente / clipping).
4. IRQ MMC3 (detecção borda A12 + contador + reload + IRQ CPU).
5. Cache de tiles (decode CHR → 8 bytes) + invalidação por página / banco.
6. Open bus / valores power-on plausíveis / buffer de leitura $2007 e paleta.
7. Grayscale + emphasis bits (aplicar na paleta final / LUT de cor).
8. Refator modular (BackgroundUnit, SpriteUnit, PPUMemory interface).
9. Testes de ciclo e golden frames (hash frame) para regressão.
10. Paleta NTSC mais fiel + filtros opcionais (scanline leve, nearest/hq2x simples).
11. Recursos avançados (sprite overflow real, MMC5 ExRAM modos, suporte PAL, overlays debug).

## Detalhamento de Tarefas

### Precisão / Correções
- [ ] Implementar máscara dos 8 primeiros pixels (BG/Sprite) respeitando PPUMASK bits 1 e 2.
- [ ] Odd frame skip (pular 1 ciclo na linha de pré-render em frames ímpares com BG ou Sprite habilitado).
- [ ] Revisar/instrumentar sprite 0 hit (condições: BG px !=0, SPR px !=0, x>=1, prioridades, clipping aplicado).
- [ ] Sprite overflow (modelo de avaliação parcial real – fase posterior, não bloquear avanço inicial).
- [ ] Estados power-on/reset para registradores PPU (valores iniciais documentados) e OAM indefinido (preencher com 0xFF / rand opcional debug).
- [ ] Open bus para leituras de registradores não totalmente definidos (preservar bits de último valor no barramento interno).
- [ ] Buffer interno de leitura $2007 (1-ciclo delay exceto paleta) validado por testes.
- [ ] Incrementos de v (vertical/horizontal) nos ciclos exatos (256, 257, 280-304) consolidar em scheduler.
- [ ] Grayscale (PPUMASK bit 0) – forçar bits de cor para escala cinza.
- [ ] Emphasis bits (PPUMASK 5..7) – aplicar multiplicadores YIQ/RGB.

### Integração com Mappers / IRQ
- [ ] A12 edge tracking (low→high) apenas em fetch de pattern table alto, ignorando fetches supressos.
- [ ] IRQ latch, reload, contagem e clear coerentes (MMC3) – coordenação com `Mapper4`.
- [ ] Hook limpo no PPU para notificar mapper sobre fetch CHR (para mappers que dependem de timing futuro).

### Performance
- [ ] Cache decode de tiles CHR (8x8 → 64 pixels ou 8 bytes planar expandido) por banco + tag de versão.
- [ ] Cache secundário para variantes com emphasis/grayscale (ou LUT pós-processo rápido).
- [ ] Minimizar branches no pipeline (lookup tabelado de prioridade/transparência).
- [ ] Agrupar nametable/attribute/pattern fetch em estrutura compacta (prefetch tile state).

### Arquitetura / Limpeza
- [ ] Extrair `BackgroundUnit` (shifters, fetch sequencer) e `SpriteUnit` (OAM eval + pixel mux).
- [ ] Criar interface leve `PPUMemory` para desacoplar acesso direto ao Mapper no hot-path.
- [ ] Agrupar registradores temporários/scroll em objeto (`PpuScrollState`).
- [ ] Tabela de micro-op (scheduler por ciclo) para reduzir condicionais espalhadas.

### Testes / QA
- [ ] Testes unitários de incremento scroll (ciclos 256/257/280-304).
- [ ] Testes sprite 0 hit em bordas e com prioridade atrás do BG.
- [ ] Golden frame hash para ROMs de teste (ex: `ppu_vbl_nmi`, `sprite_hit`, `mmc3_irq`).
- [ ] Teste de hash para CHR decode cache (garantir invalidação ao escrever CHR RAM / trocar banco).
- [ ] Modo debug: overlay destacando fetch atual / contagem de sprites por scanline.

### Funcionalidades Opcionais / UX
- [ ] Overscan configurável (crop vertical/horizontal) e seleção NTSC/PAL.
- [ ] Filtros de apresentação (scanlines, nearest, simples upscale shader). 
- [ ] Export frame atual (PNG/PPM) com paleta correta.

### Precisão de Cor
- [ ] Revisar tabela de cores via aproximação NTSC (YIQ → RGB) e gerar LUT.
- [ ] Aplicar emphasis per-pixel após composição (não só global se necessário).

### Robustez
- [ ] Garantir writes $2005/$2006 fora das janelas críticas não quebram estado inconsistente (sincronizar latch).
- [ ] OAM secundária: preencher entradas não usadas com 0xFF.
- [ ] Proteção contra underflow/overflow em índices de shifter.

### Futuro / Avançado
- [ ] MMC5 ExRAM (modo tile attribute / split screen) integrado ao pipeline.
- [ ] Sprite overflow real (bug scanning) com fidelidade a testes oficiais.
- [ ] Suporte PAL (linhas adicionais + matriz de cor diferente + timing NMI).
- [ ] Ferramenta interna de gravação de vídeo (sequence de frames) para regressão visual.

## Notas de Implementação
- Introduzir mudanças críticas (IRQ MMC3, scheduler) isoladas atrás de flags de feature para comparação A/B.
- Medir desempenho antes/depois (ms/frame) para validar impacto do cache de tiles.
- Cada nova funcionalidade deve vir acompanhada de pelo menos 1 teste (hash frame ou unit) para prevenir regressão.

## Indicadores de Conclusão (Roadmap)
- MMC3 IRQ funcional em ROMs de teste (split status bars) sem jitter.
- Hash de referência estável por 100 frames em 3+ ROMs.
- Nenhum branch quente com >5% dos ciclos de CPU (profiling Java) sem justificativa.
- Fácil isolamento de unidades (SpriteUnit / BackgroundUnit) em testes headless.

---
## Save State / Snapshot Roadmap (Novos Itens)

Objetivo: robustez total, zero travamentos raros ao carregar mid-frame, compatibilidade futura.

### Entregas Planejadas
- [ ] Versão 3 do formato: incluir shifters (patternLow/HighShift, attributeLow/HighShift), latches (ntLatch, atLatch, pattern latches) para retomada pixel-exata do frame.
- [ ] Persistir prepared sprite list (preparedSpriteIndices + count + linha) para evitar pequeno pop no primeiro scanline pós-load.
- [ ] Serializar fine pipeline prefetch (prefetchHadFirstTile + bytes A) se em pré-render.
- [ ] APU state skeleton (frame sequencer, pulse/triangle/noise regs) – placeholder até implementação completa.
- [ ] Mapper 2/3/4/5 registros internos e CHR/PRG RAM específicos (incl. MMC3 IRQ counters, MMC1 shift reg já incluso).
- [ ] Compressão opcional (LZ4) via flag INI `save-state-compress=true` (fallback descompr.)
- [ ] Hash/checksum por seção (header + CRC32) para detecção de corrupção.
- [ ] Modo multi-slot: `save-state-slot=N` ciclo entre `slot0..slot9`.
- [ ] CLI: `--save-state=path.state` para dump imediato headless.
- [ ] Auto-recovery watchdog: se frame não avança X ms após load -> normalizar timing novamente.
- [ ] Teste de estresse automatizado: salvar/carregar N vezes em offsets pseudo-aleatórios dentro do frame (gera relatório). 

### Melhorias de UX
- [ ] Mensagem overlay “STATE CORRUPT” diferenciada se checksum inválido.
- [ ] Timestamp e mapper info no nome: `gamename-m001-f12345.state`.
- [ ] Exibir versão do state e mapper no HUD debug (toggle).

### Técnicas de Segurança
- [ ] Escrita dupla (temp + .bak + swap) para cenários de FS instável.
- [ ] Ignorar gracefully campos extras de versões futuras (forward compatible parser).

### Documentação
- [ ] Especificação formal do formato (docs/STATE_FORMAT.md) com tabela de offsets.
- [ ] Seção no README descrevendo limites e política de versionamento de snapshots.

### Métrica de Qualidade
- 0 travamentos em 10.000 ciclos de load repetido (script de estresse).
- Carregamento < 5 ms para states < 128 KB (desktop médio).
- Nenhum frame congelado após load (detecção por watchdog). 
