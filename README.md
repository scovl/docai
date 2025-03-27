# DocAI - Assistente RAG para Documentação Técnica

Um aplicativo Clojure que implementa um sistema RAG (Retrieval Augmented Generation) para consulta de documentação técnica. O DocAI permite buscar informações em documentos técnicos e receber respostas contextualmente relevantes.

## Características

- Suporte a documentos em formato Markdown e HTML
- Três implementações de RAG:
  1. **TF-IDF em memória**: Processamento leve sem dependências externas
  2. **PostgreSQL com pgvector**: Busca semântica escalável usando embeddings densos via pgai
  3. **RAG Avançado com Agentes**: Processamento de consultas complexas usando workflows com agentes
- Geração de respostas usando Ollama (modelo deepseek-r1)
- Processamento de chunking automático de documentos
- Integração com PostgreSQL, pgvector e pgai para busca semântica robusta
- Reranqueamento de resultados para maior precisão
- Workflows com agentes para consultas complexas multi-etapas
- Monitoramento e métricas para avaliar qualidade das respostas

## Pré-requisitos

- [Leiningen](https://leiningen.org/) 2.9.0 ou superior
- [Ollama](https://ollama.com/) (para geração de respostas e embeddings)
- [Docker](https://www.docker.com/) ou [Podman](https://podman.io/) (para o modo PostgreSQL)

## Dependências principais

- **Clojure**: 1.11.1
- **markdown-to-hiccup** e **hickory**: Para processamento de documentos Markdown e HTML
- **next.jdbc**: 1.3.1002 para integração com PostgreSQL
- **PostgreSQL**: 42.7.2 (driver JDBC)
- **Ollama**: Para execução de modelos de linguagem e geração de embeddings

## Instalação

1. Clone o repositório:
   ```
   git clone https://github.com/scovl/docai.git
   cd docai
   ```

2. Configure o ambiente usando o script de execução:
   ```
   # No Linux (usando Podman)
   chmod +x run.sh
   ./run.sh setup

   # No Windows (usando Docker)
   run.bat setup
   ```

3. Para iniciar os containers:
   ```
   # No Linux (usando Podman)
   ./run.sh podman-start
   
   # No Windows (usando Docker)
   run.bat docker-start
   ```

## Uso

### Preparação dos documentos
Coloque seus documentos em Markdown ou HTML na pasta `resources/docs/`. O sistema processará automaticamente todos os arquivos neste diretório.

### Scripts de execução

#### Linux (run.sh - Usa Podman)
```bash
# Modo em memória (TF-IDF)
./run.sh memory

# Modo PostgreSQL
./run.sh postgres

# Modo RAG avançado com agentes
./run.sh advanced

# Iniciar Podman
./run.sh podman-start

# Parar Podman
./run.sh podman-stop

# Ver ajuda
./run.sh help
```

#### Windows (run.bat - Usa Docker)
```batch
# Modo em memória (TF-IDF)
run.bat memory

# Modo PostgreSQL
run.bat postgres

# Modo RAG avançado com agentes
run.bat advanced

# Iniciar Docker
run.bat docker-start

# Parar Docker
run.bat docker-stop

# Ver ajuda
run.bat help
```

> **Nota**: Os comandos para iniciar e parar containers aceitam tanto o formato com hífen (`docker-start`) quanto com underscore (`docker_start`). Ambos funcionam da mesma forma, oferecendo flexibilidade na digitação.

### Comandos disponíveis com lein run

O DocAI oferece diversos comandos para uso direto via lein run:

```bash
# Execução básica (modo em memória com TF-IDF)
lein run

# Modo interativo de busca
lein run --search

# Busca específica
lein run --search "Como implementar autenticação JWT?"

# Modo PostgreSQL com busca semântica
lein run --postgres

# Consulta direta usando PostgreSQL
lein run --postgres "Como implementar autenticação JWT?"

# RAG avançado com workflows inteligentes
lein run --advanced

# Consulta direta usando RAG avançado
lein run --advanced "Como implementar autenticação JWT?"

# Workflow explícito com agentes para consultas complexas
lein run --agents "Compare os diferentes algoritmos de hashing para senhas"

# Processar um único arquivo (TF-IDF)
lein run --process path/to/file.md

# Processar um único arquivo com chunking dinâmico
lein run --process-dynamic path/to/file.md

# Importar um diretório
lein run --import path/to/directory

# Importar um diretório com chunking dinâmico
lein run --import-dynamic path/to/directory

# Limpar caches e dados temporários
lein run --clean

# Ver métricas dos últimos N dias
lein run --metrics 7

# Fornecer feedback para uma consulta específica
lein run --feedback <query_id>

# Ver ajuda completa
lein run --help

# Ver versão
lein run --version
```

### Modo em memória (TF-IDF)
Para executar o DocAI com a implementação baseada em TF-IDF:

```
# Usando Leiningen diretamente
lein run

# Ou usando o script
./run.sh memory   # Linux
run.bat memory    # Windows
```

Este modo é mais leve e não requer dependências externas, ideal para testes e conjuntos pequenos de documentos.

### Modo PostgreSQL (embeddings densos)
Para executar o DocAI com a implementação baseada em PostgreSQL:

```
# Usando Leiningen diretamente
lein run --postgres

# Ou usando o script (recomendado, pois configura automaticamente o ambiente)
./run.sh postgres   # Linux (Podman)
run.bat postgres    # Windows (Docker)
```

Este modo oferece busca semântica mais robusta e escalável, ideal para grandes conjuntos de documentos.

### Modo RAG Avançado com Agentes
Para executar o DocAI com a implementação avançada que inclui workflows com agentes:

```
# Usando Leiningen diretamente
lein run --advanced

# Ou usando o script (recomendado, pois configura automaticamente o ambiente)
./run.sh advanced   # Linux (Podman)
run.bat advanced    # Windows (Docker)
```

Este modo é o mais poderoso, incorporando:
- Chunking dinâmico adaptativo
- Reranqueamento de resultados
- Workflows com agentes para consultas complexas
- Cache multi-camada para respostas e embeddings
- Monitoramento e métricas avançadas

### Interação
Uma vez iniciado, o DocAI apresentará um prompt onde você pode digitar suas perguntas. Digite `sair` para encerrar o programa.

## Arquitetura

### Modo em memória
```
+-------------+     +-------------+     +-------------+
| Documentos  | --> | Processador | --> | TF-IDF      |
| (MD/HTML)   |     | de Texto    |     | Embeddings  |
+-------------+     +-------------+     +-------------+
                                              |
                                              v
+-------------+     +-------------+     +-------------+
| Resposta    | <-- |    LLM      | <-- | Similarity  |
| ao Usuário  |     | (Ollama)    |     | Search      |
+-------------+     +-------------+     +-------------+
```

### Modo PostgreSQL
```
+-------------+     +-------------+     +-------------+
| Documentos  | --> | PostgreSQL  | --> | pgai        |
| (MD/HTML)   |     |             |     | Vectorizer  |
+-------------+     +-------------+     +-------------+
                                              |
                                              v
+-------------+     +-------------+     +-------------+
| Resposta    | <-- |    LLM      | <-- | pgvector    |
| ao Usuário  |     | (Ollama)    |     | Semantic    |
+-------------+     +-------------+     | Search      |
                                        +-------------+
```

### Modo RAG Avançado com Agentes
```
+-------------+     +-------------+     +-------------+
| Consulta    | --> | Analisador  | --> | Workflow    |
| Complexa    |     | de Consulta |     | de Agentes  |
+-------------+     +-------------+     +-------------+
                                              |
                          +---------+---------+---------+
                          |         |         |         |
                          v         v         v         v
+-------------+     +-------------+     +-------------+
| Documentos  | --> | Agente de   | --> | Agente de   |
| (MD/HTML)   |     | Pesquisa    |     | Raciocínio  |
+-------------+     +-------------+     +-------------+
                                              |
                                              v
+-------------+     +-------------+     +-------------+
| Resposta    | <-- | Sintetizador| <-- | Verificador |
| Final       |     |             |     |             |
+-------------+     +-------------+     +-------------+
```

## Componentes do PostgreSQL

### Extensões utilizadas
- **pgvector**: Armazenamento e busca vetorial de alta performance
- **pgai**: Integração com modelos de IA para geração de embeddings e automação

### Estrutura do banco de dados
- **documentos**: Tabela principal que armazena os documentos
- **documentos_embeddings**: Tabela que armazena os embeddings gerados
- **documentos_embeddings_vectorized**: View que combina documentos e embeddings

### Benefícios do modo PostgreSQL
- Escalabilidade para milhões de documentos
- Busca semântica de alta precisão com embeddings densos
- Persistência dos dados e embeddings
- Possibilidade de indexação avançada (HNSW, IVFFlat)
- Gerenciamento automático de embeddings via pgai vectorizer

## Desenvolvimento

### Ambiente de desenvolvimento
O projeto inclui algumas ferramentas úteis para desenvolvimento:

- **lein-cljfmt**: Para formatação de código
- **lein-kibit**: Para identificação de possíveis melhorias no código

Você pode executar estas ferramentas com os comandos:
```
lein cljfmt fix
lein kibit
```

### Uso do REPL
Durante o desenvolvimento, você pode usar o REPL para testar funcionalidades:

```clojure
;; Para ativar o modo PostgreSQL:
(reset! docai.core/use-postgres true)

;; Para importar documentos para o PostgreSQL:
(docai.core/import-docs-to-postgres)

;; Para configurar o PostgreSQL (criar tabelas, etc):
(docai.pg/setup-pg-rag!)
```

## Problemas comuns

### Erro "Connection refused" ao conectar no PostgreSQL
Este erro ocorre quando o PostgreSQL não está acessível na porta 5432. Verifique se:
1. Os containers Docker/Podman estão em execução (`docker compose ps` ou `podman-compose ps`)
2. O PostgreSQL está iniciado corretamente
3. A porta 5432 não está sendo usada por outro processo

### Erro com next.jdbc
Se você encontrar erros relacionados ao next.jdbc, verifique se está usando a versão correta (1.3.1002).

### Modelos do Ollama
Certifique-se de que os modelos necessários foram baixados:
```
ollama list
```

## Diferenças entre Docker e Podman

O projeto suporta tanto Docker quanto Podman:

- **Docker**: Utilizado principalmente no Windows através do script `run.bat`
- **Podman**: Alternativa sem privilégios de root, utilizada no Linux através do script `run.sh`

Os containers e configurações são compatíveis com ambos os sistemas, permitindo escolher a opção mais adequada para seu ambiente.

### Variável de ambiente CONTAINER_ENGINE

A aplicação utiliza a variável de ambiente `CONTAINER_ENGINE` para determinar qual motor de container está sendo usado:

- Quando `CONTAINER_ENGINE=docker`: Os comandos e mensagens são adaptados para Docker
- Quando `CONTAINER_ENGINE=podman`: Os comandos e mensagens são adaptados para Podman

Esta variável é definida automaticamente pelos scripts:
- `run.bat` define como "docker" no Windows
- `run.sh` define como "podman" no Linux

Você pode sobrescrever esta configuração definindo a variável manualmente antes de executar a aplicação:

```bash
# Para forçar o uso de Docker no Linux
export CONTAINER_ENGINE=docker
./run.sh postgres

# Para forçar o uso de Podman no Windows (se instalado)
set CONTAINER_ENGINE=podman
run.bat postgres
```

## Licença

Este projeto está licenciado sob os termos da licença [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/).
