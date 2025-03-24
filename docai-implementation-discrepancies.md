# Discrepâncias entre RAG.md e a Implementação Atual do DocAI

Este documento descreve as principais diferenças entre o tutorial RAG.md e a implementação atual do sistema DocAI encontrada no código-fonte.

## 1. Implementação de Embeddings (embedding.clj)

### No Tutorial (RAG.md)
O tutorial menciona a criação de um sistema de embeddings, mas não especifica claramente que está usando TF-IDF como método para gerar os embeddings. Adicionalmente, o código apresentado como `embedding.clj` no tutorial é na verdade o conteúdo do arquivo `document.clj`.

### Na Implementação Atual
O arquivo `embedding.clj` implementa um sistema completo de embeddings usando TF-IDF (Term Frequency-Inverse Document Frequency):

- `tokenize`: Divide texto em tokens, removendo tokens muito curtos
- `term-freq`: Calcula a frequência dos termos nos documentos
- `doc-freq`: Calcula a frequência dos documentos para cada termo
- `tf-idf`: Implementa o cálculo do TF-IDF para um documento
- `vectorize`: Converte documento em vetor TF-IDF
- `create-embeddings`: Gera embeddings para uma lista de textos
- `cosine-similarity`: Calcula a similaridade do cosseno entre vetores
- `similarity-search`: Encontra os chunks mais similares

A implementação inclui comentários que esclarecem explicitamente que está usando TF-IDF e não depende de modelos externos, ao contrário do LLM que usa o modelo deepseek-r1 via Ollama.

## 2. Processamento de Documentos (document.clj)

### No Tutorial (RAG.md)
O código apresentado como `document.clj` é na verdade uma duplicação do código de `embedding.clj`.

### Na Implementação Atual
O arquivo `document.clj` real contém funções para:
- Extrair texto de documentos Markdown e HTML
- Processar o texto para criar chunks
- Pré-processar esses chunks para uso no sistema RAG

## 3. Interface com LLM (llm.clj)

### No Tutorial (RAG.md)
A versão apresentada é simplificada e não inclui todas as funções utilitárias.

### Na Implementação Atual
O arquivo `llm.clj` inclui funções adicionais:
- `extract_code_blocks`: Para extrair blocos de código do texto
- `extract_summary`: Para extrair resumos com tamanho máximo
- Melhor tratamento de erros ao se comunicar com o Ollama

## 4. Módulo Principal (core.clj)

### No Tutorial (RAG.md)
Versão simplificada com menos informações de debug.

### Na Implementação Atual
O arquivo `core.clj` inclui:
- Mais mensagens de log com emojis
- Melhor tratamento de erros
- Mais informações de debug durante o processamento de queries
- Verificações adicionais para garantir que o sistema não falhe quando não há chunks ou embeddings disponíveis

## 5. Dependências (project.clj)

As dependências são as mesmas nas duas versões, mas o arquivo real inclui comentários explicativos para cada dependência, facilitando o entendimento do propósito de cada biblioteca.

## Recomendações

Para alinhar o tutorial com a implementação atual, sugere-se:

1. Corrigir o código de `document.clj` no tutorial para refletir sua implementação real
2. Adicionar uma explicação mais detalhada sobre o sistema de embeddings baseado em TF-IDF
3. Incluir informações sobre as funções utilitárias adicionais em `llm.clj`
4. Atualizar o código em `core.clj` para incluir as mensagens de debug e emojis
5. Explicar o propósito de cada dependência no arquivo `project.clj` 