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