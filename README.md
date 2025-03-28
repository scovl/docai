# DocAI - Assistente RAG para Documentação Técnica

Um sistema RAG (Retrieval Augmented Generation) em Clojure para consulta de documentação técnica com respostas contextualizadas.

## Características

- **Formatos suportados**: Markdown, HTML
- **Implementações**:
  1. **TF-IDF em memória**: Leve, sem dependências externas
  2. **PostgreSQL/pgvector**: Busca semântica escalável
  3. **RAG Avançado com Agentes**: Para consultas complexas
- **Recursos**: Chunking dinâmico, reranqueamento, cache multi-camada, monitoramento

## Pré-requisitos

- [Leiningen](https://leiningen.org/) 2.9.0+
- [Ollama](https://ollama.com/) (LLM deepseek-r1)
- [Docker](https://www.docker.com/) ou [Podman](https://podman.io/) (para PostgreSQL)

## Início Rápido

1. **Clone e configure**:
   ```bash
   git clone https://github.com/scovl/docai.git && cd docai
   
   # Linux
   chmod +x run.sh && ./run.sh setup
   # Windows
   run.bat setup
   ```

2. **Inicie os containers**:
   ```bash
   # Linux
   ./run.sh podman-start
   # Windows
   run.bat docker-start
   ```

3. **Coloque documentos** em `resources/docs/`

4. **Execute**:
   ```bash
   # Modo básico
   ./run.sh memory    # Linux
   run.bat memory     # Windows
   
   # Modo avançado
   ./run.sh advanced  # Linux
   run.bat advanced   # Windows
   ```

## Modos de Operação

### Scripts de Execução

**Linux** (`run.sh`) e **Windows** (`run.bat`) suportam:
- `memory` - Modo TF-IDF em memória
- `postgres` - Modo PostgreSQL com embeddings densos
- `advanced` - Modo RAG avançado com agentes
- `podman-start/docker-start` - Inicia containers
- `podman-stop/docker-stop` - Para containers
- `help` - Ajuda

### Lein Run (exemplos principais)

```bash
# Básico (TF-IDF em memória)
lein run

# PostgreSQL
lein run --postgres

# RAG avançado
lein run --advanced

# Consulta direta
lein run --advanced "Como implementar autenticação JWT?"

# Workflow com agentes
lein run --agents "Compare os algoritmos de hashing"

# Importar diretório
lein run --import path/to/directory

# Ver métricas
lein run --metrics 7
```

> Use `lein run --help` para ver todos os comandos

## Arquitetura

### Fluxos de Processamento

**Modo em memória (TF-IDF)**
```
Documento → Processador → TF-IDF → Similarity Search → LLM → Resposta
```

**Modo PostgreSQL**
```
Documento → PostgreSQL → pgai/pgvector → Busca Semântica → LLM → Resposta
```

**Modo RAG Avançado**
```
Consulta → Analisador → Workflow de Agentes → Documentos → 
Agentes (Pesquisa/Raciocínio) → Verificador → Sintetizador → Resposta
```

### Componentes PostgreSQL

- **Extensões**: pgvector, pgai
- **Tabelas**: documentos, documentos_embeddings
- **Benefícios**: Escalabilidade, busca semântica precisa, persistência

## Desenvolvimento

```clojure
;; Ativar PostgreSQL no REPL
(reset! docai.core/use-postgres true)
(docai.pg/setup-pg-rag!)
(docai.core/import-docs-to-postgres)
```

**Ferramentas**: 
- Formatação: `lein cljfmt fix`
- Análise: `lein kibit`

## Solução de Problemas

- **PostgreSQL inacessível**: Verifique containers e porta 5432
- **Erros next.jdbc**: Confirme versão 1.3.1002
- **Modelos ausentes**: Execute `ollama list`

## Ambiente Contêiner

O projeto suporta Docker (Windows) e Podman (Linux) via variável `CONTAINER_ENGINE`.

## Licença

[Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/)
