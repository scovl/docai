# DocAI - Assistente RAG para Documentação Técnica

DocAI é uma aplicação Clojure que implementa um sistema RAG (Retrieval-Augmented Generation) para consulta de documentação técnica. Ele permite que você faça perguntas sobre documentação e receba respostas precisas baseadas no conteúdo dos documentos.

## Funcionalidades

- Processamento de documentação em Markdown e HTML
- Geração de embeddings utilizando algoritmo TF-IDF simples
- Busca semântica por similaridade
- Geração de respostas usando o modelo DeepSeek-R1 através do Ollama
- Interface de linha de comando interativa

## Requisitos

- Clojure 1.11.1 ou superior
- Leiningen 2.0.0 ou superior
- [Ollama](https://ollama.com/) para execução local de modelos LLM

## Instalação

1. Clone o repositório:
```bash
git clone https://github.com/seu-usuario/docai.git
cd docai
```

2. Instale o Ollama seguindo as instruções em [ollama.com](https://ollama.com)

3. Baixe o modelo DeepSeek R1:
```bash
ollama pull deepseek-r1
```

4. Coloque sua documentação na pasta `resources/docs/`:
```bash
mkdir -p resources/docs
# Copie seus arquivos .md ou .html para resources/docs/
```

5. Instale as dependências:
```bash
lein deps
```

## Uso

1. Inicie o servidor Ollama:
```bash
ollama serve
```

2. Execute o projeto:
```bash
lein run
```

3. Faça suas perguntas! Por exemplo:
```
🚀 Inicializando DocAI...
✨ Base de conhecimento pronta! Faça sua pergunta:
Como implemento autenticação JWT em Clojure?
```

4. Para sair, digite "sair" quando solicitado.

## Testes

Para executar os testes:

```bash
lein test
```

Seguimos boas práticas de teste em Clojure:
- Importação seletiva de funções em vez de `:refer :all`
- Uso de aliases para namespaces
- Asserções precisas para comportamentos esperados

## Estrutura do Projeto

```
docai/
├── src/
│   └── docai/
│       ├── document.clj  ; Processamento de documentação
│       ├── embedding.clj ; Geração de embeddings usando TF-IDF
│       ├── llm.clj       ; Interface com o Ollama/LLM
│       └── core.clj      ; Lógica principal
├── test/
│   └── docai/
│       └── core_test.clj ; Testes unitários
├── resources/
│   └── docs/            ; Documentação para consulta
├── project.clj          ; Configuração do projeto
├── .gitignore           ; Padrões de arquivos a ignorar
└── README.md           ; Este arquivo
```

## Configuração de Desenvolvimento

Este projeto usa [clj-kondo](https://github.com/clj-kondo/clj-kondo) para linting. Recomendamos instalar e configurar:

```bash
# Instalar clj-kondo
# Verificar código
clj-kondo --lint src
```

## Contribuindo

Contribuições são bem-vindas! Por favor, sinta-se à vontade para:

1. Fazer um fork do projeto
2. Criar uma branch para sua feature (`git checkout -b feature/AmazingFeature`)
3. Commit suas mudanças (`git commit -m 'Add some AmazingFeature'`)
4. Push para a branch (`git push origin feature/AmazingFeature`)
5. Abrir um Pull Request

## Licença

Este projeto está licenciado sob a licença EPL-2.0 - veja o arquivo [LICENSE](LICENSE) para detalhes.

## Referências

- [RAG](https://www.pinecone.io/learn/rag/)
- [Embedding](https://www.pinecone.io/learn/embeddings/)
- [LLM](https://www.pinecone.io/learn/llms/)
- [Ollama](https://ollama.com/)
- [Clojure](https://clojure.org/)
- [clojure.test](https://clojure.github.io/clojure/clojure.test-api.html)
- [clj-kondo](https://github.com/clj-kondo/clj-kondo)
