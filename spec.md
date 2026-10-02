# PROJETO: Adaptar o BitChord (Android) para usar o Navidrome como única fonte de biblioteca

## 1. CONTEXTO

Repositório base: https://github.com/kushagrasinghx/BitChord (Kotlin, Jetpack Compose, Media3/ExoPlayer, licença GPLv3, cerca de 118 mil linhas). Hoje é um cliente de YouTube Music com visual inspirado no Apple Music.

Servidor alvo: Navidrome self-hosted, acessado por HTTPS em https://navidrome.navidromedovitin.lat (Cloudflare Tunnel). Usa a API Subsonic/OpenSubsonic. A biblioteca contém arquivos de música com qualidade original, letras TTML (palavra a palavra) como sidecar e playlists inteligentes (.nsp) de humor.

Objetivo: transformar o app em um player do Navidrome, com a biblioteca "viva" e totalmente nativa, mantendo 100% do visual, dos componentes e das animações atuais.

## 2. REGRAS ABSOLUTAS (valem para todas as fases)

R1. PROIBIDO alterar layout, espaçamentos, tipografia, cores, formas, animações, componentes existentes (cards, listas, shelves, barras, sheets, player, tema dinâmico pela capa, efeito de vidro fosco). Dados do Navidrome entram nos componentes que já existem, através dos modelos existentes.

R2. Se um recurso exigir um componente que não existe, PARE e pergunte antes. Não crie tela nova, não redesenhe. A única adição visual permitida é o grupo "Navidrome" nas configurações, usando o padrão já existente (SearchableSettingsGroup / SettingsGroup).

R3. Compatibilidade com o upstream: este será um fork que precisa continuar recebendo merges do repositório original. Portanto:
   - Código novo vai em pacotes novos (ex.: com.music.bitchord.data.navidrome).
   - Edições em arquivos existentes devem ser mínimas e pontuais (pontos de roteamento). Não reformatar, não renomear, não mover arquivos, não apagar código do YouTube.
   - Antes de editar um arquivo grande (MainViewModel.kt, PlaybackService.kt, MainActivity.kt), liste exatamente quais linhas/funções serão tocadas.

R4. Modo de fonte: o app opera no modo Navidrome por padrão. O código do YouTube permanece no projeto, mas dormente e inacessível pela interface, por flag de build/configuração. Não há mistura de fontes na mesma tela.

R5. Fora de escopo, não implementar e ocultar pela flag quando aparecerem na UI: vídeos, login Google, inscrever-se em artistas, moods/gêneros do YouTube, rádios do YouTube, histórico da conta, Listen Together.

R6. Segurança: nenhuma credencial no código ou no repositório. Usuário, senha e URL do servidor são digitados nas configurações e guardados de forma segura (EncryptedSharedPreferences/Keystore). URLs de stream com token NUNCA são persistidas em fila, cache ou banco; são geradas na hora de tocar.

R7. Toda string nova vai em strings.xml (com tradução pt-BR e en). Nenhum texto fixo em Compose.

R8. Em caso de dúvida sobre comportamento do servidor, teste contra o servidor real ou a documentação oficial (navidrome.org/docs e opensubsonic.netlify.app) em vez de assumir.

## 3. FASE 0 — AUDITORIA OBRIGATÓRIA (antes de qualquer alteração)

Entregue um relatório (sem alterar código) cobrindo:

a) Mapa completo de tudo que depende do YouTube. Já sei que os principais pontos são YtMusicRepository (cerca de 45 métodos chamados fora de data/), MainViewModel, PlaybackService, MainActivity, Autoplay, ListeningRecorder, ArtistFacts, Downloader e StreamResolver. Complete o mapa e classifique cada ponto como: (1) trocar por Navidrome, (2) ocultar, (3) manter inalterado.

b) Áreas que a minha análise NÃO cobriu a fundo e que você deve ler inteiras: PlaybackService.kt, AudioCache.kt, CrossfadeController.kt, playback/smart (TrackAnalyzer, TransitionPlanner), Downloads.kt, PartySync, widget/, Android Auto, Discord Rich Presence, data/stats. Para cada uma, diga se funciona com faixas do Navidrome e o que precisa mudar.

c) Teste prático (se houver acesso ao servidor): uma faixa com URL HTTP do Navidrome passa pela cadeia de DataSource (AudioCache.playbackFactory, toMediaItem em playback/PlayerConnection.kt) sem ajuste? Gapless e crossfade funcionam com stream transcodificado?

d) Validação do servidor: versão instalada do Navidrome; resposta de getOpenSubsonicExtensions; se Last.fm e Spotify estão configurados (necessário para getArtistInfo2, getTopSongs, getSimilarSongs2); se o servidor entrega letras TTML com timing por palavra via OpenSubsonic (extensão songLyrics v2, estrutura cueLine/cue) e qual é o parâmetro/forma exata para pedir a versão enriquecida em getLyricsBySongId.

e) Autenticação: verificar se o servidor aceita autenticação por API key (extensão OpenSubsonic apiKeyAuthentication). Se sim, preferir; senão, usar token+salt (MD5) gerado por requisição.

f) Lista de riscos e decisões pendentes para eu aprovar. PARE ao terminar a Fase 0 e aguarde minha aprovação.

## 4. FASE 1 — FUNDAÇÃO

1.1 Configurar o fork (branch de trabalho própria; main acompanhando o upstream).
1.2 Criar o pacote data/navidrome/ com:
   - NavidromeClient: cliente HTTP (reaproveitar o OkHttp/Http.kt existente), autenticação, tratamento de erros, parse JSON da resposta subsonic-response.
   - NavidromeConfig/Store: servidor, usuário, credencial, guardados com segurança.
   - NavidromeRepository: expõe métodos equivalentes aos usados pela UI, devolvendo OS MESMOS modelos de data/model/Models.kt (Song, HomeFeed, HomeShelf, ShelfItem, DetailPage, ArtistPage, LibraryPage, MoodGenreSection, SearchResult, BrowseItem).
1.3 Identidade: ids do Navidrome entram em Song.videoId com prefixo fixo (ex.: "nd:<id>") e em browseId com prefixos por tipo (nd:album:, nd:artist:, nd:playlist:, nd:genre:). Nunca misturar com ids do YouTube.
1.4 Flag de modo de fonte (Navidrome padrão) e ponto único de roteamento: o código que hoje chama YtMusicRepository passa a chamar uma interface fina que decide por prefixo/flag. Sem alterar assinaturas consumidas pela UI.
1.5 Seção "Navidrome" nas configurações: servidor, usuário, senha, botão "Testar conexão" (reaproveitar a lógica do health() de data/sources/MusicSource.kt, que já separa "inalcançável" de "credencial rejeitada").
Critério de aceite: o app abre, conecta ao servidor, mostra erro claro se falhar, e nenhum componente visual foi alterado.

## 5. FASE 2 — BIBLIOTECA E NAVEGAÇÃO

Mapeamento (use estes endpoints; confirme nomes na Fase 0):
- Home: getAlbumList2 (recentes, mais tocados, aleatórios, adicionados recentemente) → HomeShelf/ShelfItem, reaproveitando a estrutura de prateleiras atual.
- Busca: search3 (músicas, álbuns, artistas) → SearchResult (TopTrack/Track/Browse). Sugestões e typeahead: derivar de search3; se não houver equivalente, ocultar sem redesenhar.
- Biblioteca: getStarred2 (curtidas), álbuns, album artists (getArtists), playlists (getPlaylists/getPlaylist) → LibraryPage.
- Curtir/descurtir: star/unstar, sincronizado com LikeState.
- Playlists: criar, renomear, apagar, adicionar e remover faixas (createPlaylist, updatePlaylist, deletePlaylist).
- Página de álbum e de artista: getAlbum, getArtist → DetailPage/ArtistPage.
- Arte: getCoverArt, em alta resolução, pelos loaders (Coil) já usados; adicionar autenticação no carregamento de imagens seguindo o padrão de data/webdav/WebDavCoilAuth.kt.
Critério de aceite: cada tela existente (Home, Busca, Biblioteca, Detalhe de álbum/artista/playlist) exibe dados reais do Navidrome, com layout idêntico ao original.

## 6. FASE 3 — REPRODUÇÃO E QUALIDADE

3.1 Esquema de URI próprio para faixas do Navidrome, resolvido na hora de tocar (seguir o padrão do esquema bitchord:// em toMediaItem). Gerar a URL autenticada apenas no momento da abertura.
3.2 Qualidade: mapear o StreamRequest existente (Lossless, Capped(maxKbps), Best) para o endpoint stream (maxBitRate, format) e para download. Reaproveitar a qualidade por rede (Wi-Fi/dados) já existente.
3.3 Formato: preencher StreamFormat (codec, kbps, sampleRate, bitDepth) com os metadados do Navidrome para alimentar o indicador do player e "Stats for nerds".
3.4 Scrobble: chamar scrobble do Navidrome (now playing e submission) além dos provedores Last.fm/ListenBrainz que já existem; evitar duplicar no Last.fm caso o servidor também envie.
3.5 Downloads offline: reescrever o caminho de download para usar o endpoint do Navidrome, mantendo a mesma UI, nomes e metadados embutidos.
3.6 Autoplay/continuação de fila: substituir a rádio do YouTube por getSimilarSongs2 (se Last.fm estiver ativo) com fallback para getRandomSongs/faixas do mesmo gênero. Sem mudar o comportamento visual.
3.7 Automix, crossfade e gapless: validar com faixas do Navidrome. Se algum não funcionar, documentar e ocultar, sem quebrar o player.
Critério de aceite: tocar, pausar, pular, buscar posição, gapless, crossfade, qualidade por rede, downloads offline e scrobble funcionando com faixas do servidor, inclusive após reiniciar o app com fila restaurada.

## 7. FASE 4 — LETRAS, CAPAS ANIMADAS, ARTISTA E EXPLORE

4.1 Letras (switcher):
   - Criar um provedor NAVIDROME no sistema de letras existente (enum LyricsSource e o when em LyricsRepository.fetch, em data/lyrics/).
   - Converter a resposta do servidor (linhas sincronizadas, cueLine/cue por palavra, agentes) para o modelo LyricLine/LyricWord existente, preservando palavra a palavra, vocais de fundo e alinhamento por agente. Reaproveitar TtmlLyrics.kt se o servidor devolver TTML bruto.
   - Switcher nas configurações do grupo Navidrome: "Letras: padrão do app | Navidrome". No modo "padrão do app", todo o comportamento atual dos provedores permanece. No modo "Navidrome", usar apenas o servidor. DECISÃO PENDENTE: se o servidor não tiver a letra, mostrar "sem letra" ou cair para o modo padrão? Proponha e aguarde minha escolha.
   - Requer servidor com suporte a TTML/word-level (Fase 0d). Se não houver, informe e bloqueie a opção com mensagem explicativa, sem quebrar.
4.2 Capas animadas: NÃO buscar no servidor. Reaproveitar integralmente o CanvasRepository atual (provedores existentes) alimentado por título, artista e álbum das faixas do Navidrome. Confirmar que o filtro de id vazio em canvasFor não descarta faixas do Navidrome e que canvasForAlbum funciona na página de álbum.
4.3 Artista (foto, bio, monthly listeners):
   - Bio e foto: getArtistInfo2 do Navidrome como fonte primária.
   - Monthly listeners: não existe no Navidrome. Reaproveitar o mecanismo do ArtistFacts (busca de artista por nome no YouTube Music, já existente) para obter o número mensal de ouvintes e, onde faltar, foto e bio, SOMENTE para album artists.
   - O match por nome exato (sem diferenciar maiúsculas) não basta para homônimos: exigir confirmação adicional (ex.: sobreposição de álbuns/faixas) e, na dúvida, não exibir o dado em vez de exibir um errado.
   - Isso usa o código do YouTube apenas como leitura pública de metadados, sem login. Controlar por uma opção nas configurações do grupo Navidrome ("Enriquecer artistas com dados externos"), cacheada para não refazer a busca.
   - Preencher ArtistPage/DetailPage (thumbnailUrl, description, monthlyListenerCount) para alimentar os componentes atuais.
4.4 Explore: reaproveitar a tela existente (ExploreScreen e MoodGenrePlaylistsScreen) com os mesmos cartões e a mesma grade.
   - Seção "Gêneros": getGenres → MoodGenre (título, id nd:genre:, capa a partir de um álbum do gênero). Tocar abre a lista de álbuns do gênero no formato HomeShelf.
   - Seção de humor: playlists inteligentes do servidor (ex.: Energia, Relaxar), criadas por mim via .nsp, listadas em uma segunda MoodGenreSection.
   - Se alguma seção não tiver conteúdo, não exibir a seção (nunca exibir vazia).
Critério de aceite: letras palavra a palavra sincronizadas do servidor no player, com o switcher funcionando; capa animada nas faixas compatíveis; páginas de artista com bio, foto e monthly listeners quando houver match confiável; Explore populado e visualmente idêntico.

## 8. FASE 5 — LIMPEZA E ESTABILIZAÇÃO

5.1 Ocultar (por flag) tudo que sobrar do YouTube na interface: login Google, inscrever-se, vídeos, Listen Together. Não apagar código.
5.2 Revisar telas com estado vazio ou erro quando o servidor estiver fora do ar.
5.3 Documentar o que muda em relação ao upstream (lista de arquivos tocados) para facilitar merges futuros.
5.4 Teste completo de regressão visual: comparar capturas de tela de cada tela antes e depois.

## 9. FORMA DE TRABALHO

- Trabalhe fase a fase. Ao final de cada fase, apresente: o que foi feito, arquivos alterados (separando novos de editados), como testar e o que ficou pendente. Aguarde minha aprovação para seguir.
- Commits pequenos, com mensagens descritivas, sem misturar formatação com lógica.
- Nunca modifique os componentes visuais. Se perceber que algo só funciona mudando o visual, pare e me consulte.
- Em qualquer divergência entre este prompt e o que o código real permite, priorize as Regras Absolutas e avise.

## 10. RESULTADO ESPERADO

Um fork do BitChord que abre direto no Navidrome, com visual idêntico ao original, onde: a biblioteca (Home, busca, curtidas, álbuns, artistas, playlists) é 100% do servidor; a reprodução respeita qualidade por rede, gapless, crossfade, downloads e scrobble; as letras palavra a palavra podem vir do servidor ou dos provedores do app; as capas animadas seguem funcionando; as páginas de artista mostram bio, foto e monthly listeners quando há match confiável; o Explore mostra gêneros e playlists de humor do servidor; e existe uma seção "Navidrome" nas configurações. O fork continua recebendo atualizações do repositório original com o menor atrito possível.